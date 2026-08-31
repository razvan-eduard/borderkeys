<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Patches against the vendored llama.cpp

`assist/src/main/cpp/third_party/llama.cpp` is a git submodule pinned to an exact upstream tag.
Any change we need to make to it lives here as a patch file, not as an edit inside the
submodule.

The reason is mechanical rather than stylistic: an edit made inside a submodule working tree is
invisible to this repository's history and is **silently discarded** the next time anyone runs
`git submodule update`. It will look like the change was never made, on someone else's machine,
weeks later.

So: change it upstream if the change belongs upstream. Otherwise write the patch here, name it
for what it does, and apply it from the build.

There are no patches today. llama.cpp builds for both Android ABIs with the CMake version this
project pins, and the `llama` target does not pull in `cpp-httplib` — which was the one thing
worth checking before vendoring an inference runtime into a keyboard with no network permission.
