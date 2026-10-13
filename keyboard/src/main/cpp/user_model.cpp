// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "user_model.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "proximity.hpp"

namespace borderkeys {
UserModel::UserModel() { clear(); }

namespace {

/** [a] + [b], held at UINT32_MAX. */
uint32_t saturatingAdd(uint32_t a, uint32_t b) {
    const uint64_t sum = static_cast<uint64_t>(a) + b;
    return (sum > UINT32_MAX) ? UINT32_MAX : static_cast<uint32_t>(sum);
}

/** Takes `slot` out of a context's links, the others kept in order; the context keeps its total. */
void unlinkSlot(std::vector<uint32_t>& slots, uint32_t slot) {
    const auto at = std::find(slots.begin(), slots.end(), slot);
    if (at != slots.end()) {
        slots.erase(at);
    }
}

/** Renames `from` to `to` among `slots`. */
void relink(std::vector<uint32_t>& slots, uint32_t from, uint32_t to) {
    const auto at = std::find(slots.begin(), slots.end(), from);
    if (at != slots.end()) {
        *at = to;
    }
}

/**
 * The best `maxOut` of the links at `slots`, most frequent first and, among equals, in the order of
 * `slots`; `linkAt` gives a slot's next entry and count.
 */
template <typename LinkAt>
int bestSuccessors(const std::vector<uint32_t>& slots, LinkAt linkAt, UserModel::Successor* out,
                   int maxOut) {
    const auto before = [](const UserModel::Successor& a, const UserModel::Successor& b) {
        return a.count > b.count;
    };
    int written = 0;
    for (const uint32_t slot : slots) {
        const UserModel::Successor candidate = linkAt(slot);
        if (written == maxOut && !before(candidate, out[written - 1])) {
            continue;
        }
        int at = written < maxOut ? written++ : maxOut - 1;
        while (at > 0 && before(candidate, out[at - 1])) {
            out[at] = out[at - 1];
            --at;
        }
        out[at] = candidate;
    }
    return written;
}

}  // namespace

void UserModel::clear() {
    nodes_.clear();
    entries_.clear();
    stores_.clear();
    nodes_.emplace_back();  // the root
    totalCount_ = 0;
}

uint64_t UserModel::pairKey(int32_t first, int32_t second) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(first)) << kKeyHalfBits) |
           static_cast<uint32_t>(second);
}

int UserModel::wordKey(const char* word, size_t length, char* out) {
    if (word == nullptr || length == 0 || length > kMaxWordBytes) {
        return 0;
    }
    const int bytes = lowerUtf8(word, length, out, kMaxKeyBytes);
    return bytes > 0 ? bytes : 0;
}

size_t UserModel::TrigramKeyHash::operator()(const TrigramKey& key) const {
    const uint64_t context = pairKey(key.previous2Index, key.previous1Index);
    const uint64_t next = static_cast<uint32_t>(key.nextIndex);
    const uint64_t mixed = (context * kGoldenRatioMultiplier) ^ next;
    // The high half folded into the low.
    return static_cast<size_t>(mixed ^ (mixed >> kKeyHalfBits));
}

int32_t UserModel::childOf(int32_t node, uint32_t folded) const {
    const std::vector<std::pair<uint32_t, int32_t>>& children = nodes_[node].children;
    const auto it = std::lower_bound(
        children.begin(), children.end(), folded,
        [](const std::pair<uint32_t, int32_t>& entry, uint32_t value) {
            return entry.first < value;
        });
    if (it == children.end() || it->first != folded) {
        return -1;
    }
    return it->second;
}

