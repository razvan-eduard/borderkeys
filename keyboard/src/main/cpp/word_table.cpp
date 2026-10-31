// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "word_table.hpp"

#include <algorithm>
#include <cstring>

#include "proximity.hpp"

namespace borderkeys {

WordTable::WordTable() { clear(); }

void WordTable::clear() {
    nodes_.clear();
    entries_.clear();
    nodes_.emplace_back();  // the root
}

int WordTable::wordKey(const char* word, size_t length, char* out) {
    if (word == nullptr || length == 0 || length > kMaxWordBytes) {
        return 0;
    }
    const int bytes = lowerUtf8(word, length, out, kMaxKeyBytes);
    return bytes > 0 ? bytes : 0;
}

int32_t WordTable::childOf(int32_t node, uint32_t folded) const {
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

int32_t WordTable::childOfOrCreate(int32_t node, uint32_t folded) {
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

int32_t WordTable::findNode(const uint32_t* folded, int count) const {
    int32_t node = 0;
    for (int i = 0; i < count; ++i) {
        node = childOf(node, folded[i]);
        if (node < 0) {
            return -1;
        }
    }
    return node;
}

int32_t WordTable::entryWithKey(int32_t node, const char* key, size_t length) const {
    for (const int32_t entry : nodes_[static_cast<size_t>(node)].entries) {
        const std::string& held = entries_[static_cast<size_t>(entry)].key;
        if (held.size() == length && std::memcmp(held.data(), key, length) == 0) {
            return entry;
        }
    }
    return -1;
}

int32_t WordTable::entryFor(const char* word, size_t length, bool* created) {
    *created = false;
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
    const int32_t index = static_cast<int32_t>(entries_.size());
    entries_.push_back(
        Entry{std::string(word, length), std::string(key, static_cast<size_t>(keyLength))});
    nodes_[static_cast<size_t>(node)].entries.push_back(index);
    *created = true;
    return index;
}

void WordTable::setFields(int32_t index, const int32_t* fields) {
    const auto held = [](int32_t value) { return value > 0 ? static_cast<uint32_t>(value) : 0u; };
    Entry& entry = entries_[static_cast<size_t>(index)];
    entry.count = held(fields[kCountField]);
    entry.deliberateCapitals = held(fields[kDeliberateCapitalsField]);
    entry.asserted = held(fields[kAssertedField]);
    entry.added = held(fields[kAddedField]);
}

int WordTable::load(const char* const* spellings, const size_t* lengths, const int32_t* fields,
                    int count, int32_t* outIds) {
    clear();
    if (spellings == nullptr || lengths == nullptr || fields == nullptr) {
        return 0;
    }
    int held = 0;
    for (int i = 0; i < count; ++i) {
        bool created = false;
        const int32_t index = entryFor(spellings[i], lengths[i], &created);
        if (index >= 0 && created) {
            setFields(index, fields + static_cast<size_t>(i) * kFields);
            ++held;
        }
        if (outIds != nullptr) {
            outIds[i] = created ? index : kNewWord;
        }
    }
    return held;
}

int WordTable::put(const int32_t* ids, const char* const* spellings, const size_t* lengths,
                   const int32_t* fields, int count, int32_t* outIds) {
    if (ids == nullptr || fields == nullptr) {
        return 0;
    }
    int held = 0;
    for (int i = 0; i < count; ++i) {
        const char* const spelling = spellings != nullptr ? spellings[i] : nullptr;
        const size_t length = spelling != nullptr && lengths != nullptr ? lengths[i] : 0u;
        int32_t index = kNewWord;
        if (ids[i] == kNewWord) {
            bool created = false;
            const int32_t found = length > 0 ? entryFor(spelling, length, &created) : kNewWord;
            index = created ? found : kNewWord;
        } else if (ids[i] >= 0 && static_cast<size_t>(ids[i]) < entries_.size() &&
                   (length == 0 || entryIndexFor(spelling, length) == ids[i])) {
            index = ids[i];
        }
        if (index >= 0) {
            if (length > 0) {
                entries_[static_cast<size_t>(index)].text.assign(spelling, length);
            }
            setFields(index, fields + static_cast<size_t>(i) * kFields);
            ++held;
        }
        if (outIds != nullptr) {
            outIds[i] = index;
        }
    }
    return held;
}

int32_t WordTable::entryIndexFor(const char* word, size_t length) const {
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

const char* WordTable::entryText(uint32_t entryIndex, uint32_t* lengthOut) const {
    if (entryIndex >= entries_.size()) {
        return nullptr;
    }
    const Entry& entry = entries_[entryIndex];
    *lengthOut = static_cast<uint32_t>(entry.text.size());
    return entry.text.data();
}

uint32_t WordTable::entryCount(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].count : 0u;
}

uint32_t WordTable::deliberateCapitals(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].deliberateCapitals : 0u;
}

uint32_t WordTable::asserted(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].asserted : 0u;
}

uint32_t WordTable::added(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].added : 0u;
}

void WordTable::collect(int32_t node, Completion* out, int maxOut, int* written) const {
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

int WordTable::completions(const uint32_t* foldedPrefix, int prefixLength, Completion* out,
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
