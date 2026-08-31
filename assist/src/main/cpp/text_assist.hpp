// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TEXT_ASSIST_HPP
#define BORDERKEYS_TEXT_ASSIST_HPP

#include <cstddef>
#include <cstdint>
#include <string>

struct llama_model;
struct llama_context;
struct llama_sampler;

namespace borderkeys {

/**
 * A single loaded language model, and the one operation this application asks of it.
 *
 * Everything about this class is shaped by where it runs: a separate process, started when the
 * user asks for something and killed shortly afterwards. It is not a service, it holds no
 * queue, and it has no concept of a conversation. The user selects text, picks an action, gets
 * one answer, and the model goes away again.
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
    };

    ~TextAssist();

    /**
     * Loads a GGUF model from an absolute path.
     *
     * The caller has already verified the file's SHA-256 against the known-model registry; this
     * takes the path on that basis and does not re-check. `contextTokens` is clamped to what the
     * model was trained for, because asking for more silently produces nonsense rather than an
     * error.
     */
    int32_t load(const char* path, int contextTokens, int threads);

    /** Frees the model and its context. Called on the idle timeout, and before the process dies. */
    void unload();

    bool isLoaded() const { return context_ != nullptr; }

    int contextTokens() const { return contextTokens_; }

    /**
     * Runs one instruction over one piece of text and returns the whole answer.
     *
     * Streaming is deliberately absent. The result is shown in a sheet with a Replace button
     * next to it, so a half-finished answer has nothing to be done with -- and a token-by-token
     * callback across a process boundary would cost an IPC per token.
     */
    int32_t run(const char* instruction, const char* text, int maxOutputTokens,
                std::string* out);

    /** Asks the current run to stop at the next token boundary. Safe from another thread. */
    void requestCancel() { cancelRequested_ = true; }

private:
    std::string applyChatTemplate(const char* instruction, const char* text) const;

    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
    llama_sampler* sampler_ = nullptr;
    int contextTokens_ = 0;
    bool cancelRequested_ = false;
    bool running_ = false;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TEXT_ASSIST_HPP
