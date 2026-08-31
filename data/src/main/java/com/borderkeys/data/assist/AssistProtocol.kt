// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/**
 * The wire contract between the keyboard and the assistant process.
 *
 * It lives in `:data` because both ends need it and neither may depend on the other: `:keyboard`
 * must not know `:assist` exists -- the assistant is absent from the free build entirely, and
 * the keyboard has to work without it. The keyboard binds by component name, as a string, the
 * same way it opens the settings screen.
 *
 * A [android.os.Messenger] rather than AIDL. The exchange is one request and one reply carrying
 * a bounded string; AIDL would generate a stub, a proxy and an interface file to express that.
 */
object AssistProtocol {

    /** The service the keyboard binds to, by name. */
    const val SERVICE_PACKAGE_SUFFIX = ""
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

    // ---- errors ---------------------------------------------------------------------------
    //
    // Every one of these is a sentence the user can be shown. A failure that reaches the sheet
    // as "something went wrong" is a failure the user cannot act on.

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
}
