// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "text_assist.hpp"


#include <algorithm>
#include <android/log.h>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <llama.h>
#include <vector>

namespace borderkeys {
namespace {

constexpr const char* kTag = "BorderKeysAssist";

/** Hard ceiling on the input, checked before tokenising rather than after. */
constexpr int kMaxInputChars = 8000;
constexpr int kMinContextTokens = 512;
constexpr int kMaxContextTokens = 8192;

/**
 * Left unclaimed when `run`'s `useRemainingContext` raises the output cap to the real space left
 * in the window, rather than filling every last token of it.
 *
 * The prompt's own token count comes from two separate `llama_tokenize` calls (one to size the
 * buffer, one to fill it) that are expected to agree but are not proven to by anything this file
 * checks -- a small, fixed reserve is cheaper than a mismatch between them turning into a decode
 * past the context's actual capacity.
 */
constexpr int kOutputSafetyMarginTokens = 16;

/**
 * Fixed, so that the same selection and the same action give the same answer.
 *
 * A user who taps "correct this" twice and gets two different corrections has been handed a
 * slot machine rather than a tool.
 */
constexpr uint32_t kSamplerSeed = 0xB0DE4Eu;

/** See the comment at its one call site, in applyChatTemplate. */
constexpr const char* kNoThink = "/no_think";

/**
 * Tokenised once at load to measure this model's real chars-per-token ratio. Ordinary mixed-case
 * prose with regular punctuation and spacing, long enough that a token or two of rounding error
 * does not swing the result -- not a pangram or a word list, which tokenise differently from what
 * a selection actually looks like.
 */
constexpr const char* kCalibrationSample =
    "The quick brown fox jumps over the lazy dog. Please review this paragraph and let me "
    "know what you think, including any changes you would suggest for tomorrow's meeting.";

/**
 * What applyChatTemplate wraps the text to transform in, and cleanResult strips from an answer
 * that echoed it back. One pair of constants rather than the same literal typed at both call
 * sites, so the two can never quietly drift out of agreement with each other.
 */
constexpr const char* kTextLabel = "\n\nText:\n";
constexpr const char* kTextFence = "\"\"\"";

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
 * and SmolLM3 -- the two families in KnownAssistModels.kt today -- actually emit; the other two
 * are the same idea under the names used elsewhere in the wider GGUF ecosystem. Kept as a list a
 * model addition might extend, rather than one pair hardcoded to today's two families.
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

/**
 * Removes a short label line a model prepends before the actual answer -- "Traducere:", seen
 * verbatim, with the translation itself on the next line -- when nothing after it needs the
 * label to make sense. The task already told the model what it is doing and the UI already
 * names the button that was pressed; a model announcing the same thing a second time, in its
 * own words, is the model narrating rather than answering, the same class of thing reasoning-tag
 * stripping exists for above.
 *
 * Narrow on purpose, the same way [unwrapWhole] is: only the first line, and only a short one --
 * a genuine first sentence that happens to contain a colon (a time, a ratio, direct speech) runs
 * on past forty characters or is the whole answer with nothing after the newline, either of
 * which leaves it alone.
 */
std::string stripLeadingLabel(const std::string& text) {
    const size_t newline = text.find('\n');
    if (newline == std::string::npos || newline == 0) {
        return text;
    }
    const std::string firstLine = trimmed(text.substr(0, newline));
    if (firstLine.empty() || firstLine.size() > 40 || firstLine.back() != ':') {
        return text;
    }
    const std::string rest = trimmed(text.substr(newline + 1));
    if (rest.empty()) {
        return text;
    }
    return rest;
}

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
std::string cleanResult(const std::string& raw, bool cleanFormatting) {
    std::string text = trimmed(raw);
    for (const WrapTag& tag : kReasoningTags) {
        text = stripTag(std::move(text), tag);
    }
    text = trimmed(text);
    if (!cleanFormatting) {
        return text;
    }
    // Ahead of the fence and quote stripping below: a labelled preamble is often followed by
    // the answer wrapped in one of those too ("Traducere:\n\"...\"", seen verbatim), and the
    // label has to come off first for what is left to be the plain wrapped answer those steps
    // already know how to handle.
    text = stripLeadingLabel(text);
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
    return trimmed(text);
}

/**
 * Silences llama.cpp's own logging.
 *
 * It writes model architecture, tensor names and token counts to the log by default. None of
 * that is secret, but this process is handed the user's selected text and the less it says
 * about what it is doing with it, the smaller the surface for something to end up in a bug
 * report. Errors are still surfaced, as return codes.
 */
void quietLog(ggml_log_level level, const char* text, void* /*userData*/) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", text);
    }
}

}  // namespace