int32_t UserModel::childOfOrCreate(int32_t node, uint32_t folded) {
    std::vector<std::pair<uint32_t, int32_t>>& children = nodes_[node].children;
    const auto it = std::lower_bound(
        children.begin(), children.end(), folded,
        [](const std::pair<uint32_t, int32_t>& entry, uint32_t value) {
            return entry.first < value;
        });
    if (it != children.end() && it->first == folded) {
        return it->second;
    }
    const int32_t created = static_cast<int32_t>(nodes_.size());
    // Recorded before emplace_back, which can move `children`.
    const size_t position = static_cast<size_t>(it - children.begin());
    nodes_.emplace_back();
    nodes_[node].children.insert(nodes_[node].children.begin() + position,
                                 std::make_pair(folded, created));
    return created;
}

int32_t UserModel::findNode(const uint32_t* folded, int count) const {
    int32_t node = 0;
    for (int i = 0; i < count; ++i) {
        node = childOf(node, folded[i]);
        if (node < 0) {
            return -1;
        }
    }
    return node;
}

int32_t UserModel::entryWithKey(int32_t node, const char* key, size_t length) const {
    for (const int32_t entry : nodes_[static_cast<size_t>(node)].entries) {
        const std::string& held = entries_[static_cast<size_t>(entry)].key;
        if (held.size() == length && std::memcmp(held.data(), key, length) == 0) {
            return entry;
        }
    }
    return -1;
}

int32_t UserModel::entryFor(const char* word, size_t length) {
    char key[kMaxKeyBytes];
    const int keyLength = wordKey(word, length, key);
    if (keyLength <= 0) {
        return -1;
    }
    uint32_t folded[kMaxWordCodePoints];
    const int count = foldUtf8(word, length, folded, kMaxWordCodePoints);
    if (count <= 0) {
        return -1;
    }
    int32_t node = 0;
    for (int i = 0; i < count; ++i) {
        node = childOfOrCreate(node, folded[i]);
    }
    const int32_t found = entryWithKey(node, key, static_cast<size_t>(keyLength));
    if (found >= 0) {
        return found;
    }
    const int32_t created = static_cast<int32_t>(entries_.size());
    entries_.push_back(Entry{std::string(word, length), std::string(key, static_cast<size_t>(keyLength))});
    nodes_[static_cast<size_t>(node)].entries.push_back(created);
    return created;
}

int32_t UserModel::learn(const char* word, size_t length, bool deliberateCapital,
                         bool asserted) {
    const int32_t index = entryFor(word, length);
    if (index < 0) {
        return -1;
    }
    Entry& entry = entries_[static_cast<size_t>(index)];
    // The last spelling committed is the display form.
    entry.text.assign(word, length);
    if (entry.count < UINT32_MAX) {
        ++entry.count;
    }
    // Never decremented.
    if (deliberateCapital && entry.deliberateCapitals < UINT32_MAX) {
        ++entry.deliberateCapitals;
    }
    if (asserted && entry.asserted < UINT32_MAX) {
        ++entry.asserted;
    }
    if (totalCount_ < UINT32_MAX) {
        ++totalCount_;
    }
    return index;
}

int32_t UserModel::reserve(const char* word, size_t length) {
    return entryFor(word, length);
}

int32_t UserModel::entryIndexFor(const char* word, size_t length) const {
    char key[kMaxKeyBytes];
    const int keyLength = wordKey(word, length, key);
    if (keyLength <= 0) {
        return -1;
    }
    uint32_t folded[kMaxWordCodePoints];
    const int count = foldUtf8(word, length, folded, kMaxWordCodePoints);
    if (count <= 0) {
        return -1;
    }
    const int32_t node = findNode(folded, count);
    return node < 0 ? -1 : entryWithKey(node, key, static_cast<size_t>(keyLength));
}

int32_t UserModel::storeFor(const char* language, size_t length) {
    const int32_t found = findStore(language, length);
    if (found >= 0 || language == nullptr || length == 0) {
        return found;
    }
    stores_.emplace_back();
    stores_.back().language.assign(language, length);
    return static_cast<int32_t>(stores_.size() - 1);
}

