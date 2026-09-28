// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/**
 * The [android.os.Messenger] contract between the keyboard and the assistant process, which the
 * keyboard binds by component name.
 */
object AssistProtocol {

    /** The service the keyboard binds to, by name, inside its own package. */
    const val SERVICE_CLASS = "com.borderkeys.assist.TextAssistService"

    // ---- messages -------------------------------------------------------------------------

    /** Keyboard to service: run a task. Reply goes to `Message.replyTo`. */
    const val MSG_RUN = 1

    /** Service to keyboard: the answer, or a failure. */
    const val MSG_RESULT = 2

    /** Keyboard to service: abandon the running task; the user closed the sheet. */
    const val MSG_CANCEL = 3

    /** Keyboard to service: is a model available and loadable? Replies with MSG_STATUS. */
    const val MSG_QUERY_STATUS = 4
    const val MSG_STATUS = 5

    // ---- bundle keys ----------------------------------------------------------------------

    const val KEY_TASK = "task"
    const val KEY_TEXT = "text"
    const val KEY_RESULT = "result"
    const val KEY_ERROR = "error"
    const val KEY_MODEL_NAME = "model"
    const val KEY_REQUEST_ID = "request"

    /**
     * Service to keyboard, alongside a successful KEY_RESULT: the answer stops short of where
     * the model itself would have stopped -- the length limit was reached, or the request was
     * cancelled mid-generation.
     */
    const val KEY_TRUNCATED = "truncated"

    /**
     * Service to keyboard, on an MSG_STATUS reply: the loaded model's chars-per-token ratio
     * (`TextAssist::charsPerToken`), or absent when nothing is loaded.
     */
    const val KEY_CHARS_PER_TOKEN = "chars_per_token"

    /**
     * The instruction a user wrote, for AssistTask.CUSTOM. Absent for every other task, whose
     * instruction is a constant the service already has.
     */
    const val KEY_INSTRUCTION = "instruction"

    /**
     * Keyboard to service, on an MSG_RUN: true for a chunk after the first within one
     * ChunkedAssistRunner job, letting the service keep the model's memory of the shared prefix
     * (`TextAssist::run`'s `reuseSharedPrefix`). Absent, read as false, otherwise.
     */
    const val KEY_CONTINUE_JOB = "continue_job"

    // ---- errors ---------------------------------------------------------------------------

    const val ERROR_NONE = 0

    /** No model has been imported yet. The user is sent to Settings. */
    const val ERROR_NO_MODEL = 1

    /** The file on disk no longer matches the hash it was imported with. */
    const val ERROR_MODEL_CHANGED = 2

    /** The model exists but could not be loaded -- corrupt, or out of memory. */
    const val ERROR_LOAD_FAILED = 3

    /** The selection is longer than the model's context window allows. */
    const val ERROR_TOO_LONG = 4

    /** Inference failed part way through. */
    const val ERROR_FAILED = 5

    /** The keyboard asked while in a password field. The service refuses; see BorderKeysService. */
    const val ERROR_PRIVATE_MODE = 6

    const val ERROR_BUSY = 7

    /** The longest selection that may be sent, in characters. Enforced on both sides. */
    const val MAX_SELECTION_CHARS = 8000

    /** A custom task arrived with no instruction, or one longer than the cap. */
    const val ERROR_NO_INSTRUCTION = 8
}
