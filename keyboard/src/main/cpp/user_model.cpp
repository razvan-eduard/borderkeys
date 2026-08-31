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
namespace {

// A snapshot is our own file in our own private storage, but it is still parsed after coming
// off a disk that a crash may have truncated mid-write, so it carries the same magic-and-
// version discipline as a language pack.
constexpr uint32_t kSnapshotMagic = 0x314B5542u;  // 'B','U','K','1'
constexpr uint32_t kSnapshotVersion = 1u;
constexpr uint32_t kMaxSnapshotEntries = 1000000u;
constexpr uint32_t kMaxWordBytes = 256u;
constexpr int kMaxWordCodePoints = 64;

}  // namespace

UserModel::UserModel() { clear(); }

void UserModel::clear() {
    nodes_.clear();
    entries_.clear();
    nodes_.emplace_back();  // the root
    totalCount_ = 0;
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
    // Recorded before emplace_back: growing the vector can move it, and `children` above is a
    // reference into the old buffer.
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

void UserModel::learn(const char* word, size_t length) {
    if (word == nullptr || length == 0 || length > kMaxWordBytes) {
        return;
    }
    uint32_t folded[kMaxWordCodePoints];
    const int count = foldUtf8(word, length, folded, kMaxWordCodePoints);
    if (count <= 0) {
        return;
    }

    int32_t node = 0;
    for (int i = 0; i < count; ++i) {
        node = childOfOrCreate(node, folded[i]);
    }
    if (nodes_[node].entryIndex < 0) {
        nodes_[node].entryIndex = static_cast<int32_t>(entries_.size());
        entries_.push_back(Entry{std::string(word, length), 0u});
    }
    Entry& entry = entries_[static_cast<size_t>(nodes_[node].entryIndex)];
    // The display form of the last spelling wins, so that a user who starts writing "masina"
    // and later picks "mașina" ends up with the accented form in their own dictionary.
    entry.text.assign(word, length);
    if (entry.count < UINT32_MAX) {
        ++entry.count;
    }
    if (totalCount_ < UINT32_MAX) {
        ++totalCount_;
    }
}

void UserModel::bulkLoad(const char* const* words, const size_t* lengths, const int32_t* counts,
                         int count) {
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
        uint32_t folded[kMaxWordCodePoints];
        const int folds = foldUtf8(words[i], lengths[i], folded, kMaxWordCodePoints);
        if (folds <= 0) {
            continue;
        }
        int32_t node = 0;
        for (int c = 0; c < folds; ++c) {
            node = childOfOrCreate(node, folded[c]);
        }
        if (nodes_[node].entryIndex < 0) {
            nodes_[node].entryIndex = static_cast<int32_t>(entries_.size());
            entries_.push_back(Entry{std::string(words[i], lengths[i]), 0u});
        }
        Entry& entry = entries_[static_cast<size_t>(nodes_[node].entryIndex)];
        entry.text.assign(words[i], lengths[i]);
        entry.count = static_cast<uint32_t>(stored);
        const uint64_t total = static_cast<uint64_t>(totalCount_) + entry.count;
        totalCount_ = (total > UINT32_MAX) ? UINT32_MAX : static_cast<uint32_t>(total);
    }
}

uint32_t UserModel::countFor(const char* word, size_t length) const {
    if (word == nullptr || length == 0 || length > kMaxWordBytes) {
        return 0;
    }
    uint32_t folded[kMaxWordCodePoints];
    const int count = foldUtf8(word, length, folded, kMaxWordCodePoints);
    if (count <= 0) {
        return 0;
    }
    const int32_t node = findNode(folded, count);
    if (node < 0 || nodes_[static_cast<size_t>(node)].entryIndex < 0) {
        return 0;
    }
    return entries_[static_cast<size_t>(nodes_[static_cast<size_t>(node)].entryIndex)].count;
}

const char* UserModel::entryText(uint32_t entryIndex, uint32_t* lengthOut) const {
    if (entryIndex >= entries_.size()) {
        return nullptr;
    }
    const Entry& entry = entries_[entryIndex];
    *lengthOut = static_cast<uint32_t>(entry.text.size());
    return entry.text.data();
}

