// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TOPK_HPP
#define BORDERKEYS_TOPK_HPP

#include <cstdint>

namespace borderkeys {

// A fixed-capacity min-heap keeping the K highest-ranking items seen, in storage the caller
// supplies; the root is the worst item kept. T must expose a `float score` member, larger being
// better, and `bool ranksBelow(const T&) const`, which orders equal scores.
template <typename T>
class TopK {
public:
    void reset(T* storage, int capacity) {
        items_ = storage;
        capacity_ = capacity;
        size_ = 0;
    }

    int size() const { return size_; }
    bool empty() const { return size_ == 0; }

    // The score a candidate has to beat to be kept.
    float worstScore() const {
        return (size_ < capacity_) ? -3.0e38f : items_[0].score;
    }

    void offer(const T& item) {
        if (capacity_ <= 0) {
            return;
        }
        if (size_ < capacity_) {
            items_[size_] = item;
            siftUp(size_);
            ++size_;
            return;
        }
        if (!items_[0].ranksBelow(item)) {
            return;
        }
        items_[0] = item;
        siftDown(0);
    }

    // Direct access for the caller's de-duplication pass.
    T* data() { return items_; }
    const T* data() const { return items_; }

    // Replaces an item already in the heap and restores the invariant.
    void replaceAt(int index, const T& item) {
        if (index < 0 || index >= size_) {
            return;
        }
        const T previous = items_[index];
        items_[index] = item;
        if (item.ranksBelow(previous)) {
            siftUp(index);
        } else {
            siftDown(index);
        }
    }

    // Empties the heap into `out`, best first.
    int drainSorted(T* out, int maxOut) {
        int written = 0;
        while (size_ > 0 && written < maxOut) {
            // Repeatedly extracting the minimum yields ascending order, so fill from the back.
            const T worst = items_[0];
            items_[0] = items_[size_ - 1];
            --size_;
            siftDown(0);
            out[written] = worst;
            ++written;
        }
        for (int i = 0, j = written - 1; i < j; ++i, --j) {
            const T tmp = out[i];
            out[i] = out[j];
            out[j] = tmp;
        }
        return written;
    }

private:
    void siftUp(int index) {
        while (index > 0) {
            const int parent = (index - 1) / 2;
            if (!items_[index].ranksBelow(items_[parent])) {
                return;
            }
            const T tmp = items_[parent];
            items_[parent] = items_[index];
            items_[index] = tmp;
            index = parent;
        }
    }

    void siftDown(int index) {
        for (;;) {
            const int left = 2 * index + 1;
            if (left >= size_) {
                return;
            }
            const int right = left + 1;
            int smallest = left;
            if (right < size_ && items_[right].ranksBelow(items_[left])) {
                smallest = right;
            }
            if (!items_[smallest].ranksBelow(items_[index])) {
                return;
            }
            const T tmp = items_[smallest];
            items_[smallest] = items_[index];
            items_[index] = tmp;
            index = smallest;
        }
    }

    T* items_ = nullptr;
    int capacity_ = 0;
    int size_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TOPK_HPP
