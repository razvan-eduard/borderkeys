// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "answer_cleanup.hpp"

#include <cctype>
#include <cstddef>
#include <cstring>
#include <string>

namespace borderkeys {
namespace {

/** Below this many characters of input, cleanResult never falls back to the input. */
constexpr size_t kNarrationFallbackMinInputChars = 40;

/** A length-preserving task answered with more than this many times the input is a failure. */
constexpr size_t kNarrationFallbackLengthFactor = 4;

/** A tag pair a model may wrap part of its output in without being asked to. */
struct WrapTag {
    const char* open;
    const char* close;
};

/** Reasoning-block spellings: `<think>`, which Qwen3 and SmolLM3 emit, and two others. */
constexpr WrapTag kReasoningTags[] = {
    {"<think>", "</think>"},
    {"<thinking>", "</thinking>"},
    {"<reasoning>", "</reasoning>"},
};

constexpr const char* kWhitespace = " \t\n\r";

std::string trimmed(const std::string& text) {
    const size_t begin = text.find_first_not_of(kWhitespace);
    if (begin == std::string::npos) {
        return std::string();
    }
    const size_t end = text.find_last_not_of(kWhitespace);
    return text.substr(begin, end - begin + 1);
}

/**
 * Removes every closed instance of `tag` from `text`, tags included, and cuts from the first one
 * that never closes to the end.
 */
std::string stripTag(std::string text, const WrapTag& tag) {
    for (;;) {
        const size_t open = text.find(tag.open);
        if (open == std::string::npos) {
            return text;
        }
        const size_t closeTag = text.find(tag.close, open);
        if (closeTag == std::string::npos) {
            text.erase(open);
            return text;
        }
        text.erase(open, (closeTag + std::strlen(tag.close)) - open);
    }
}

/**
 * Peels one delimiter pair off `text`, only when it wraps the whole trimmed string. An answer
 * that is itself one quoted phrase is peeled too.
 */
std::string unwrapWhole(const std::string& text, const std::string& open,
                        const std::string& close) {
    if (text.size() < open.size() + close.size()) {
        return text;
    }
    if (text.compare(0, open.size(), open) != 0) {
        return text;
    }
    if (text.compare(text.size() - close.size(), close.size(), close) != 0) {
        return text;
    }
    return text.substr(open.size(), text.size() - open.size() - close.size());
}

/** The longest first line, or label before a colon, that counts as a label. */
constexpr size_t kMaxLabelChars = 40;

/** The most words a label at the start of the first line can have. */
constexpr int kMaxLabelWords = 4;

/**
 * Up to this many words, a label is removed when the answer after it opens with a capital or a
 * quotation mark.
 */
constexpr int kMaxLooseLabelWords = 2;

/** An ASCII letter, or any byte of a character outside ASCII. */
bool isLabelLetter(unsigned char c) {
    return std::isalpha(c) != 0 || c >= 0x80;
}

/** Where the text after a first line of at most kMaxLabelChars ending in a colon starts, or 0. */
size_t afterLabelLine(const std::string& text) {
    const size_t newline = text.find('\n');
    if (newline == std::string::npos || newline == 0) {
        return 0;
    }
    const std::string firstLine = trimmed(text.substr(0, newline));
    if (firstLine.empty() || firstLine.size() > kMaxLabelChars || firstLine.back() != ':') {
        return 0;
    }
    return trimmed(text.substr(newline + 1)).empty() ? 0 : newline + 1;
}

/**
 * The number of words before the colon when `text` opens with at most kMaxLabelWords words of
 * letters and then a colon, on the first line; 0 otherwise. `colon` is set to its offset.
 */
int labelWords(const std::string& text, size_t& colon) {
    int words = 0;
    bool inWord = false;
    size_t i = 0;
    while (i < text.size() && i <= kMaxLabelChars) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        if (isLabelLetter(c)) {
            words += inWord ? 0 : 1;
            inWord = true;
        } else if (c == ' ') {
            inWord = false;
        } else {
            break;
        }
        ++i;
    }
    if (i >= text.size() || text[i] != ':' || words > kMaxLabelWords) {
        return 0;
    }
    colon = i;
    return words;
}

/** Whether `text` is wrapped whole in one pair of straight double or single quotes. */
bool isWhollyQuoted(const std::string& text) {
    return text.size() >= 2 && (text.front() == '"' || text.front() == '\'') &&
           text.back() == text.front();
}

/** Whether `text` opens with a label: a label line, or a label at the start of the first line. */
bool opensWithLabel(const std::string& text) {
    size_t colon = 0;
    return afterLabelLine(text) != 0 || labelWords(text, colon) != 0;
}

/**
 * Removes a label a model puts before the answer: a first line of at most kMaxLabelChars ending
 * in a colon ("Traducere:"), or at most kMaxLabelWords words of letters and a colon at the start
 * of the first line ("Respuesta: ...", "Réponse : ...", "La respuesta es: \"...\""). After one
 * or two words the answer has to open with a capital or a quotation mark, and after three or
 * four it has to be wholly quoted. Nothing is removed when `input` opens with a label of its own.
 */
std::string stripLeadingLabel(const std::string& text, const std::string& input) {
    if (opensWithLabel(trimmed(input))) {
        return text;
    }
    const size_t afterLine = afterLabelLine(text);
    if (afterLine != 0) {
        return trimmed(text.substr(afterLine));
    }
    size_t colon = 0;
    const int words = labelWords(text, colon);
    if (words == 0) {
        return text;
    }
    const size_t lineEnd = text.find('\n');
    const std::string restOfLine = trimmed(text.substr(colon + 1, lineEnd == std::string::npos
                                                                      ? std::string::npos
                                                                      : lineEnd - colon - 1));
    if (restOfLine.empty()) {
        return text;
    }
    const std::string rest = trimmed(text.substr(colon + 1));
    const unsigned char first = static_cast<unsigned char>(rest.front());
    const bool opensLikeAnAnswer =
        std::isupper(first) != 0 || first == '"' || first == '\'' || first >= 0x80;
    if (words <= kMaxLooseLabelWords ? !opensLikeAnAnswer : !isWhollyQuoted(rest)) {
        return text;
    }
    return rest;
}

std::string toLower(std::string s) {
    for (char& c : s) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

/**
 * Phrases a model only writes when it is describing the task rather than doing it: "The rewritten
 * sentence is:", "Here is the corrected text:", "The original sentence was:". Lowercased, matched
 * as substrings.
 */
const char* const kNarrationMarkers[] = {
    "the rewritten", "the corrected", "the revised", "the translated",
    "the shortened", "the summarised", "the summarized", "the original",
    "here is the", "here's the", "the following is", "the text is rewritten",
    "rewritten sentence", "rewritten text", "corrected sentence", "corrected text",
    "the translation is", "the paragraph",
};

bool looksLikeNarration(const std::string& lower) {
    for (const char* marker : kNarrationMarkers) {
        if (lower.find(marker) != std::string::npos) {
            return true;
        }
    }
    return false;
}

/**
 * When a model has narrated instead of answering, recovers the answer it buried: the last thing
 * put in quotes ("...is: \"<answer>\""), or everything after the last narration line. Returns the
 * text unchanged when neither is found.
 */
std::string salvageFromNarration(const std::string& text) {
    const std::string lower = toLower(text);
    if (!looksLikeNarration(lower)) {
        return text;
    }
    // The last "..." whose closing quote sits at the end, past only trailing spaces and a stop.
    size_t end = text.size();
    while (end > 0 && (std::isspace(static_cast<unsigned char>(text[end - 1])) ||
                       text[end - 1] == '.')) {
        --end;
    }
    if (end >= 2 && text[end - 1] == '"') {
        const size_t open = text.rfind('"', end - 2);
        if (open != std::string::npos && open + 1 < end - 1) {
            const std::string inner = trimmed(text.substr(open + 1, (end - 1) - (open + 1)));
            if (!inner.empty()) {
                return inner;
            }
        }
    }
    // Everything after the last newline that follows a narration marker.
    size_t afterMarkerLine = std::string::npos;
    for (const char* marker : kNarrationMarkers) {
        size_t at = lower.find(marker);
        while (at != std::string::npos) {
            const size_t nl = text.find('\n', at);
            if (nl != std::string::npos &&
                (afterMarkerLine == std::string::npos || nl > afterMarkerLine)) {
                afterMarkerLine = nl;
            }
            at = lower.find(marker, at + 1);
        }
    }
    if (afterMarkerLine != std::string::npos) {
        const std::string rest = trimmed(text.substr(afterMarkerLine + 1));
        if (!rest.empty() && !looksLikeNarration(toLower(rest))) {
            return rest;
        }
    }
    return text;
}

}  // namespace

/**
 * Turns whatever a small instruction-tuned model generated into the answer a task asked for.
 * Reasoning blocks are always removed; every later step runs only with `cleanFormatting`.
 */
std::string cleanResult(const std::string& raw, const std::string& input, bool cleanFormatting) {
    std::string text = trimmed(raw);
    for (const WrapTag& tag : kReasoningTags) {
        text = stripTag(std::move(text), tag);
    }
    text = trimmed(text);
    if (!cleanFormatting) {
        return text;
    }
    // An answer buried in narration, before the label and fence stripping below.
    text = trimmed(salvageFromNarration(text));
    // A leading label, before the fence and quote stripping below.
    text = stripLeadingLabel(text, input);
    // A code fence around the whole answer.
    text = unwrapWhole(text, "```\n", "\n```");
    text = unwrapWhole(text, "```", "```");
    text = trimmed(text);
    // The input's own fence echoed around the answer, before the single-quote unwrap below.
    text = unwrapWhole(text, std::string(kTextFence) + "\n", std::string("\n") + kTextFence);
    text = unwrapWhole(text, kTextFence, kTextFence);
    text = trimmed(text);
    // Quote marks around the whole answer.
    text = unwrapWhole(text, "\"", "\"");
    text = unwrapWhole(text, "'", "'");
    text = trimmed(text);

    // Last resort: the input itself, when narration survived or the answer is several times the
    // input's length. Not for a very short input.
    if (input.size() >= kNarrationFallbackMinInputChars &&
        (looksLikeNarration(toLower(text)) ||
         text.size() > input.size() * kNarrationFallbackLengthFactor)) {
        return input;
    }
    return text;
}

}  // namespace borderkeys
