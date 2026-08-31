// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "text_assist.hpp"

#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <cstring>
#include <vector>

namespace borderkeys {
namespace {

constexpr const char* kTag = "BorderKeysAssist";

/** Hard ceiling on the input, checked before tokenising rather than after. */
constexpr int kMaxInputChars = 8000;
constexpr int kMinContextTokens = 512;
constexpr int kMaxContextTokens = 8192;

/**
 * Fixed, so that the same selection and the same action give the same answer.
 *
 * A user who taps "correct this" twice and gets two different corrections has been handed a
 * slot machine rather than a tool.
 */
constexpr uint32_t kSamplerSeed = 0xB0DE4Eu;

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

    // Low temperature and a tight nucleus. Every task here is a transformation of text the user
    // wrote -- summarise it, correct it, make it formal -- and none of them wants invention.
    // Greedy would be defensible; a little sampling avoids the degenerate repetition that pure
    // argmax falls into on small models.
    llama_sampler_chain_params chainParams = llama_sampler_chain_default_params();
    sampler_ = llama_sampler_chain_init(chainParams);
    llama_sampler_chain_add(sampler_, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler_, llama_sampler_init_temp(0.3f));
    // A fixed seed, so the same selection and the same action give the same answer. A user who
    // taps the action twice and gets two different rewrites has been given a slot machine.
    llama_sampler_chain_add(sampler_, llama_sampler_init_dist(kSamplerSeed));
    return kOk;
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
}

std::string TextAssist::applyChatTemplate(const char* instruction, const char* text) const {
    // Instruction and text are kept as one user turn rather than a system prompt plus a user
    // turn: small instruction-tuned models follow a single concrete request far more reliably
    // than they follow a persona, and half the candidate models have no system role at all.
    std::string content;
    content.reserve(std::strlen(instruction) + std::strlen(text) + 8);
    content += instruction;
    content += "\n\n";
    content += text;

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

int32_t TextAssist::run(const char* instruction, const char* text, int maxOutputTokens,
                        std::string* out) {
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
    // The prompt and the answer share one window, so the check is against both.
    if (needed + maxOutputTokens >= contextTokens_) {
        running_ = false;
        return kErrTooLong;
    }

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       needed, true, true) < 0) {
        running_ = false;
        return kErrTokenise;
    }

    // A fresh window for every request. This process may serve several actions before its idle
    // timeout, and leaving the previous request's tokens in the cache would let one selection
    // influence the answer to the next -- which is both wrong and a small information leak
    // between two things the user thought were separate.
    llama_memory_clear(llama_get_memory(context_), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(context_, batch) != 0) {
        running_ = false;
        return kErrDecode;
    }

    char piece[256];
    llama_token next = 0;
    for (int generated = 0; generated < maxOutputTokens; ++generated) {
        if (cancelRequested_) {
            break;
        }
        next = llama_sampler_sample(sampler_, context_, -1);
        if (llama_vocab_is_eog(vocab, next)) {
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

    running_ = false;
    return kOk;
}

}  // namespace borderkeys