TextAssist::~TextAssist() { unload(); }

int32_t TextAssist::load(const char* path, int contextTokens, int threads) {
    if (path == nullptr || path[0] == '\0') {
        return kErrArgument;
    }
    unload();

    llama_log_set(quietLog, nullptr);
    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    // No GPU offload. A keyboard's assistant must not compete with the foreground app for the
    // GPU, and the Vulkan backend is not built here anyway.
    modelParams.n_gpu_layers = 0;
    // Mapped rather than read, and explicitly not locked. A 600 MB model used once and dropped
    // should be page cache the kernel can reclaim under pressure, not an allocation this process
    // is charged for -- and mlock in a background process on a phone is a way to get the whole
    // process killed instead of a few pages evicted.
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;

    model_ = llama_model_load_from_file(path, modelParams);
    if (model_ == nullptr) {
        return kErrLoadFailed;
    }

    // Clamped to what the model was actually trained for. Asking for a longer context than the
    // training length does not fail -- it quietly produces worse output the further past it you
    // go, which is the least useful failure mode there is.
    const int trained = llama_model_n_ctx_train(model_);
    int requested = contextTokens;
    if (requested <= 0) {
        requested = kMinContextTokens;
    }
    requested = std::min(requested, kMaxContextTokens);
    if (trained > 0) {
        requested = std::min(requested, trained);
    }
    requested = std::max(requested, kMinContextTokens);
    contextTokens_ = requested;

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(contextTokens_);
    contextParams.n_batch = static_cast<uint32_t>(std::min(contextTokens_, 512));
    contextParams.n_threads = threads > 0 ? threads : 4;
    contextParams.n_threads_batch = contextParams.n_threads;

    context_ = llama_init_from_model(model_, contextParams);
    if (context_ == nullptr) {
        llama_model_free(model_);
        model_ = nullptr;
        return kErrContext;
    }

    // Low temperature and a tight nucleus by default. Every task here is a transformation of
    // text the user wrote -- summarise it, correct it, make it formal -- and none of them wants
    // invention. Greedy would be defensible; a little sampling avoids the degenerate repetition
    // that pure argmax falls into on small models. Both are adjustable -- see setSamplingParams.
    rebuildSampler();

    // No add_special/parse_special: this measures how the tokeniser splits ordinary content
    // alone, the same thing chunk and budget sizing use it for, without a BOS or template
    // overhead of a handful of tokens skewing a short sample's ratio.
    const llama_vocab* const vocab = llama_model_get_vocab(model_);
    const auto sampleLength = static_cast<int32_t>(std::strlen(kCalibrationSample));
    const int32_t sampleTokens = -llama_tokenize(vocab, kCalibrationSample, sampleLength, nullptr,
                                                 0, false, false);
    charsPerToken_ = sampleTokens > 0
        ? static_cast<float>(sampleLength) / static_cast<float>(sampleTokens)
        : 0.0f;

    return kOk;
}

