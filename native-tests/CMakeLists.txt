# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

# Host tests for the native engine: the same sources the device build compiles, built for the
# machine running them. No test framework and no dependencies.

cmake_minimum_required(VERSION 3.22.1)
project(borderkeys_native_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../keyboard/src/main/cpp)
set(TOOLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../tools)
set(ASSIST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../assist/src/main/cpp)

# Exceptions and RTTI off, exactly as the shipped library is built. The neural swipe sources are
# listed unconditionally, unlike keyboard/src/main/cpp/CMakeLists.txt's
# BORDERKEYS_NEURAL_SWIPE-gated list.
set(ENGINE_SOURCES
    ${ENGINE_DIR}/engine.cpp
    ${ENGINE_DIR}/packed_trie.cpp
    ${ENGINE_DIR}/ngram_model.cpp
    ${ENGINE_DIR}/user_model.cpp
    ${ENGINE_DIR}/proximity.cpp
    ${ENGINE_DIR}/touch_model.cpp
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

# Scoring constants a sweep may set; empty keeps the engine's own values.
set(BORDERKEYS_EDIT_PENALTY "" CACHE STRING "kEditPenalty for this build")
set(BORDERKEYS_CORRECTION_SURCHARGE "" CACHE STRING "kCorrectionSurcharge for this build")
set(BORDERKEYS_SLIP_SCALE "" CACHE STRING "kSlipScale for this build")
set(BORDERKEYS_RUN_ON_COST "" CACHE STRING "kRunOnCost for this build")
set(BORDERKEYS_DELETE_COST "" CACHE STRING "kDeleteCost for this build")
set(BORDERKEYS_TRANSPOSE_COST "" CACHE STRING "kTransposeCost for this build")
set(BORDERKEYS_CEILING_3 "" CACHE STRING "maxEditCostFor(3) for this build")
set(BORDERKEYS_CEILING_4 "" CACHE STRING "maxEditCostFor(4) for this build")
set(BORDERKEYS_CEILING_5 "" CACHE STRING "maxEditCostFor(5..7) for this build")
set(BORDERKEYS_CEILING_8 "" CACHE STRING "maxEditCostFor(8+) for this build")
set(BORDERKEYS_DEPTH_CAP_4 "" CACHE STRING "edit cost allowed before the fourth typed letter")
set(BORDERKEYS_DEPTH_CAP_7 "" CACHE STRING "edit cost allowed before the seventh typed letter")
function(apply_sweep_definitions target)
    foreach(name DELETE_COST TRANSPOSE_COST CEILING_3 CEILING_4 CEILING_5 CEILING_8 DEPTH_CAP_4
                 DEPTH_CAP_7)
        if(BORDERKEYS_${name})
            target_compile_definitions(${target} PUBLIC BORDERKEYS_${name}=${BORDERKEYS_${name}})
        endif()
    endforeach()
    if(BORDERKEYS_SLIP_SCALE)
        target_compile_definitions(${target} PUBLIC BORDERKEYS_SLIP_SCALE=${BORDERKEYS_SLIP_SCALE})
    endif()
    if(BORDERKEYS_RUN_ON_COST)
        target_compile_definitions(${target} PUBLIC BORDERKEYS_RUN_ON_COST=${BORDERKEYS_RUN_ON_COST})
    endif()
    if(BORDERKEYS_EDIT_PENALTY)
        target_compile_definitions(${target} PUBLIC BORDERKEYS_EDIT_PENALTY=${BORDERKEYS_EDIT_PENALTY})
    endif()
    if(BORDERKEYS_CORRECTION_SURCHARGE)
        target_compile_definitions(${target} PUBLIC
            BORDERKEYS_CORRECTION_SURCHARGE=${BORDERKEYS_CORRECTION_SURCHARGE})
    endif()
endfunction()
apply_sweep_definitions(borderkeys_engine)

# The test pack, produced by the real writer, tools/build_dict.py.
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

# The same script's fold table, which test_fold.cpp diffs against foldCodePoint() in
# proximity.cpp.
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
    test_answer_cleanup.cpp
    ${ASSIST_DIR}/answer_cleanup.cpp
)
add_dependencies(borderkeys_tests test_pack test_folds)
target_include_directories(borderkeys_tests PRIVATE ${ASSIST_DIR})
target_link_libraries(borderkeys_tests borderkeys_engine)
target_compile_options(borderkeys_tests PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)
target_compile_definitions(borderkeys_tests PRIVATE
    BORDERKEYS_TEST_PACK="${TEST_PACK}"
    BORDERKEYS_TEST_FOLDS="${TEST_FOLDS}"
    BORDERKEYS_TEST_DATA="${CMAKE_CURRENT_SOURCE_DIR}/data"
    # The weights the `plus` flavor ships, written by tools/swipe_model/export_weights.py.
    BORDERKEYS_SWIPE_MODEL="${CMAKE_CURRENT_SOURCE_DIR}/../keyboard/src/plus/assets/model.bkw"
)

# The binary tools/gesture_replay.py drives; its output is a measurement, not a verdict.
add_executable(gesture_replay gesture_replay.cpp)
target_link_libraries(gesture_replay borderkeys_engine)
target_compile_options(gesture_replay PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

# The binary tools/tcn_replay.py drives: gesture_replay's formats, with a TcnDecoder.
add_executable(tcn_replay tcn_replay.cpp)
target_link_libraries(tcn_replay borderkeys_engine)
target_compile_options(tcn_replay PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

# The same for typed words: a corpus in, a measurement out. It takes a directory of real packs.
add_executable(suggest_eval suggest_eval.cpp)
target_link_libraries(suggest_eval borderkeys_engine)
target_compile_options(suggest_eval PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

# The touch model measured on a tap corpus from tools/make_tap_corpus.py; a measurement, not a
# verdict.
add_executable(touch_eval touch_eval.cpp)
target_link_libraries(touch_eval borderkeys_engine)
target_compile_options(touch_eval PRIVATE -fno-exceptions -fno-rtti -Wall -Wextra)

enable_testing()
add_test(NAME engine COMMAND borderkeys_tests)

# --------------------------------------------------------------------------------------------
# Fuzzing the language-pack loader.
#
# `pack_corpus_test` is part of the ordinary test run. It replays the corrupt blobs committed
# under data/corpus and then mutates the valid pack deterministically, repairing both checksums
# after each mutation.
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
# named what NativePredictor's `System.loadLibrary("borderkeys")` asks for, for JVM tests run with
# -Djava.library.path pointing here. Optional: without JNI headers every other target still
# builds.
#
# The headers are found under JAVA_HOME, not through find_package(JNI), which on macOS reports
# the stub framework.
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
    apply_sweep_definitions(borderkeys)
    # No -fno-exceptions here: the JDK's desktop jni.h is not built for it.
    target_compile_options(borderkeys PRIVATE -Wall -Wextra)
else()
    message(STATUS "JNI headers not found -- the host bridge will not be built")
endif()