int32_t UserModel::findStore(const char* language, size_t length) const {
    if (language == nullptr || length == 0) {
        return -1;
    }
    for (size_t i = 0; i < stores_.size(); ++i) {
        if (stores_[i].language.size() == length &&
            std::memcmp(stores_[i].language.data(), language, length) == 0) {
            return static_cast<int32_t>(i);
        }
    }
    return -1;
}

const UserModel::LinkStore* UserModel::storeAt(int32_t store) const {
    return store >= 0 && store < static_cast<int32_t>(stores_.size())
               ? &stores_[static_cast<size_t>(store)]
               : nullptr;
}

UserModel::LinkStore* UserModel::storeAt(int32_t store) {
    return store >= 0 && store < static_cast<int32_t>(stores_.size())
               ? &stores_[static_cast<size_t>(store)]
               : nullptr;
}

template <typename Link>
bool UserModel::linkable(const Link& link, int32_t entries) {
    const auto indices = link.indices();
    const bool held = std::all_of(indices.begin(), indices.end(), [entries](int32_t index) {
        return index >= 0 && index < entries;
    });
    return held && link.lastContextIndex() != link.nextIndex;
}

template <typename Link>
bool UserModel::add(LinkTable<Link>& table, const Link& link, int most) {
    const auto found = table.slots.find(link.key());
    if (found != table.slots.end()) {
        Link& held = table.links[found->second];
        held.count = saturatingAdd(held.count, link.count);
        held.contextSeen = std::max(held.contextSeen, link.contextSeen);
        held.languageSeen = std::max(held.languageSeen, link.languageSeen);
        Successors& context = table.contexts[link.contextKey()];
        context.total = saturatingAdd(context.total, link.count);
        return true;
    }
    if (static_cast<int>(table.links.size()) >= most) {
        return false;
    }
    const uint32_t slot = static_cast<uint32_t>(table.links.size());
    table.links.push_back(link);
    table.slots.emplace(link.key(), slot);
    Successors& context = table.contexts[link.contextKey()];
    context.slots.push_back(slot);
    context.total = saturatingAdd(context.total, link.count);
    return true;
}

template <typename Link>
void UserModel::removeAt(LinkTable<Link>& table, uint32_t slot) {
    const Link dropped = table.links[slot];
    table.slots.erase(dropped.key());
    unlinkSlot(table.contexts[dropped.contextKey()].slots, slot);
    const uint32_t last = static_cast<uint32_t>(table.links.size() - 1);
    if (slot != last) {
        const Link moved = table.links[last];
        table.links[slot] = moved;
        table.slots[moved.key()] = slot;
        relink(table.contexts[moved.contextKey()].slots, last, slot);
    }
    table.links.pop_back();
}

template <typename Link>
void UserModel::drop(LinkTable<Link>& table, const typename Link::Key& key) {
    const auto found = table.slots.find(key);
    if (found != table.slots.end()) {
        removeAt(table, found->second);
    }
}

template <typename Link>
uint32_t UserModel::countOf(const LinkTable<Link>& table, const typename Link::Key& key) {
    const auto found = table.slots.find(key);
    return found == table.slots.end() ? 0u : table.links[found->second].count;
}

template <typename Link>
uint32_t UserModel::totalOf(const LinkTable<Link>& table, const typename Link::ContextKey& key) {
    const auto found = table.contexts.find(key);
    return found == table.contexts.end() ? 0u : found->second.total;
}

template <typename Link>
int UserModel::successorsOf(const LinkTable<Link>& table, const typename Link::ContextKey& key,
                            Successor* out, int maxOut) {
    if (out == nullptr || maxOut <= 0) {
        return 0;
    }
    const auto found = table.contexts.find(key);
    if (found == table.contexts.end()) {
        return 0;
    }
    return bestSuccessors(
        found->second.slots,
        [&table](uint32_t slot) {
            const Link& link = table.links[slot];
            return Successor{static_cast<uint32_t>(link.nextIndex), link.count};
        },
        out, maxOut);
}

