// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TEXT_ASSIST_HPP
#define BORDERKEYS_TEXT_ASSIST_HPP

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_sampler;

namespace borderkeys {

/**
 * A single loaded language model, and the one operation this application asks of it: one
 * instruction over one piece of text, one answer, no conversation.
 *
 * Not thread safe. One request at a time, from the service's worker thread.
 */
class TextAssist {
public:
    enum Status : int32_t {
        kOk = 0,
        kErrNoModel = -1,
        kErrLoadFailed = -2,
        kErrContext = -3,
        kErrTooLong = -4,
        kErrTokenise = -5,
        kErrDecode = -6,
        kErrBusy = -7,
        kErrArgument = -8,
        /** A C++ exception was caught at the JNI boundary, in assist_jni.cpp's load or run. */
        kErrException = -9,
    };

    ~TextAssist();

    /**
     * Loads a GGUF model from an absolute path whose SHA-256 the caller has already checked
     * against the known-model registry. `contextTokens` is clamped to what the model was trained
     * for.
     */
    int32_t load(const char* path, int contextTokens, int threads);

    /** Frees the model and its context. Called on the idle timeout, and before the process dies. */
    void unload();

    bool isLoaded() const { return context_ != nullptr; }

    int contextTokens() const { return contextTokens_; }

    /**
     * This model's chars-per-token ratio, measured against a fixed sample at [load]; 0 before a
     * model has been loaded.
     */
    float charsPerToken() const { return charsPerToken_; }

    /**
     * Replaces the sampler's temperature and nucleus (top-p); an out-of-range value becomes the
     * default. Safe before [load] (the values are kept for when the chain is built) or after (the
     * chain is rebuilt, with no model reload). The sampler seed does not change.
     */
    void setSamplingParams(float temperature, float topP);

    /**
     * Runs one instruction over one piece of text and returns the whole answer, not streamed.
     *
     * `cleanFormatting` gates the part of cleanResult's cleanup that can contradict the
     * instruction.
     *
     * `outputRatio` and `minOutputTokens` are the task's
     * ([com.borderkeys.data.assist.AssistTask]), and `maxOutputTokensCeiling` is its
     * `MAX_OUTPUT_TOKENS`; the token budget is computed from them against the prompt's tokenised
     * size. With `useRemainingContext` that budget is a floor and generation may run to the end of
     * the context window; without it, the budget is the stop.
     *
     * `outTruncated`, when not null, is set on a [kOk] return to whether generation stopped for
     * a reason other than the model ending the answer (the token budget, or [requestCancel]). It
     * is left untouched on every other return.
     *
     * `reuseSharedPrefix` is true only for a chunk after the first within one
     * [com.borderkeys.assist.ChunkedAssistRunner] job: the start this prompt shares with the
     * previous one stays in the context and is not decoded again.
     */
    int32_t run(const char* instruction, const char* text, float outputRatio,
                int minOutputTokens, int maxOutputTokensCeiling, bool useRemainingContext,
                bool reuseSharedPrefix, bool cleanFormatting, std::string* out,
                bool* outTruncated);

    /**
     * Asks the current run to stop at the next token boundary. Safe from another thread:
     * [cancelRequested_] is the one atomic field.
     */
    void requestCancel() { cancelRequested_ = true; }

private:
    std::string applyChatTemplate(const char* instruction, const char* text) const;

    /** Frees [sampler_] if set and builds a new chain from [temperature_] and [topP_]. */
    void rebuildSampler();

    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
    llama_sampler* sampler_ = nullptr;
    int contextTokens_ = 0;
    float charsPerToken_ = 0.0f;
    std::atomic<bool> cancelRequested_{false};
    bool running_ = false;
    // The tokens the context's memory holds as a prompt, at the positions they were decoded at,
    // or empty when nothing in memory is known to be one. Cleared before a decode, restored only
    // after it succeeds.
    std::vector<int32_t> lastPromptTokens_;
    // Low temperature and a tight nucleus by default.
    float temperature_ = 0.3f;
    float topP_ = 0.9f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TEXT_ASSIST_HPP
