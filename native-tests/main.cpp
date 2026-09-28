// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <cstdio>

#include "test_support.hpp"

void runFormatTests();
void runEngineTests();
void runGestureTests();
void runTcnTests();
void runFoldTests();
void runAnswerCleanupTests();

int main() {
    runFormatTests();
    runEngineTests();
    runGestureTests();
    runTcnTests();
    runFoldTests();
    runAnswerCleanupTests();

    std::printf("\n%d checks, %d failures\n", borderkeys_test::checks, borderkeys_test::failures);
    return borderkeys_test::failures == 0 ? 0 : 1;
}