template <typename Link>
int UserModel::linksAfter(const LinkTable<Link>& table, const typename Link::ContextKey& key,
                          ContextLink* out, int maxOut, ContextCount* context) {
    if (context != nullptr) {
        *context = ContextCount{0u, 0u};
    }
    const auto found = table.contexts.find(key);
    if (found == table.contexts.end()) {
        return 0;
    }
    if (context != nullptr) {
        *context = ContextCount{found->second.total, found->second.seen};
    }
    const std::vector<uint32_t>& slots = found->second.slots;
    for (size_t i = 0; out != nullptr && i < slots.size() && static_cast<int>(i) < maxOut; ++i) {
        const Link& link = table.links[slots[i]];
        out[i] = ContextLink{link.nextIndex, link.count, link.contextSeen};
    }
    return static_cast<int>(slots.size());
}

template <typename Link>
const Link* UserModel::linkAt(const LinkTable<Link>& table, int index) {
    return index < 0 || index >= static_cast<int>(table.links.size())
               ? nullptr
               : &table.links[static_cast<size_t>(index)];
}

template <typename Link>
bool UserModel::learnLink(int32_t store, LinkTable<Link> LinkStore::*kind,
                          uint64_t LinkStore::*clock, Link link) {
    LinkStore* const links = storeAt(store);
    if (links == nullptr || !linkable(link, static_cast<int32_t>(entries_.size()))) {
        return false;
    }
    LinkTable<Link>& table = links->*kind;
    Successors& context = table.contexts[link.contextKey()];
    context.seen += link.count;
    links->*clock += link.count;
    link.contextSeen = context.seen;
    link.languageSeen = links->*clock;
    return add(table, link, std::numeric_limits<int>::max());
}

template <typename Link, typename LinkAt>
void UserModel::loadLinks(LinkTable<Link> LinkStore::*kind, const char* const* languages,
                          const size_t* languageLengths, const int32_t* counts, int count,
                          bool wordsGiven, int most, const uint64_t* contextSeen,
                          const uint64_t* languageSeen, LinkAt linkAt) {
    for (LinkStore& store : stores_) {
        LinkTable<Link>& table = store.*kind;
        table.links.clear();
        table.slots.clear();
        table.contexts.clear();
    }
    if (languages == nullptr || counts == nullptr || !wordsGiven) {
        return;
    }
    for (int i = 0; i < count; ++i) {
        if (counts[i] <= 0) {
            continue;
        }
        // A link whose words cannot be held is dropped.
        LinkStore* const links = storeAt(storeFor(languages[i], languageLengths[i]));
        Link link = linkAt(i, static_cast<uint32_t>(counts[i]));
        if (links == nullptr || !linkable(link, static_cast<int32_t>(entries_.size()))) {
            continue;
        }
        link.contextSeen = contextSeen != nullptr ? contextSeen[i] : 0u;
        link.languageSeen = languageSeen != nullptr ? languageSeen[i] : 0u;
        add(links->*kind, link, most);
    }
}

template <typename Link, typename ContextAt>
void UserModel::loadTotals(LinkTable<Link> LinkStore::*kind, const char* const* languages,
                           const size_t* languageLengths, const uint32_t* totals,
                           const uint64_t* seen, int count, bool wordsGiven,
                           ContextAt contextAt) {
    if (languages == nullptr || totals == nullptr || !wordsGiven) {
        return;
    }
    for (int i = 0; i < count; ++i) {
        LinkStore* const links = storeAt(findStore(languages[i], languageLengths[i]));
        typename Link::ContextKey key{};
        if (!contextAt(i, &key) || links == nullptr) {
            continue;
        }
        auto& contexts = (links->*kind).contexts;
        const auto found = contexts.find(key);
        if (found != contexts.end()) {
            found->second.total = std::max(found->second.total, totals[i]);
            if (seen != nullptr) {
                found->second.seen = std::max(found->second.seen, seen[i]);
            }
        }
    }
}

