// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "answer_cleanup.hpp"

#include <cctype>
#include <cstddef>
#include <cstring>
#include <string>

namespace borderkeys {
namespace {

/** Below this many characters of input, cleanResult's "the model failed, hand the text back"
 *  fallback is not applied: a legitimate rewrite of a few words can be several times their
 *  length without anything being wrong. */
constexpr size_t kNarrationFallbackMinInputChars = 40;

/** A length-preserving task answered with more than this many times the input is a failure. */
constexpr size_t kNarrationFallbackLengthFactor = 4;

/**
 * A tag pair a model may wrap part of its output in without being asked to.
 *
 * A plain open/close string pair rather than anything smarter -- these are not XML and nothing
 * here needs to parse them as a document, only find and remove one kind of thing a small model
 * does that a task instruction did not ask for.
 */
struct WrapTag {
    const char* open;
    const char* close;
};

/**
 * Every reasoning-block spelling this application has reason to expect. `<think>` is what Qwen3
 * and SmolLM3 -- the reasoning-tuned families in KnownAssistModels.kt; EuroLLM, the third, has
 * no thinking phase -- actually emit; the other two are the same idea under the names used
 * elsewhere in the wider GGUF ecosystem. Kept as a list a model addition might extend, rather
 * than one pair hardcoded to today's families.
 */
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
 * Removes every closed instance of `tag` from `text`, and truncates at the first one that never
 * closes.
 *
 * A closed block is cut out whole, tags included -- the text before and after it is what the
 * model meant as its answer. An opened-but-never-closed block (generation stopped, by the output
 * budget or by cancellation, before the model finished) is cut from the opening tag to the end:
 * there is no answer inside an unfinished thought, and a fragment of one is worse to show than
 * nothing.
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
 * Peels one delimiter pair off `text`, but only when it wraps the *whole* trimmed string.
 *
 * A translation that happens to start and end with a quotation mark as part of its own content
 * is legitimate, ordinary text -- only the whole-string case, the entire answer quoted or
 * fenced as if it were being handed over rather than written, is presentation formatting nobody
 * asked for, and the whole-string check is what tells the two apart in the common case: a
 * sentence that legitimately opens with a quote almost never also happens to close the string
 * with one. It is not a perfect test -- a real answer that is itself one short quoted phrase,
 * start to end, looks identical to the model's own wrapping and gets peeled the same way -- but
 * a model reflexively quoting a plain translation nobody asked to have quoted is the case this
 * was actually seen doing, and the rarer one it trades away is a smaller cost than that.
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

/** Up to this many words, a label is removed when the answer after it opens with a capital or a quotation mark. */
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
 * as substrings. A built-in task's real answer is the transformed text and nothing else, so any
 * of these appearing in one is the model narrating.
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
 * When a model has narrated instead of answering, recovers the answer it buried.
 *
 * Two shapes, both seen: the answer as the last thing put in quotes ("...is: \"<answer>\""), and
 * the answer as everything after the last narration line. Neither is guaranteed -- a model that
 * narrates is already off the rails -- so this only ever returns something cleaner than it was
 * handed, never something worse: if it cannot find a plausible answer it returns the text
 * unchanged and cleanResult's own fallback decides what to do with it.
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
 * Turns whatever a small instruction-tuned model actually generated into the answer a task
 * asked for.
 *
 * None of this is guaranteed by the prompt -- kNoThink is asked for up front, and even that is a
 * request, not a contract -- it is what stays true after asking nicely. Each cleanup here is
 * independent and narrow rather than one pattern tuned to today's two model families, because a
 * future model added to KnownAssistModels.kt is not obliged to behave like the ones this list
 * was written against.
 *
 * `cleanFormatting` splits these into two different kinds of claim. Reasoning-tag stripping is
 * unconditional: nothing a task or a custom instruction legitimately asks for looks like a leaked
 * `<think>` block, so there is nothing it could be disagreeing with. The fence and quote
 * unwrapping are the opposite -- both are guesses about what the *task* asked for, and a task
 * built into this application never asks for either, but AssistTask.CUSTOM carries whatever the
 * user actually typed, and "wrap the answer in quotes" is a perfectly reasonable thing to type.
 * Peeling quotes off a result that were requested on purpose is not a smaller version of the bug
 * this was added for -- it is the opposite of it -- so the caller passes false for a custom
 * instruction and this leaves that half alone.
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
    // Before the label and fence stripping below: a model that has narrated the task rather than
    // done it ("The rewritten sentence is: \"...\"") has usually buried the answer inside quotes
    // or after the narration, and recovering that is what leaves a plain answer for the rest of
    // this to tidy.
    text = trimmed(salvageFromNarration(text));
    // Ahead of the fence and quote stripping below: a labelled preamble is often followed by
    // the answer wrapped in one of those too ("Traducere:\n\"...\"", seen verbatim), and the
    // label has to come off first for what is left to be the plain wrapped answer those steps
    // already know how to handle.
    text = stripLeadingLabel(text, input);
    // A plain-text answer to "translate this" or "correct this" is never legitimately fenced --
    // there is no task here whose real answer starts and ends with three backticks -- so this
    // one is removed unconditionally.
    text = unwrapWhole(text, "```\n", "\n```");
    text = unwrapWhole(text, "```", "```");
    text = trimmed(text);
    // The same fence applyChatTemplate now wraps the input text in, echoed back around the
    // answer -- a small model mirroring the shape of what it just read is exactly the kind of
    // thing this whole function exists to undo. Its own pass, ahead of the single-quote unwrap
    // below: that one only peels a single quote off each end, which would leave a triple-quote
    // echo as "" rather than gone.
    text = unwrapWhole(text, std::string(kTextFence) + "\n", std::string("\n") + kTextFence);
    text = unwrapWhole(text, kTextFence, kTextFence);
    text = trimmed(text);
    // Seen doing this on a plain translation with nothing quoted in the source at all -- Translate
    // wrapped in quote marks reads as "here is the translation," presentation the task never
    // asked for. See unwrapWhole's own doc for the one case this can be wrong about.
    text = unwrapWhole(text, "\"", "\"");
    text = unwrapWhole(text, "'", "'");
    text = trimmed(text);

    // Last resort. If narration survived every attempt above to lift the answer out of it, or
    // the model answered a length-preserving task with something several times longer than it
    // was given, the model has failed -- and the text the user selected, handed straight back,
    // is a better outcome than the model's monologue in place of it. Skipped for a very short
    // input, where "several times longer" is a handful of words and means nothing.
    if (input.size() >= kNarrationFallbackMinInputChars &&
        (looksLikeNarration(toLower(text)) ||
         text.size() > input.size() * kNarrationFallbackLengthFactor)) {
        return input;
    }
    return text;
}

}  // namespace borderkeys
