# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

cmake_minimum_required(VERSION 3.22.1)
project(borderkeys CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(BORDERKEYS_ASAN "Build with AddressSanitizer" OFF)

# Off by default -- set ON only by the `plus` flavor's own externalNativeBuild.cmake.arguments
# (keyboard/build.gradle.kts). This is what makes tier B a real per-flavor difference rather than
# a runtime branch on an identical binary: `core` never lists tcn_*.cpp as a source at all, so no
# amount of reading app-core-release.apk's .so can find a trace of it. See docs/licensing.md
# section 2.5 and tools/swipe_model/ for where the weights this tier loads come from.
option(BORDERKEYS_NEURAL_SWIPE "Build the trained (tier B) swipe decoder, plus flavor only" OFF)

# Sources are listed, not globbed. file(GLOB) resolves at configure time, so a new file appears
# in the build only after something else forces a reconfigure -- which means it compiles on the
# machine that added it and silently does not on CI.
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

# Exceptions and RTTI are off. Errors cross every boundary in this library as return codes, and
# the JNI bridge is written to that contract; enabling them would add unwind tables to a library
# whose whole point is to be small and to have no path that allocates while failing.
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

# --gc-sections drops every function nothing reaches, which after RegisterNatives is most of
# what a naive build would keep. --exclude-libs,ALL stops symbols from the static libc++ being
# re-exported from our .so, where they could satisfy another library's link at runtime and bind
# it to our copy of the STL.
target_link_options(borderkeys PRIVATE
    $<$<CONFIG:Release>:-Wl,--gc-sections>
    -Wl,--exclude-libs,ALL
    # 16 KB page alignment.
    #
    # Android 15 ships devices whose page size is 16384 bytes, and a shared library whose LOAD
    # segments are aligned to the old 4096 simply does not load there: dlopen fails and the
    # keyboard comes up with no prediction engine at all. This was found by installing on such a
    # device and reading the segment alignment out of the ELF -- every third-party .so in the
    # APK already had 0x4000 and ours had 0x1000, which is invisible in any build log.
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

if(BORDERKEYS_ASAN)
    # Off by default and never enabled for a shipped build: ASan replaces the allocator, which
    # is the opposite of what an engine designed around not calling the allocator wants measured.
    target_compile_options(borderkeys PRIVATE -fsanitize=address -fno-omit-frame-pointer)
    target_link_options(borderkeys PRIVATE -fsanitize=address)
endif()

# Only two. `log` for __android_log_print in debug builds, `android` for AAssetManager when a
# bundled pack is opened by name rather than by descriptor. Nothing else is linked, and the
# acceptance criteria say so out loud because it is checkable with readelf.
target_link_libraries(borderkeys log android)