template <typename Link, typename KeyAt>
void UserModel::dropLinks(LinkTable<Link> LinkStore::*kind, const char* const* languages,
                          const size_t* languageLengths, int count, bool wordsGiven, KeyAt keyAt) {
    if (languages == nullptr || !wordsGiven) {
        return;
    }
    for (int i = 0; i < count; ++i) {
        LinkStore* const links = storeAt(findStore(languages[i], languageLengths[i]));
        if (links != nullptr) {
            drop(links->*kind, keyAt(i));
        }
    }
}

bool UserModel::learnBigram(int32_t store, int32_t previousIndex, int32_t nextIndex) {
    return learnLink(store, &LinkStore::pairs, &LinkStore::pairClock,
                     Bigram{previousIndex, nextIndex, 1u});
}

void UserModel::dropBigram(int32_t store, int32_t previousIndex, int32_t nextIndex) {
    LinkStore* const links = storeAt(store);
    if (links != nullptr) {
        drop(links->pairs, Bigram::keyOf(previousIndex, nextIndex));
    }
}

bool UserModel::learnTrigram(int32_t store, int32_t previous2Index, int32_t previous1Index,
                             int32_t nextIndex) {
    return learnLink(store, &LinkStore::triples, &LinkStore::tripleClock,
                     Trigram{previous2Index, previous1Index, nextIndex, 1u});
}

void UserModel::dropTrigram(int32_t store, int32_t previous2Index, int32_t previous1Index,
                            int32_t nextIndex) {
    LinkStore* const links = storeAt(store);
    if (links != nullptr) {
        drop(links->triples, Trigram::keyOf(previous2Index, previous1Index, nextIndex));
    }
}

void UserModel::dropBigrams(const char* const* languages, const size_t* languageLengths,
                            const char* const* previous, const size_t* previousLengths,
                            const char* const* next, const size_t* nextLengths, int count) {
    dropLinks(&LinkStore::pairs, languages, languageLengths, count,
              previous != nullptr && next != nullptr, [&](int i) {
                  return Bigram::keyOf(entryIndexFor(previous[i], previousLengths[i]),
                                       entryIndexFor(next[i], nextLengths[i]));
              });
}

void UserModel::dropTrigrams(const char* const* languages, const size_t* languageLengths,
                             const char* const* previous2, const size_t* previous2Lengths,
                             const char* const* previous1, const size_t* previous1Lengths,
                             const char* const* next, const size_t* nextLengths, int count) {
    dropLinks(&LinkStore::triples, languages, languageLengths, count,
              previous2 != nullptr && previous1 != nullptr && next != nullptr, [&](int i) {
                  return Trigram::keyOf(entryIndexFor(previous2[i], previous2Lengths[i]),
                                        entryIndexFor(previous1[i], previous1Lengths[i]),
                                        entryIndexFor(next[i], nextLengths[i]));
              });
}

uint32_t UserModel::trigramCount(int32_t store, int32_t previous2Index, int32_t previous1Index,
                                 int32_t nextIndex) const {
    const LinkStore* const links = storeAt(store);
    return links == nullptr || !named(previous2Index, previous1Index, nextIndex)
               ? 0u
               : countOf(links->triples, Trigram::keyOf(previous2Index, previous1Index, nextIndex));
}

uint32_t UserModel::trigramTotal(int32_t store, int32_t previous2Index,
                                 int32_t previous1Index) const {
    const LinkStore* const links = storeAt(store);
    return links == nullptr || !named(previous2Index, previous1Index)
               ? 0u
               : totalOf(links->triples, Trigram::contextKeyOf(previous2Index, previous1Index));
}

int UserModel::trigramSuccessors(int32_t store, int32_t previous2Index, int32_t previous1Index,
                                 Successor* out, int maxOut) const {
    const LinkStore* const links = storeAt(store);
    if (links == nullptr || !named(previous2Index, previous1Index)) {
        return 0;
    }
    return successorsOf(links->triples, Trigram::contextKeyOf(previous2Index, previous1Index), out,
                        maxOut);
}

