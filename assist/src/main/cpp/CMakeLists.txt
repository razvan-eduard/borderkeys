# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

cmake_minimum_required(VERSION 3.22.1)
project(borderkeysassist CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------------------------
# llama.cpp, vendored as a submodule pinned to an exact tag and compiled from source.
#
# Not an AAR, and not a downloaded artefact: this project builds everything it ships. The
# submodule records a commit, so the binary is reproducible from the source tree alone -- which
# is what GPL section 6 and F-Droid both actually require.
#
# The option list below is the interesting part. Everything that is off is off for a reason:
#
#   * TESTS / TOOLS / EXAMPLES / SERVER -- none of them are linked, and the server target links
#     `cpp-httplib`. An HTTP client compiled into the process that sees every keystroke would be
#     a contradiction of the project's central claim even if nothing ever called it.
#   * CURL -- same reason, more directly.
#   * NATIVE -- the build machine's CPU is not the phone's. Left on, ggml would probe the host
#     and emit instructions the target may not have.
#   * OPENMP -- one extra shared library and a thread pool, for a runtime that is already told
#     exactly how many threads to use.
#
# Verified before vendoring: this configures and builds for arm64-v8a and armeabi-v7a with
# CMake 3.22.1, the version this project pins, and `cpp-httplib` compiles no objects.
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
)

target_include_directories(borderkeysassist PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/ggml/include
)

# Exceptions stay enabled here, unlike in :keyboard. llama.cpp uses them internally, and
# compiling a dependency with a different exception model than the code linking it is how you
# get a std::terminate at an unwind boundary instead of an error.
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
    # Android 15 devices use 16 KB pages, and a library aligned to the old 4096 does not load
    # there at all. Found the hard way on libborderkeys.so.
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_link_libraries(borderkeysassist llama ggml log)
