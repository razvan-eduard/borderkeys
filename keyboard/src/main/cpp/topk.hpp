// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TOPK_HPP
#define BORDERKEYS_TOPK_HPP

#include <cstdint>

namespace borderkeys {

// Fixed-capacity min-heap that keeps the K highest-scoring items seen.
//
// Non-owning: the caller supplies the storage, which in practice is allocated once at engine
// creation and reused for every request. A search that walks tens of thousands of trie nodes
// calls offer() for each candidate it reaches, so this has to be branch-light and allocation
// free; a sorted insert or a std::priority_queue would be neither.
//
// The heap is a *min*-heap on purpose. The root is the worst item currently kept, which is the
// one comparison that decides whether a new candidate is worth keeping at all -- and once the
// heap is full that comparison rejects the overwhelming majority of candidates in one branch.
//
// T must expose a `float score` member. Larger is better.
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

    // The score a candidate has to beat to be worth constructing in full. Callers use it to
    // skip work, not just to skip an insertion.
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
        if (item.score <= items_[0].score) {
            return;
        }
        items_[0] = item;
        siftDown(0);
    }

    // Direct access for the caller's own de-duplication pass. The heap holds at most sixteen
    // items, so scanning it is cheaper than any auxiliary set would be to maintain -- and a
    // suggestion strip that shows the same word twice is a bug the user sees immediately.
    T* data() { return items_; }
    const T* data() const { return items_; }

    // Replaces an item already in the heap and restores the invariant. A min-heap only needs
    // one direction: a score that went up sinks, a score that went down rises.
    void replaceAt(int index, const T& item) {
        if (index < 0 || index >= size_) {
            return;
        }
        const float previous = items_[index].score;
        items_[index] = item;
        if (item.score < previous) {
            siftUp(index);
        } else {
            siftDown(index);
        }
    }

    // Empties the heap into `out`, best first. Destroys the heap, which is what the caller
    // wants: a request is done with it by the time it reads the results.
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
            if (items_[parent].score <= items_[index].score) {
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
            if (right < size_ && items_[right].score < items_[left].score) {
                smallest = right;
            }
            if (items_[index].score <= items_[smallest].score) {
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