int UserModel::pairsAfter(int32_t store, int32_t previousIndex, ContextLink* out, int maxOut,
                          ContextCount* context) const {
    const LinkStore* const links = storeAt(store);
    if (links == nullptr || !named(previousIndex)) {
        if (context != nullptr) {
            *context = ContextCount{0u, 0u};
        }
        return 0;
    }
    return linksAfter(links->pairs, Bigram::contextKeyOf(previousIndex), out, maxOut, context);
}

int UserModel::triplesAfter(int32_t store, int32_t previous2Index, int32_t previous1Index,
                            ContextLink* out, int maxOut, ContextCount* context) const {
    const LinkStore* const links = storeAt(store);
    if (links == nullptr || !named(previous2Index, previous1Index)) {
        if (context != nullptr) {
            *context = ContextCount{0u, 0u};
        }
        return 0;
    }
    return linksAfter(links->triples, Trigram::contextKeyOf(previous2Index, previous1Index), out,
                      maxOut, context);
}

int UserModel::pairsHeld(int32_t store) const {
    const LinkStore* const links = storeAt(store);
    return links == nullptr ? 0 : static_cast<int>(links->pairs.links.size());
}

int UserModel::triplesHeld(int32_t store) const {
    const LinkStore* const links = storeAt(store);
    return links == nullptr ? 0 : static_cast<int>(links->triples.links.size());
}

void UserModel::tripleAt(int32_t store, int index, int32_t* previous2Index,
                         int32_t* previous1Index, int32_t* nextIndex, uint32_t* count,
                         uint64_t* languageSeen, uint32_t* contextTotal) const {
    const LinkStore* const links = storeAt(store);
    const Trigram* const trigram = links == nullptr ? nullptr : linkAt(links->triples, index);
    if (trigram == nullptr) {
        return;
    }
    if (previous2Index != nullptr) *previous2Index = trigram->previous2Index;
    if (previous1Index != nullptr) *previous1Index = trigram->previous1Index;
    if (nextIndex != nullptr) *nextIndex = trigram->nextIndex;
    if (count != nullptr) *count = trigram->count;
    if (languageSeen != nullptr) *languageSeen = trigram->languageSeen;
    if (contextTotal != nullptr) *contextTotal = totalOf(links->triples, trigram->contextKey());
}

void UserModel::bulkLoadTrigrams(const char* const* languages, const size_t* languageLengths,
                                 const char* const* previous2, const size_t* previous2Lengths,
                                 const char* const* previous1, const size_t* previous1Lengths,
                                 const char* const* next, const size_t* nextLengths,
                                 const int32_t* counts, int count, const uint64_t* contextSeen,
                                 const uint64_t* languageSeen) {
    loadLinks(&LinkStore::triples, languages, languageLengths, counts, count,
              previous2 != nullptr && previous1 != nullptr && next != nullptr, kMaxTrigrams,
              contextSeen, languageSeen, [&](int i, uint32_t uses) {
                  return Trigram{reserve(previous2[i], previous2Lengths[i]),
                                 reserve(previous1[i], previous1Lengths[i]),
                                 reserve(next[i], nextLengths[i]), uses};
              });
}

uint32_t UserModel::bigramCount(int32_t store, int32_t previousIndex, int32_t nextIndex) const {
    const LinkStore* const links = storeAt(store);
    return links == nullptr || !named(previousIndex, nextIndex)
               ? 0u
               : countOf(links->pairs, Bigram::keyOf(previousIndex, nextIndex));
}

uint32_t UserModel::successorTotal(int32_t store, int32_t previousIndex) const {
    const LinkStore* const links = storeAt(store);
    return links == nullptr || !named(previousIndex)
               ? 0u
               : totalOf(links->pairs, Bigram::contextKeyOf(previousIndex));
}

