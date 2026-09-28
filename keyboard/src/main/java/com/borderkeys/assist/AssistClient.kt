// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask

/**
 * The keyboard's end of the assistant. Binds to [AssistProtocol.SERVICE_CLASS] by name, which
 * resolves only in the `plus` flavor.
 */
class AssistClient(private val context: Context) {

    interface Listener {
        /** `truncated` is true when the length limit or a cancel cut [text] short. */
        fun onAssistResult(requestId: Int, text: String, modelName: String?, truncated: Boolean)
        fun onAssistError(requestId: Int, error: Int)

        /** `charsPerToken` is the loaded model's measured ratio, or 0 before one has loaded. */
        fun onAssistAvailability(available: Boolean, modelName: String?, charsPerToken: Float)
    }

    var listener: Listener? = null

    private var service: Messenger? = null
    private var bound = false
    private var nextRequestId = 1
    private var pendingRun: Message? = null

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            AssistProtocol.MSG_RESULT -> {
                val data = message.data ?: Bundle.EMPTY
                val requestId = data.getInt(AssistProtocol.KEY_REQUEST_ID)
                val error = data.getInt(AssistProtocol.KEY_ERROR)
                val text = data.getString(AssistProtocol.KEY_RESULT)
                if (error == AssistProtocol.ERROR_NONE && text != null) {
                    listener?.onAssistResult(
                        requestId, text, data.getString(AssistProtocol.KEY_MODEL_NAME),
                        data.getBoolean(AssistProtocol.KEY_TRUNCATED),
                    )
                } else {
                    listener?.onAssistError(requestId, error)
                }
                true
            }
            AssistProtocol.MSG_STATUS -> {
                val data = message.data ?: Bundle.EMPTY
                listener?.onAssistAvailability(
                    data.getInt(AssistProtocol.KEY_ERROR) == AssistProtocol.ERROR_NONE,
                    data.getString(AssistProtocol.KEY_MODEL_NAME),
                    data.getFloat(AssistProtocol.KEY_CHARS_PER_TOKEN),
                )
                true
            }
            else -> false
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
            // Sends the request held while binding.
            pendingRun?.let { queued ->
                pendingRun = null
                dispatch(queued)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** True when the assistant service resolves, in the `plus` flavor. */
    fun isAvailable(): Boolean = resolveIntent() != null

    private fun resolveIntent(): Intent? {
        val intent = Intent().setClassName(context.packageName, AssistProtocol.SERVICE_CLASS)
        val resolved = context.packageManager.resolveService(intent, 0)
        return if (resolved == null) null else intent
    }

    fun connect(): Boolean {
        if (bound) {
            return true
        }
        val intent = resolveIntent() ?: return false
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return bound
    }

    fun disconnect() {
        if (!bound) {
            return
        }
        // Cancels generation, then unbinds.
        runCatching { service?.send(Message.obtain(null, AssistProtocol.MSG_CANCEL)) }
        runCatching { context.unbindService(connection) }
        bound = false
        service = null
        pendingRun = null
    }

    fun queryAvailability() {
        if (!connect()) {
            listener?.onAssistAvailability(false, null, 0f)
            return
        }
        dispatch(
            Message.obtain(null, AssistProtocol.MSG_QUERY_STATUS).apply { replyTo = incoming },
        )
    }

    /**
     * Runs a task over a selection. Returns the request id, which comes back with the answer, or
     * -1 when the assistant is absent or the request invalid. `continueJob` is true only for a
     * [ChunkedAssistRunner] chunk after the first; see [AssistProtocol.KEY_CONTINUE_JOB].
     */
    fun run(task: AssistTask, text: String, instruction: String = "", continueJob: Boolean = false): Int {
        if (text.isEmpty() || text.length > AssistProtocol.MAX_SELECTION_CHARS) {
            return -1
        }
        // A custom task needs an instruction.
        if (task == AssistTask.CUSTOM &&
            (instruction.isBlank() || instruction.length > AssistTask.MAX_INSTRUCTION_CHARS)
        ) {
            return -1
        }
        if (!connect()) {
            return -1
        }
        val requestId = nextRequestId++
        val message = Message.obtain(null, AssistProtocol.MSG_RUN).apply {
            replyTo = incoming
            data = Bundle().apply {
                putInt(AssistProtocol.KEY_REQUEST_ID, requestId)
                putInt(AssistProtocol.KEY_TASK, task.id)
                putString(AssistProtocol.KEY_TEXT, text)
                if (instruction.isNotEmpty()) {
                    putString(AssistProtocol.KEY_INSTRUCTION, instruction)
                }
                putBoolean(AssistProtocol.KEY_CONTINUE_JOB, continueJob)
            }
        }
        dispatch(message)
        return requestId
    }

    fun cancel() {
        runCatching { service?.send(Message.obtain(null, AssistProtocol.MSG_CANCEL)) }
    }

    private fun dispatch(message: Message) {
        val target = service
        if (target == null) {
            pendingRun = message
            return
        }
        // A failed send is held until the service connects again.
        if (runCatching { target.send(message) }.isFailure) {
            service = null
            pendingRun = message
        }
    }
}
