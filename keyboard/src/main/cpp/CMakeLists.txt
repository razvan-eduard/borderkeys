# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

cmake_minimum_required(VERSION 3.22.1)
project(borderkeys CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Set by the `plus` product flavor only. The neural swipe decoder is compiled out of the `core`
# variant, not eliminated from it: the preprocessor never sees the code, so no amount of reading
# the shipped .so can find a trace of it.
option(BORDERKEYS_ASAN "Build with AddressSanitizer" OFF)

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

add_library(borderkeys SHARED ${BORDERKEYS_SOURCES})

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