int UserModel::successors(int32_t store, int32_t previousIndex, Successor* out,
                          int maxOut) const {
    const LinkStore* const links = storeAt(store);
    if (links == nullptr || !named(previousIndex)) {
        return 0;
    }
    return successorsOf(links->pairs, Bigram::contextKeyOf(previousIndex), out, maxOut);
}

void UserModel::pairAt(int32_t store, int index, int32_t* previousIndex, int32_t* nextIndex,
                       uint32_t* count, uint64_t* languageSeen, uint32_t* contextTotal) const {
    const LinkStore* const links = storeAt(store);
    const Bigram* const bigram = links == nullptr ? nullptr : linkAt(links->pairs, index);
    if (bigram == nullptr) {
        return;
    }
    if (previousIndex != nullptr) *previousIndex = bigram->previousIndex;
    if (nextIndex != nullptr) *nextIndex = bigram->nextIndex;
    if (count != nullptr) *count = bigram->count;
    if (languageSeen != nullptr) *languageSeen = bigram->languageSeen;
    if (contextTotal != nullptr) *contextTotal = totalOf(links->pairs, bigram->contextKey());
}

void UserModel::bulkLoadBigrams(const char* const* languages, const size_t* languageLengths,
                                const char* const* previous, const size_t* previousLengths,
                                const char* const* next, const size_t* nextLengths,
                                const int32_t* counts, int count, const uint64_t* contextSeen,
                                const uint64_t* languageSeen) {
    loadLinks(&LinkStore::pairs, languages, languageLengths, counts, count,
              previous != nullptr && next != nullptr, kMaxBigrams, contextSeen, languageSeen,
              [&](int i, uint32_t uses) {
                  return Bigram{reserve(previous[i], previousLengths[i]),
                                reserve(next[i], nextLengths[i]), uses};
              });
}

void UserModel::loadPairTotals(const char* const* languages, const size_t* languageLengths,
                               const char* const* previous, const size_t* previousLengths,
                               const uint32_t* totals, int count, const uint64_t* seen) {
    loadTotals(&LinkStore::pairs, languages, languageLengths, totals, seen, count,
               previous != nullptr, [&](int i, Bigram::ContextKey* key) {
                   const int32_t index = entryIndexFor(previous[i], previousLengths[i]);
                   *key = Bigram::contextKeyOf(index);
                   return named(index);
               });
}

void UserModel::loadTripleTotals(const char* const* languages, const size_t* languageLengths,
                                 const char* const* previous2, const size_t* previous2Lengths,
                                 const char* const* previous1, const size_t* previous1Lengths,
                                 const uint32_t* totals, int count, const uint64_t* seen) {
    loadTotals(&LinkStore::triples, languages, languageLengths, totals, seen, count,
               previous2 != nullptr && previous1 != nullptr,
               [&](int i, Trigram::ContextKey* key) {
                   const int32_t index2 = entryIndexFor(previous2[i], previous2Lengths[i]);
                   const int32_t index1 = entryIndexFor(previous1[i], previous1Lengths[i]);
                   *key = Trigram::contextKeyOf(index2, index1);
                   return named(index2, index1);
               });
}

void UserModel::loadClocks(const char* const* languages, const size_t* languageLengths,
                           const uint64_t* pairClocks, const uint64_t* tripleClocks, int count) {
    for (LinkStore& store : stores_) {
        store.pairClock = 0u;
        store.tripleClock = 0u;
    }
    if (languages == nullptr || languageLengths == nullptr || pairClocks == nullptr ||
        tripleClocks == nullptr) {
        return;
    }
    for (int i = 0; i < count; ++i) {
        LinkStore* const links = storeAt(storeFor(languages[i], languageLengths[i]));
        if (links != nullptr) {
            links->pairClock = pairClocks[i];
            links->tripleClock = tripleClocks[i];
        }
    }
}

