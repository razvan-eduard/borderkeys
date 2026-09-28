// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "text_assist.hpp"

#include "answer_cleanup.hpp"


#include <algorithm>
#include <android/log.h>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <llama.h>
#include <string>
#include <vector>

namespace borderkeys {
namespace {

constexpr const char* kTag = "BorderKeysAssist";

/** Hard ceiling on the input, checked before tokenising rather than after. */
constexpr int kMaxInputChars = 8000;
constexpr int kMinContextTokens = 512;
constexpr int kMaxContextTokens = 8192;

/**
 * Left unclaimed when `run`'s `useRemainingContext` raises the output cap to the space left in
 * the window.
 */
constexpr int kOutputSafetyMarginTokens = 16;

/** Fixed, so that the same selection and the same action give the same answer. */
constexpr uint32_t kSamplerSeed = 0xB0DE4Eu;

/** Asks a reasoning-tuned model to skip its thinking phase. */
constexpr const char* kNoThink = "/no_think";

/** Tokenised once at load to measure this model's chars-per-token ratio: ordinary prose. */
constexpr const char* kCalibrationSample =
    "The quick brown fox jumps over the lazy dog. Please review this paragraph and let me "
    "know what you think, including any changes you would suggest for tomorrow's meeting.";

/** Silences llama.cpp's own logging; errors are still surfaced, as return codes. */
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
    // No GPU offload.
    modelParams.n_gpu_layers = 0;
    // Mapped, not read, and not locked.
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;

    model_ = llama_model_load_from_file(path, modelParams);
    if (model_ == nullptr) {
        return kErrLoadFailed;
    }

    // Clamped to what the model was trained for.
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

    // Low temperature and a tight nucleus by default; setSamplingParams changes both.
    rebuildSampler();

    // No add_special/parse_special: the content alone, without BOS or template tokens.
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
    // A mild penalty against repeating any of the last 64 tokens, ahead of top-p and temperature
    // in the chain so that it sees the raw logits.
    if (model_ != nullptr) {
        const llama_vocab* const vocab = llama_model_get_vocab(model_);
        llama_sampler_chain_add(
            sampler_,
            llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.15f, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(sampler_, llama_sampler_init_top_p(topP_, 1));
    llama_sampler_chain_add(sampler_, llama_sampler_init_temp(temperature_));
    // A fixed seed, which setSamplingParams does not change.
    llama_sampler_chain_add(sampler_, llama_sampler_init_dist(kSamplerSeed));
}

void TextAssist::setSamplingParams(float temperature, float topP) {
    // An out-of-range value becomes the default; llama.cpp does not validate these.
    temperature_ = (temperature > 0.0f && temperature <= 2.0f) ? temperature : 0.3f;
    topP_ = (topP > 0.0f && topP <= 1.0f) ? topP : 0.9f;
    if (context_ == nullptr) {
        // Not loaded yet; load() builds the chain from temperature_ and topP_.
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
    // The memory it described is gone.
    lastPromptTokens_.clear();
}

std::string TextAssist::applyChatTemplate(const char* instruction, const char* text) const {
    // Instruction and text as one user turn, with no system prompt.
    std::string content;
    content.reserve(std::strlen(instruction) + std::strlen(text) + 24 + std::strlen(kNoThink));
    content += instruction;
    // "/no_think" between the instruction and the text: Qwen3 and SmolLM3 read it anywhere in
    // the last turn as a request to skip their thinking phase.
    content += " ";
    content += kNoThink;
    // The text under a label, between two `"""` fences. The answer is read to the end of
    // generation, not to a fence.
    content += kTextLabel;
    content += kTextFence;
    content += "\n";
    content += text;
    content += "\n";
    content += kTextFence;

    const char* templateText = llama_model_chat_template(model_, nullptr);
    if (templateText == nullptr) {
        // No template in the GGUF metadata: the raw text.
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

namespace {

/** Sets a flag for exactly as long as the scope it lives in, including an exit by exception. */
class RunningGuard {
public:
    explicit RunningGuard(bool& flag) : flag_(flag) { flag_ = true; }
    ~RunningGuard() { flag_ = false; }
    RunningGuard(const RunningGuard&) = delete;
    RunningGuard& operator=(const RunningGuard&) = delete;

private:
    bool& flag_;
};

}  // namespace

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
        // Refused with a code the UI turns into a sentence.
        return kErrTooLong;
    }

    const RunningGuard running(running_);
    cancelRequested_ = false;
    out->clear();

    const std::string prompt = applyChatTemplate(instruction, text);
    const llama_vocab* vocab = llama_model_get_vocab(model_);

    // Two calls: the first with a negative capacity returns the count needed.
    const int32_t needed = -llama_tokenize(vocab, prompt.c_str(),
                                           static_cast<int32_t>(prompt.size()), nullptr, 0, true,
                                           true);
    if (needed <= 0) {
        return kErrTokenise;
    }

    // The task's ratio and floor, against this prompt's exact token count.
    int32_t maxOutputTokens = std::clamp(
        static_cast<int32_t>(static_cast<float>(needed) * outputRatio), minOutputTokens,
        maxOutputTokensCeiling);

    // The prompt and the answer share one window, so the check is against both.
    if (needed + maxOutputTokens >= contextTokens_) {
        return kErrTooLong;
    }
    if (useRemainingContext) {
        // At least maxOutputTokens: anything smaller was refused above.
        const int32_t remaining = contextTokens_ - static_cast<int32_t>(needed) -
                                  kOutputSafetyMarginTokens;
        if (remaining > maxOutputTokens) {
            maxOutputTokens = remaining;
        }
    }

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       needed, true, true) < 0) {
        return kErrTokenise;
    }

    // A fresh window for every request, except that with reuseSharedPrefix the start this prompt
    // shares with the previous one is kept.
    size_t commonLen = 0;
    if (reuseSharedPrefix && !lastPromptTokens_.empty()) {
        // One short of the whole prompt: the first generated token needs logits from a decode.
        const size_t limit = std::min(lastPromptTokens_.size(), tokens.size() - 1);
        while (commonLen < limit && lastPromptTokens_[commonLen] == tokens[commonLen]) {
            ++commonLen;
        }
    }
    // Cleared before the memory changes; restored below only once the decode has succeeded.
    lastPromptTokens_.clear();

    llama_memory_t memory = llama_get_memory(context_);
    if (commonLen > 0) {
        // Keeps [0, commonLen) and drops the rest; llama_decode continues from commonLen.
        llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(commonLen), -1);
    } else {
        llama_memory_clear(memory, true);
    }

    llama_batch batch = llama_batch_get_one(tokens.data() + commonLen,
                                            static_cast<int32_t>(tokens.size() - commonLen));
    if (llama_decode(context_, batch) != 0) {
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
            return kErrDecode;
        }
    }
    // Set for a cancelled request as well as for an exhausted budget.
    if (outTruncated != nullptr) {
        *outTruncated = !endedNaturally;
    }

    *out = cleanResult(*out, text, cleanFormatting);
    return kOk;
}

}  // namespace borderkeys