uint32_t UserModel::entryCount(uint32_t entryIndex) const {
    return (entryIndex < entries_.size()) ? entries_[entryIndex].count : 0u;
}

void UserModel::collect(int32_t node, Completion* out, int maxOut, int* written) const {
    if (*written >= maxOut || node < 0) {
        return;
    }
    const Node& current = nodes_[static_cast<size_t>(node)];
    if (current.entryIndex >= 0) {
        const Entry& entry = entries_[static_cast<size_t>(current.entryIndex)];
        out[*written] = Completion{static_cast<uint32_t>(current.entryIndex), entry.count};
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

bool UserModel::snapshot(const char* path) const {
    if (path == nullptr) {
        return false;
    }
    // Written to a sibling and renamed, so that a process killed mid-write leaves the previous
    // snapshot intact rather than a truncated one that restore() would then have to reject.
    std::string temporary(path);
    temporary += ".tmp";

    std::FILE* const file = std::fopen(temporary.c_str(), "wb");
    if (file == nullptr) {
        return false;
    }

    bool ok = true;
    const uint32_t header[4] = {kSnapshotMagic, kSnapshotVersion,
                                static_cast<uint32_t>(entries_.size()), totalCount_};
    ok = ok && std::fwrite(header, sizeof(header), 1, file) == 1;
    for (size_t i = 0; ok && i < entries_.size(); ++i) {
        const Entry& entry = entries_[i];
        const uint32_t length = static_cast<uint32_t>(entry.text.size());
        ok = ok && std::fwrite(&entry.count, sizeof(entry.count), 1, file) == 1;
        ok = ok && std::fwrite(&length, sizeof(length), 1, file) == 1;
        ok = ok && (length == 0 || std::fwrite(entry.text.data(), 1, length, file) == length);
    }
    ok = (std::fclose(file) == 0) && ok;
    if (!ok) {
        std::remove(temporary.c_str());
        return false;
    }
    if (std::rename(temporary.c_str(), path) != 0) {
        std::remove(temporary.c_str());
        return false;
    }
    return true;
}

bool UserModel::restore(const char* path) {
    if (path == nullptr) {
        return false;
    }
    std::FILE* const file = std::fopen(path, "rb");
    if (file == nullptr) {
        return false;
    }

    uint32_t header[4];
    if (std::fread(header, sizeof(header), 1, file) != 1 || header[0] != kSnapshotMagic ||
        header[1] != kSnapshotVersion || header[2] > kMaxSnapshotEntries) {
        std::fclose(file);
        return false;
    }

    clear();
    const uint32_t declared = header[2];
    char buffer[kMaxWordBytes];
    for (uint32_t i = 0; i < declared; ++i) {
        uint32_t count = 0;
        uint32_t length = 0;
        if (std::fread(&count, sizeof(count), 1, file) != 1 ||
            std::fread(&length, sizeof(length), 1, file) != 1 || length > kMaxWordBytes) {
            // Truncated or nonsense: keep whatever was read so far rather than throwing away a
            // dictionary because its tail was lost, and report the failure so the caller can
            // fall back to the database, which is the authoritative copy anyway.
            std::fclose(file);
            return false;
        }
        if (length != 0 && std::fread(buffer, 1, length, file) != length) {
            std::fclose(file);
            return false;
        }
        if (length == 0 || count == 0) {
            continue;
        }
        uint32_t folded[kMaxWordCodePoints];
        const int folds = foldUtf8(buffer, length, folded, kMaxWordCodePoints);
        if (folds <= 0) {
            continue;
        }
        int32_t node = 0;
        for (int c = 0; c < folds; ++c) {
            node = childOfOrCreate(node, folded[c]);
        }
        if (nodes_[node].entryIndex < 0) {
            nodes_[node].entryIndex = static_cast<int32_t>(entries_.size());
            entries_.push_back(Entry{std::string(buffer, length), 0u});
        }
        Entry& entry = entries_[static_cast<size_t>(nodes_[node].entryIndex)];
        entry.text.assign(buffer, length);
        entry.count = count;
    }
    totalCount_ = header[3];
    std::fclose(file);
    return true;
}

}  // namespace borderkeys