std::string primaryLanguageOf(const char* tag, size_t length) {
    std::string language;
    for (size_t i = 0; tag != nullptr && i < length && tag[i] != kSubtagSeparator &&
                       tag[i] != kLegacySubtagSeparator;
         ++i) {
        language += static_cast<char>(lowerCodePoint(static_cast<unsigned char>(tag[i])));
    }
    return language.empty() ? std::string(kUnknownLanguage) : language;
}

void UserModel::bulkLoad(const char* const* words, const size_t* lengths, const int32_t* counts,
                         int count, const int32_t* deliberateCapitals, const int32_t* asserted) {
    clear();
    if (words == nullptr || lengths == nullptr || counts == nullptr) {
        return;
    }
    for (int i = 0; i < count; ++i) {
        const int32_t stored = counts[i];
        if (stored <= 0 || words[i] == nullptr || lengths[i] == 0 ||
            lengths[i] > kMaxWordBytes) {
            continue;
        }
        const int32_t index = entryFor(words[i], lengths[i]);
        if (index < 0) {
            continue;
        }
        Entry& entry = entries_[static_cast<size_t>(index)];
        // Another case of a spelling already loaded adds to it; the first loaded keeps
        // its spelling.
        if (entry.count == 0u) {
            entry.text.assign(words[i], lengths[i]);
        }
        entry.count = saturatingAdd(entry.count, static_cast<uint32_t>(stored));
        if (deliberateCapitals != nullptr && deliberateCapitals[i] > 0) {
            entry.deliberateCapitals =
                saturatingAdd(entry.deliberateCapitals, static_cast<uint32_t>(deliberateCapitals[i]));
        }
        if (asserted != nullptr && asserted[i] > 0) {
            entry.asserted = saturatingAdd(entry.asserted, static_cast<uint32_t>(asserted[i]));
        }
        totalCount_ = saturatingAdd(totalCount_, static_cast<uint32_t>(stored));
    }
}

uint32_t UserModel::countFor(const char* word, size_t length) const {
    const int32_t entry = entryIndexFor(word, length);
    return entry < 0 ? 0u : entries_[static_cast<size_t>(entry)].count;
}

const char* UserModel::entryText(uint32_t entryIndex, uint32_t* lengthOut) const {
    if (entryIndex >= entries_.size()) {
        return nullptr;
    }
    const Entry& entry = entries_[entryIndex];
    *lengthOut = static_cast<uint32_t>(entry.text.size());
    return entry.text.data();
}

const char* UserModel::entryKey(uint32_t entryIndex, uint32_t* lengthOut) const {
    if (entryIndex >= entries_.size()) {
        return nullptr;
    }
    const Entry& entry = entries_[entryIndex];
    *lengthOut = static_cast<uint32_t>(entry.key.size());
    return entry.key.data();
}

uint32_t UserModel::entryCount(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].count : 0u;
}

uint32_t UserModel::deliberateCapitals(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].deliberateCapitals : 0u;
}

uint32_t UserModel::asserted(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].asserted : 0u;
}

void UserModel::collect(int32_t node, Completion* out, int maxOut, int* written) const {
    if (*written >= maxOut || node < 0) {
        return;
    }
    const Node& current = nodes_[static_cast<size_t>(node)];
    for (const int32_t index : current.entries) {
        if (*written >= maxOut) {
            return;
        }
        out[*written] =
            Completion{static_cast<uint32_t>(index), entries_[static_cast<size_t>(index)].count};
        ++(*written);
    }
    for (const std::pair<uint32_t, int32_t>& child : current.children) {
        if (*written >= maxOut) {
            return;
        }
        collect(child.second, out, maxOut, written);
    }
}

int UserModel::completions(const uint32_t* foldedPrefix, int prefixLength, Completion* out,
                           int maxOut) const {
    if (out == nullptr || maxOut <= 0 || prefixLength < 0) {
        return 0;
    }
    const int32_t node = findNode(foldedPrefix, prefixLength);
    if (node < 0) {
        return 0;
    }
    int written = 0;
    collect(node, out, maxOut, &written);
    return written;
}

}  // namespace borderkeys
