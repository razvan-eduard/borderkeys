# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

cmake_minimum_required(VERSION 3.22.1)
project(borderkeysassist CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------------------------
# llama.cpp, vendored as a submodule pinned to an exact tag and compiled from source: the
# library alone, with no network client, no OpenMP, and code for the target CPU rather than the
# build machine's.
# ---------------------------------------------------------------------------------------------

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama EXCLUDE_FROM_ALL)

add_library(borderkeysassist SHARED
    assist_jni.cpp
    text_assist.cpp
    answer_cleanup.cpp
)

target_include_directories(borderkeysassist PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/ggml/include
)

# Exceptions stay enabled here, unlike in :keyboard; llama.cpp uses them.
target_compile_options(borderkeysassist PRIVATE
    -fvisibility=hidden
    -Wall
    -Wextra
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Release>:-ffunction-sections>
    $<$<CONFIG:Release>:-fdata-sections>
)

target_link_options(borderkeysassist PRIVATE
    $<$<CONFIG:Release>:-Wl,--gc-sections>
    -Wl,--exclude-libs,ALL
    # Aligned for the 16 KB pages Android 15 devices use.
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_link_libraries(borderkeysassist llama ggml log)
