// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The global allocation functions of the test binary, counting while an AllocationCount lives.

#include <atomic>
#include <cstdlib>
#include <new>

#include "test_support.hpp"

namespace {

std::atomic<bool> counting{false};
std::atomic<long> allocations{0};

/** The fewest bytes a block takes: a request for none still returns a distinct block. */
constexpr std::size_t kLeastBytes = 1;

void* allocate(std::size_t size) {
    if (counting.load(std::memory_order_relaxed)) {
        allocations.fetch_add(1, std::memory_order_relaxed);
    }
    return std::malloc(size < kLeastBytes ? kLeastBytes : size);
}

void* allocateOrAbort(std::size_t size) {
    void* const memory = allocate(size);
    if (memory == nullptr) {
        std::abort();
    }
    return memory;
}

}  // namespace

void* operator new(std::size_t size) { return allocateOrAbort(size); }
void* operator new[](std::size_t size) { return allocateOrAbort(size); }
void* operator new(std::size_t size, const std::nothrow_t&) noexcept { return allocate(size); }
void* operator new[](std::size_t size, const std::nothrow_t&) noexcept { return allocate(size); }
void operator delete(void* memory) noexcept { std::free(memory); }
void operator delete[](void* memory) noexcept { std::free(memory); }
void operator delete(void* memory, std::size_t) noexcept { std::free(memory); }
void operator delete[](void* memory, std::size_t) noexcept { std::free(memory); }
void operator delete(void* memory, const std::nothrow_t&) noexcept { std::free(memory); }
void operator delete[](void* memory, const std::nothrow_t&) noexcept { std::free(memory); }

namespace borderkeys_test {

AllocationCount::AllocationCount() {
    allocations.store(0, std::memory_order_relaxed);
    counting.store(true, std::memory_order_relaxed);
}

AllocationCount::~AllocationCount() { counting.store(false, std::memory_order_relaxed); }

long AllocationCount::count() const { return allocations.load(std::memory_order_relaxed); }

}  // namespace borderkeys_test
