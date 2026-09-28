// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_ARENA_HPP
#define BORDERKEYS_ARENA_HPP

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <new>

namespace borderkeys {

// A bump allocator holding one malloc for the engine's lifetime; reset() rewinds it between
// requests. Not thread safe: one engine, one prediction thread.
class Arena {
public:
    Arena() = default;
    Arena(const Arena&) = delete;
    Arena& operator=(const Arena&) = delete;

    ~Arena() { release(); }

    bool init(size_t bytes) {
        release();
        base_ = static_cast<uint8_t*>(std::malloc(bytes));
        if (base_ == nullptr) {
            return false;
        }
        capacity_ = bytes;
        used_ = 0;
        return true;
    }

    void release() {
        std::free(base_);
        base_ = nullptr;
        capacity_ = 0;
        used_ = 0;
    }

    // Rewinds to empty. Every pointer handed out before this call is dangling afterwards.
    void reset() { used_ = 0; }

    // Rewinds to a mark taken earlier with used().
    void rewind(size_t mark) {
        if (mark <= used_) {
            used_ = mark;
        }
    }

    void* allocate(size_t bytes, size_t align) {
        const size_t aligned = (used_ + (align - 1)) & ~(align - 1);
        // Exhaustion returns null; the arena never grows.
        if (aligned > capacity_ || bytes > capacity_ - aligned) {
            return nullptr;
        }
        void* const result = base_ + aligned;
        used_ = aligned + bytes;
        return result;
    }

    template <typename T>
    T* allocateArray(size_t count) {
        if (count != 0 && count > SIZE_MAX / sizeof(T)) {
            return nullptr;
        }
        return static_cast<T*>(allocate(count * sizeof(T), alignof(T)));
    }

    size_t capacity() const { return capacity_; }
    size_t used() const { return used_; }

private:
    uint8_t* base_ = nullptr;
    size_t capacity_ = 0;
    size_t used_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_ARENA_HPP
