# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

# Host tests for the native engine.
#
# A plain CMake project, not a Gradle module, and deliberately: these compile the *same* sources
# the device build compiles, for whatever machine is running them, and run in a second. Putting
# them behind an Android build would mean an emulator, a device, or a Robolectric shim between a
# change and the answer to "did that break the trie".
#
# No gtest and no dependencies. The assertions here are a macro and a counter; a test framework
# would be a larger thing to install than the code under test.

cmake_minimum_required(VERSION 3.22.1)
project(borderkeys_native_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../keyboard/src/main/cpp)
set(TOOLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../tools)

# Exceptions and RTTI off, exactly as the shipped library is built. A test binary compiled with
# a different language dialect than the code it tests is testing something else.
set(ENGINE_SOURCES
    ${ENGINE_DIR}/engine.cpp
    ${ENGINE_DIR}/packed_trie.cpp
    ${ENGINE_DIR}/ngram_model.cpp
    ${ENGINE_DIR}/user_model.cpp
    ${ENGINE_DIR}/proximity.cpp
    ${ENGINE_DIR}/gesture/resample.cpp
    ${ENGINE_DIR}/gesture/template_cache.cpp
    ${ENGINE_DIR}/gesture/shark2_decoder.cpp
)

add_library(borderkeys_engine STATIC ${ENGINE_SOURCES})
target_include_directories(borderkeys_engine PUBLIC ${ENGINE_DIR})
target_compile_options(borderkeys_engine PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

# The test pack is produced by the real writer rather than by a fixture built in C++.
#
# That is the point of it: the reader and the writer are two implementations of one format kept
# in agreement only by both having been written against bkd_format.hpp. A fixture would let them
# drift and still pass. This way a drift fails here, loudly, on every run.
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set(TEST_PACK ${CMAKE_CURRENT_BINARY_DIR}/test_pack.bkd)
add_custom_command(
    OUTPUT ${TEST_PACK}
    COMMAND ${Python3_EXECUTABLE} ${TOOLS_DIR}/build_dict.py --selftest --tag ro-RO
            --out ${TEST_PACK}
    DEPENDS ${TOOLS_DIR}/build_dict.py
    COMMENT "Building the test language pack with tools/build_dict.py"
)
add_custom_target(test_pack DEPENDS ${TEST_PACK})

add_executable(borderkeys_tests
    main.cpp
    test_format.cpp
    test_engine.cpp
    test_gesture.cpp
)
add_dependencies(borderkeys_tests test_pack)
target_link_libraries(borderkeys_tests borderkeys_engine)
target_compile_options(borderkeys_tests PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)
target_compile_definitions(borderkeys_tests PRIVATE
    BORDERKEYS_TEST_PACK="${TEST_PACK}"
    BORDERKEYS_TEST_DATA="${CMAKE_CURRENT_SOURCE_DIR}/data"
)

# The binary tools/gesture_replay.py drives. Separate from the test runner because its output is
# a measurement rather than a verdict, and the script owns the comparison against the baseline.
add_executable(gesture_replay gesture_replay.cpp)
target_link_libraries(gesture_replay borderkeys_engine)
target_compile_options(gesture_replay PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

enable_testing()
add_test(NAME engine COMMAND borderkeys_tests)

# --------------------------------------------------------------------------------------------
# Fuzzing the language-pack loader.
#
# Two targets, because they answer different questions and only one of them runs everywhere.
#
# `pack_corpus_test` is part of the ordinary test run. It replays the corrupt blobs committed
# under data/corpus and then mutates the valid pack deterministically, repairing both checksums
# after each mutation. The repair is the whole point: without it the CRC gate rejects better
# than 99% of mutations and the section-bounds and traversal checks -- the code doing pointer
# arithmetic over attacker-shaped data -- are never reached at all.
#
# `fuzz_pack` is a libFuzzer target for longer runs, and needs clang.
# --------------------------------------------------------------------------------------------
add_executable(pack_corpus_test pack_corpus_test.cpp)
add_dependencies(pack_corpus_test test_pack)
target_link_libraries(pack_corpus_test borderkeys_engine)
target_compile_options(pack_corpus_test PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)
target_compile_definitions(pack_corpus_test PRIVATE
    BORDERKEYS_TEST_PACK="${TEST_PACK}"
    BORDERKEYS_TEST_DATA="${CMAKE_CURRENT_SOURCE_DIR}/data"
)
add_test(NAME pack_corpus COMMAND pack_corpus_test)

option(BORDERKEYS_FUZZ "Build the libFuzzer target (requires clang)" OFF)
if(BORDERKEYS_FUZZ)
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        message(FATAL_ERROR "BORDERKEYS_FUZZ needs clang; -fsanitize=fuzzer is a clang feature")
    endif()
    add_executable(fuzz_pack fuzz_pack.cpp ${ENGINE_SOURCES})
    target_include_directories(fuzz_pack PRIVATE ${ENGINE_DIR})
    target_compile_options(fuzz_pack PRIVATE
        -fno-exceptions -fno-rtti -g -O1
        -fsanitize=fuzzer,address,undefined -fno-omit-frame-pointer
    )
    target_link_options(fuzz_pack PRIVATE -fsanitize=fuzzer,address,undefined)
endif()
