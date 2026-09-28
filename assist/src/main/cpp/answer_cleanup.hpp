// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_ANSWER_CLEANUP_HPP
#define BORDERKEYS_ANSWER_CLEANUP_HPP

#include <string>

namespace borderkeys {

/**
 * What applyChatTemplate wraps the text to transform in, and cleanResult strips from an answer
 * that echoed it back.
 */
inline constexpr const char* kTextLabel = "\n\nText:\n";
inline constexpr const char* kTextFence = "\"\"\"";

/**
 * Turns what a model generated for `input` into the answer its task asked for. `cleanFormatting`
 * is false for a custom instruction, which keeps any fences, quotes and labels the answer has.
 */
std::string cleanResult(const std::string& raw, const std::string& input, bool cleanFormatting);

}  // namespace borderkeys

#endif  // BORDERKEYS_ANSWER_CLEANUP_HPP