void TextAssist::rebuildSampler() {
    if (sampler_ != nullptr) {
        llama_sampler_free(sampler_);
        sampler_ = nullptr;
    }
    llama_sampler_chain_params chainParams = llama_sampler_chain_default_params();
    sampler_ = llama_sampler_chain_init(chainParams);
    // A mild penalty against repeating a recent token, ahead of top-p/temperature in the chain
    // -- the order llama.cpp's own reference sampler uses, and the order that matters: this has
    // to see the raw logits before top-p narrows them down to the tokens it can still choose
    // between. Without it, a low temperature and a fixed seed -- both deliberate, see below --
    // can walk a small model into a short loop it never breaks out of on its own: asked to
    // translate a paragraph, it echoed a mistranslation of its own instruction's last sentence
    // twice in a row instead of ever reaching the actual text. 1.15 is a light touch, enough to
    // break a loop without visibly changing a correct answer; 64 tokens of lookback is long
    // enough to catch the kind of short phrase that repeated here.
    if (model_ != nullptr) {
        const llama_vocab* const vocab = llama_model_get_vocab(model_);
        llama_sampler_chain_add(
            sampler_,
            llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.15f, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(sampler_, llama_sampler_init_top_p(topP_, 1));
    llama_sampler_chain_add(sampler_, llama_sampler_init_temp(temperature_));
    // A fixed seed, so the same selection and the same action give the same answer. A user who
    // taps the action twice and gets two different rewrites has been given a slot machine.
    // Unaffected by setSamplingParams -- temperature and top-p change how the model gambles,
    // not whether the same gamble always lands the same way.
    llama_sampler_chain_add(sampler_, llama_sampler_init_dist(kSamplerSeed));
}

void TextAssist::setSamplingParams(float temperature, float topP) {
    // Clamped rather than trusted: this crosses JNI from a stored preference. Out of either
    // range, the request would not fail -- llama.cpp does not validate these -- it would just
    // quietly produce garbage or nothing but the single most likely token, forever.
    temperature_ = (temperature > 0.0f && temperature <= 2.0f) ? temperature : 0.3f;
    topP_ = (topP > 0.0f && topP <= 1.0f) ? topP : 0.9f;
    if (context_ == nullptr) {
        // Not loaded yet. load() reads temperature_ and topP_ when it builds the chain, so
        // there is nothing further to do until then.
        return;
    }
    rebuildSampler();
}

void TextAssist::unload() {
    if (sampler_ != nullptr) {
        llama_sampler_free(sampler_);
        sampler_ = nullptr;
    }
    if (context_ != nullptr) {
        llama_free(context_);
        context_ = nullptr;
    }
    if (model_ != nullptr) {
        llama_model_free(model_);
        model_ = nullptr;
    }
    contextTokens_ = 0;
    charsPerToken_ = 0.0f;
    // Whatever this claimed about the context's memory is meaningless once that memory is gone.
    lastPromptTokens_.clear();
}

std::string TextAssist::applyChatTemplate(const char* instruction, const char* text) const {
    // Instruction and text are kept as one user turn rather than a system prompt plus a user
    // turn: small instruction-tuned models follow a single concrete request far more reliably
    // than they follow a persona, and half the candidate models have no system role at all.
    std::string content;
    content.reserve(std::strlen(instruction) + std::strlen(text) + 24 + std::strlen(kNoThink));
    content += instruction;
    // Every model in KnownAssistModels.kt is Qwen3 or SmolLM3, and both read a literal
    // "/no_think" anywhere in the last turn as a request to skip their extended-thinking phase.
    // Without it, a reasoning-tuned model spends the entire (small, task-sized) output budget
    // narrating its reasoning and never reaches the actual answer -- which reads as "translate
    // does nothing" rather than as a formatting problem, because nothing resembling an answer
    // ever arrives. Placed right after the instruction and before the user's own text, not at
    // the very end of the turn: appended after the text, it would sit inside the very thing a
    // task like Translate or Correct is asked to transform, and become one more word to answer
    // for instead of a switch outside the content being processed.
    content += " ";
    content += kNoThink;
    // Labelled and fenced rather than just a blank line before it: seen once with a short,
    // ordinary paragraph -- three sentences, a couple of line breaks carried over from where it
    // was written, no quotation marks or code of its own -- where the model answered with a
    // translation of the instruction's own last sentence instead of the paragraph, twice, then
    // stopped. Nothing marked where the instruction ended and the text nobody asked it to touch
    // began; a blank line is not a boundary a small model reliably respects, especially once the
    // text itself has line breaks in it that read the same way. A labelled, fenced block is an
    // unambiguous one: everything between the two `"""` is data to transform, never part of the
    // request, whatever it contains -- including a `"""` of its own, since text_assist only ever
    // reads up to the end of generation, not up to a closing fence it went looking for.
    content += kTextLabel;
    content += kTextFence;
    content += "\n";
    content += text;
    content += "\n";
    content += kTextFence;

    const char* templateText = llama_model_chat_template(model_, nullptr);
    if (templateText == nullptr) {
        // No template in the GGUF metadata. Passing the raw text is the honest fallback: it is
        // what a base model expects, and inventing a chat format the model was not trained on
        // produces worse output than none.
        return content;
    }

    llama_chat_message message{"user", content.c_str()};
    std::vector<char> buffer(content.size() + 1024);
    int32_t written = llama_chat_apply_template(templateText, &message, 1, true, buffer.data(),
                                                static_cast<int32_t>(buffer.size()));
    if (written > static_cast<int32_t>(buffer.size())) {
        buffer.resize(static_cast<size_t>(written) + 1);
        written = llama_chat_apply_template(templateText, &message, 1, true, buffer.data(),
                                            static_cast<int32_t>(buffer.size()));
    }
    if (written <= 0) {
        return content;
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

int32_t TextAssist::run(const char* instruction, const char* text, float outputRatio,
                        int minOutputTokens, int maxOutputTokensCeiling, bool useRemainingContext,
                        bool reuseSharedPrefix, bool cleanFormatting, std::string* out,
                        bool* outTruncated) {
    if (out == nullptr || instruction == nullptr || text == nullptr) {
        return kErrArgument;
    }
    if (!isLoaded()) {
        return kErrNoModel;
    }
    if (running_) {
        return kErrBusy;
    }
    const size_t textLength = std::strlen(text);
    if (textLength == 0 || textLength > kMaxInputChars) {
        // Refused with a code the UI turns into a sentence, rather than allowed through to
        // fail as an allocation error somewhere inside the runtime.
        return kErrTooLong;
    }

    running_ = true;
    cancelRequested_ = false;
    out->clear();

    const std::string prompt = applyChatTemplate(instruction, text);
    const llama_vocab* vocab = llama_model_get_vocab(model_);

    // Two calls: the first with a negative capacity returns the count needed.
    const int32_t needed = -llama_tokenize(vocab, prompt.c_str(),
                                           static_cast<int32_t>(prompt.size()), nullptr, 0, true,
                                           true);
    if (needed <= 0) {
        running_ = false;
        return kErrTokenise;
    }

    // outputRatio and minOutputTokens describe the task, not this specific request; needed is
    // this request's exact prompt size, known only now that it has actually been tokenised. No
    // guess from the input's character count is involved -- the budget below is arithmetic on a
    // real number, not an estimate of one.
    int32_t maxOutputTokens = std::clamp(
        static_cast<int32_t>(static_cast<float>(needed) * outputRatio), minOutputTokens,
        maxOutputTokensCeiling);

    // The prompt and the answer share one window, so the check is against both.
    if (needed + maxOutputTokens >= contextTokens_) {
        running_ = false;
        return kErrTooLong;
    }
    if (useRemainingContext) {
        // The real room left for the answer can only be at least as large as maxOutputTokens
        // above (the check just made already refused anything smaller), so raising the cap to it
        // cannot admit a request that would otherwise have been refused -- it only stops
        // outputRatio's guess about how long the answer will be, which is the one thing about
        // this request that genuinely cannot be known in advance, from cutting a correct answer
        // off mid-sentence.
        const int32_t remaining = contextTokens_ - static_cast<int32_t>(needed) -
                                  kOutputSafetyMarginTokens;
        if (remaining > maxOutputTokens) {
            maxOutputTokens = remaining;
        }
    }

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       needed, true, true) < 0) {
        running_ = false;
        return kErrTokenise;
    }

    // A fresh window for every request, unless the caller has said this one may share the start
    // of the previous request's prompt: reuseSharedPrefix is true only for a chunk after the
    // first within one ChunkedAssistRunner job, where the shared start is the same task's fixed
    // instruction and template wrapper, identical for every chunk of that job by construction --
    // never another selection's text, and never another action's. Leaving an unrelated request's
    // tokens in the cache would let one selection influence the answer to the next, which is
    // both wrong and a small information leak between two things the user thought were separate;
    // this reuses only what was never that in the first place.
    size_t commonLen = 0;
    if (reuseSharedPrefix && !lastPromptTokens_.empty()) {
        // Capped one short of the whole prompt: sampling the first generated token needs logits
        // from a decode that actually just happened, and reusing every last token would leave
        // nothing freshly decoded to produce them from.
        const size_t limit = std::min(lastPromptTokens_.size(), tokens.size() - 1);
        while (commonLen < limit && lastPromptTokens_[commonLen] == tokens[commonLen]) {
            ++commonLen;
        }
    }
    // Invalidated the instant the memory is about to change -- restored below only once the
    // decode it would describe has actually succeeded, so a failure here never leaves this
    // claiming content a later request could wrongly try to build on.
    lastPromptTokens_.clear();

    llama_memory_t memory = llama_get_memory(context_);
    if (commonLen > 0) {
        // Keeps [0, commonLen) -- the shared prefix -- and drops everything from there on: the
        // rest of the previous prompt that did not match this one, and whatever was generated
        // after it. Positions for what gets decoded next are assigned automatically by
        // llama_decode, continuing from wherever the memory now actually ends -- exactly
        // commonLen, once this call returns.
        llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(commonLen), -1);
    } else {
        llama_memory_clear(memory, true);
    }

    llama_batch batch = llama_batch_get_one(tokens.data() + commonLen,
                                            static_cast<int32_t>(tokens.size() - commonLen));
    if (llama_decode(context_, batch) != 0) {
        running_ = false;
        return kErrDecode;
    }
    lastPromptTokens_.assign(tokens.begin(), tokens.end());

    char piece[256];
    llama_token next = 0;
    bool endedNaturally = false;
    for (int generated = 0; generated < maxOutputTokens; ++generated) {
        if (cancelRequested_) {
            break;
        }
        next = llama_sampler_sample(sampler_, context_, -1);
        if (llama_vocab_is_eog(vocab, next)) {
            endedNaturally = true;
            break;
        }
        const int32_t length = llama_token_to_piece(vocab, next, piece,
                                                    static_cast<int32_t>(sizeof(piece)), 0, false);
        if (length > 0) {
            out->append(piece, static_cast<size_t>(length));
        }
        llama_sampler_accept(sampler_, next);
        batch = llama_batch_get_one(&next, 1);
        if (llama_decode(context_, batch) != 0) {
            running_ = false;
            return kErrDecode;
        }
    }
    // Not just the token-budget case: a cancelled request also leaves here with less than the
    // whole answer, and the caller telling the two apart from the text alone has nothing to go
    // on -- both look like an answer that simply stops.
    if (outTruncated != nullptr) {
        *outTruncated = !endedNaturally;
    }

    *out = cleanResult(*out, cleanFormatting);
    running_ = false;
    return kOk;
}

}  // namespace borderkeys
