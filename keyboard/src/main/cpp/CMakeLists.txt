# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

cmake_minimum_required(VERSION 3.22.1)
project(borderkeys CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(BORDERKEYS_ASAN "Build with AddressSanitizer" OFF)

# ON only in the `plus` flavor's externalNativeBuild arguments (keyboard/build.gradle.kts); off,
# no tier B source is compiled.
option(BORDERKEYS_NEURAL_SWIPE "Build the trained (tier B) swipe decoder, plus flavor only" OFF)

# Sources are listed, not globbed.
set(BORDERKEYS_SOURCES
    jni_bridge.cpp
    engine.cpp
    packed_trie.cpp
    ngram_model.cpp
    user_model.cpp
    proximity.cpp
    gesture/resample.cpp
    gesture/template_cache.cpp
    gesture/shark2_decoder.cpp
)

if(BORDERKEYS_NEURAL_SWIPE)
    list(APPEND BORDERKEYS_SOURCES
        gesture/tcn_features.cpp
        gesture/tcn_weights.cpp
        gesture/tcn_encoder.cpp
        gesture/tcn_ctc_decoder.cpp
        gesture/tcn_decoder.cpp
    )
endif()

add_library(borderkeys SHARED ${BORDERKEYS_SOURCES})

if(BORDERKEYS_NEURAL_SWIPE)
    target_compile_definitions(borderkeys PRIVATE BORDERKEYS_NEURAL_SWIPE=1)
endif()

target_include_directories(borderkeys PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Exceptions and RTTI are off; errors cross every boundary as return codes.
target_compile_options(borderkeys PRIVATE
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -Wall
    -Wextra
    -Werror=return-type
)

target_compile_options(borderkeys PRIVATE
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Release>:-fomit-frame-pointer>
    $<$<CONFIG:Release>:-ffunction-sections>
    $<$<CONFIG:Release>:-fdata-sections>
    $<$<CONFIG:Debug>:-O0>
    $<$<CONFIG:Debug>:-g>
)

# --gc-sections drops every function nothing reaches; --exclude-libs,ALL keeps the static
# libc++'s symbols from being exported.
target_link_options(borderkeys PRIVATE
    $<$<CONFIG:Release>:-Wl,--gc-sections>
    -Wl,--exclude-libs,ALL
    # 16 KB page alignment.
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

if(BORDERKEYS_ASAN)
    # Never for a shipped build.
    target_compile_options(borderkeys PRIVATE -fsanitize=address -fno-omit-frame-pointer)
    target_link_options(borderkeys PRIVATE -fsanitize=address)
endif()

# `log` for __android_log_print in debug builds, `android` for AAssetManager; nothing else.
target_link_libraries(borderkeys log android)
