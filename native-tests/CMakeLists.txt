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
#
# Tier B's sources are listed here UNCONDITIONALLY, unlike keyboard/src/main/cpp/CMakeLists.txt's
# own BORDERKEYS_NEURAL_SWIPE-gated list: the device build's `core` flavor genuinely must not
# carry this code, but a host test binary has no such constraint, and every line here should be
# covered by `borderkeys_tests` regardless of what any Android flavor ships.
set(ENGINE_SOURCES
    ${ENGINE_DIR}/engine.cpp
    ${ENGINE_DIR}/packed_trie.cpp
    ${ENGINE_DIR}/ngram_model.cpp
    ${ENGINE_DIR}/user_model.cpp
    ${ENGINE_DIR}/proximity.cpp
    ${ENGINE_DIR}/gesture/resample.cpp
    ${ENGINE_DIR}/gesture/template_cache.cpp
    ${ENGINE_DIR}/gesture/shark2_decoder.cpp
    ${ENGINE_DIR}/gesture/tcn_features.cpp
    ${ENGINE_DIR}/gesture/tcn_weights.cpp
    ${ENGINE_DIR}/gesture/tcn_encoder.cpp
    ${ENGINE_DIR}/gesture/tcn_ctc_decoder.cpp
    ${ENGINE_DIR}/gesture/tcn_decoder.cpp
)

add_library(borderkeys_engine STATIC ${ENGINE_SOURCES})
target_include_directories(borderkeys_engine PUBLIC ${ENGINE_DIR})
target_compile_options(borderkeys_engine PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)
target_compile_definitions(borderkeys_engine PUBLIC BORDERKEYS_NEURAL_SWIPE=1)

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

# The same script's fold table, for the same reason: foldCodePoint() in proximity.cpp and
# fold_code_point() in build_dict.py index the same trie, and a code point they disagree on is a
# word the engine can never reach. test_fold.cpp diffs the two.
set(TEST_FOLDS ${CMAKE_CURRENT_BINARY_DIR}/folds.tsv)
add_custom_command(
    OUTPUT ${TEST_FOLDS}
    COMMAND ${Python3_EXECUTABLE} ${TOOLS_DIR}/build_dict.py --dump-folds --out ${TEST_FOLDS}
    DEPENDS ${TOOLS_DIR}/build_dict.py
    COMMENT "Dumping tools/build_dict.py's fold table for the native tests"
)
add_custom_target(test_folds DEPENDS ${TEST_FOLDS})

add_executable(borderkeys_tests
    main.cpp
    test_format.cpp
    test_engine.cpp
    test_gesture.cpp
    test_tcn.cpp
    test_fold.cpp
)
add_dependencies(borderkeys_tests test_pack test_folds)
target_link_libraries(borderkeys_tests borderkeys_engine)
target_compile_options(borderkeys_tests PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)
target_compile_definitions(borderkeys_tests PRIVATE
    BORDERKEYS_TEST_PACK="${TEST_PACK}"
    BORDERKEYS_TEST_FOLDS="${TEST_FOLDS}"
    BORDERKEYS_TEST_DATA="${CMAKE_CURRENT_SOURCE_DIR}/data"
    # The weights the `plus` flavor ships. Written by tools/swipe_model/export_weights.py and
    # read by TcnWeights::loadFromBytes -- two implementations of one format, so the test that
    # loads it is what proves they still agree.
    BORDERKEYS_SWIPE_MODEL="${CMAKE_CURRENT_SOURCE_DIR}/../keyboard/src/plus/assets/model.bkw"
)

# The binary tools/gesture_replay.py drives. Separate from the test runner because its output is
# a measurement rather than a verdict, and the script owns the comparison against the baseline.
add_executable(gesture_replay gesture_replay.cpp)
target_link_libraries(gesture_replay borderkeys_engine)
target_compile_options(gesture_replay PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

# tools/tcn_replay.py's sibling: same corpus/layout formats and the same word<TAB>rank contract
# as gesture_replay, but drives a TcnDecoder built directly rather than through Engine::create()
# (which only ever builds Shark2Decoder -- see tcn_replay.cpp's own comment on why that routing
# decision is untouched here). Built unconditionally, like the rest of Tier B's sources above.
add_executable(tcn_replay tcn_replay.cpp)
target_link_libraries(tcn_replay borderkeys_engine)
target_compile_options(tcn_replay PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

# The same idea as the two above, for typed words rather than gestures: a corpus in, a
# measurement out, no verdict. It exists because the scoring constants in engine.cpp pull against
# each other -- the edit penalty that stops a frequent word replacing a rare real one is the same
# one that buries "the" under "tehachapi" -- and no single example prices both sides. Needs real
# packs, so it takes a directory rather than the small test pack the suites use.
add_executable(suggest_eval suggest_eval.cpp)
target_link_libraries(suggest_eval borderkeys_engine)
target_compile_options(suggest_eval PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

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

# The engine as the JVM sees it: the same sources plus the JNI bridge, built as a shared library
# named exactly what NativePredictor's `System.loadLibrary("borderkeys")` asks for, so a JVM test
# with -Djava.library.path pointing here loads the real thing with no source change anywhere.
#
# This is what lets the *whole* correction path be measured off a device. `suggest_eval` reaches
# the engine and stops there, so everything AutoCorrection decides -- the known-word guard, the
# edit ceiling, the proper-noun rule, the situations -- was either untested or hand-modelled in
# C++, which is a second implementation that can drift from the one that ships. Here the Kotlin
# that ships is the Kotlin that runs.
#
# Optional: a machine with no JNI headers still builds and runs every other target.
#
# JAVA_HOME rather than find_package(JNI), which on macOS reports the stub framework and misses
# a real JDK's headers even when JAVA_HOME points straight at one.
if(NOT DEFINED ENV{JAVA_HOME})
    execute_process(COMMAND /usr/libexec/java_home OUTPUT_VARIABLE JAVA_HOME_GUESS
                    OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
else()
    set(JAVA_HOME_GUESS $ENV{JAVA_HOME})
endif()
if(EXISTS "${JAVA_HOME_GUESS}/include/jni.h")
    set(JNI_FOUND TRUE)
    set(JNI_DIRS "${JAVA_HOME_GUESS}/include")
    foreach(platform darwin linux win32)
        if(IS_DIRECTORY "${JAVA_HOME_GUESS}/include/${platform}")
            list(APPEND JNI_DIRS "${JAVA_HOME_GUESS}/include/${platform}")
        endif()
    endforeach()
endif()
if(JNI_FOUND)
    add_library(borderkeys SHARED ${ENGINE_SOURCES} ${ENGINE_DIR}/jni_bridge.cpp)
    target_include_directories(borderkeys PUBLIC ${ENGINE_DIR} ${JNI_DIRS})
    target_compile_definitions(borderkeys PUBLIC BORDERKEYS_NEURAL_SWIPE=1)
    # No -fno-exceptions here: the bridge is compiled against the JDK's jni.h rather than the
    # NDK's, and the desktop headers are not built for it.
    target_compile_options(borderkeys PRIVATE -Wall -Wextra)
else()
    message(STATUS "JNI headers not found -- the host bridge will not be built")
endif()
