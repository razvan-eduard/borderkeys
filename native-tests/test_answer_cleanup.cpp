// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <cstdio>
#include <string>

#include "answer_cleanup.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

namespace {

/** Checks that cleanResult turns `raw`, answered for `input`, into exactly `expected`. */
void expectClean(const char* raw, const char* input, bool cleanFormatting, const char* expected,
                 const char* what) {
    const std::string actual = cleanResult(raw, input, cleanFormatting);
    check(actual == expected, what);
    if (actual != expected) {
        std::printf("       got [%s]\n       expected [%s]\n", actual.c_str(), expected);
    }
}

constexpr const char* kMeeting = "The meeting starts at ten. Please bring the slides.";

}  // namespace

void runAnswerCleanupTests() {
    section("answer cleanup: a label before the answer");
    expectClean("Respuesta: La reunión comienza a las diez. Por favor, traiga las diapositivas.",
                kMeeting, true,
                "La reunión comienza a las diez. Por favor, traiga las diapositivas.",
                "a one-word label at the start of the answer is removed");
    expectClean("Réponse : La réunion commence à dix heures.", kMeeting, true,
                "La réunion commence à dix heures.",
                "a label with a space before its colon is removed");
    expectClean("Traducción: \"Trae las diapositivas y el café.\"", kMeeting, true,
                "Trae las diapositivas y el café.",
                "a label before a quoted answer goes, and so do the quotes");
    expectClean("La respuesta es: \"La reunión empieza a las diez.\"", kMeeting, true,
                "La reunión empieza a las diez.",
                "a three-word label before a wholly quoted answer is removed");
    expectClean("Traducere:\nÎntâlnirea începe la zece.", kMeeting, true,
                "Întâlnirea începe la zece.", "a label on a line of its own is removed");

    section("answer cleanup: a colon that belongs to the text");
    expectClean("Notas: Trae las diapositivas.", "Notes: Bring the slides.", true,
                "Notas: Trae las diapositivas.",
                "a label the input opens with stays, translated");
    expectClean("Notas:\nTrae las diapositivas.", "Notes:\nBring the slides.", true,
                "Notas:\nTrae las diapositivas.",
                "a label line the input opens with stays, translated");
    expectClean("La regla es simple: nunca compartas contraseñas.",
                "The rule is simple, never share passwords.", true,
                "La regla es simple: nunca compartas contraseñas.",
                "four words and a colon before an unquoted answer stay");
    expectClean("Cuidado: esto importa.", "Careful, this matters.", true,
                "Cuidado: esto importa.",
                "a short word and a colon before a lower-case continuation stay");
    expectClean("Empieza a las 10:30.", "It starts at half past ten.", true,
                "Empieza a las 10:30.", "a time stays");
    expectClean("Respuesta: Hola.", "Say hello in Spanish, starting with Respuesta:", false,
                "Respuesta: Hola.", "a custom instruction's answer keeps its label");

    section("answer cleanup: what it already removed");
    expectClean("<think>plan</think>\"Hola\"", "Hello", true, "Hola",
                "a reasoning block and the quotes around the answer go");
    expectClean("```\nHola\n```", "Hello", true, "Hola", "a code fence around the answer goes");
}
