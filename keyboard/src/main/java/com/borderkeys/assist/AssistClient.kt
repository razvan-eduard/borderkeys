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
 * The keyboard's end of the assistant, and the only thing in `:keyboard` that knows it exists.
 *
 * Binds by component *name*, as a string. That is not a stylistic choice: `:assist` is attached
 * to the application only in the `plus` flavor, so in the free build the class genuinely is not
 * there. A compile-time reference would make the free build impossible; a name that fails to
 * resolve makes the feature simply absent, which is what it is.
 *
 * The connection is opened when the user asks for something and dropped when the sheet closes.
 * The service unloads its model on its own timer after that, so an assistant used once costs
 * nothing for the rest of the session.
 */
class AssistClient(private val context: Context) {

    interface Listener {
        fun onAssistResult(requestId: Int, text: String, modelName: String?)
        fun onAssistError(requestId: Int, error: Int)
        fun onAssistAvailability(available: Boolean, modelName: String?)
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
                )
                true
            }
            else -> false
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
            // A request made before the binding completed is held rather than dropped: binding
            // takes a process start the first time, and the user pressed the button before that.
            pendingRun?.let { queued ->
                pendingRun = null
                dispatch(queued)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** True when the `plus` flavor is installed and the service can be resolved at all. */
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
        // Tell the service to stop generating before letting go: an answer nobody will read is
        // still seconds of a phone's CPU.
        runCatching { service?.send(Message.obtain(null, AssistProtocol.MSG_CANCEL)) }
        runCatching { context.unbindService(connection) }
        bound = false
        service = null
        pendingRun = null
    }

    fun queryAvailability() {
        if (!connect()) {
            listener?.onAssistAvailability(false, null)
            return
        }
        dispatch(
            Message.obtain(null, AssistProtocol.MSG_QUERY_STATUS).apply { replyTo = incoming },
        )
    }

    /**
     * Runs a task over a selection. Returns the request id, or -1 when the assistant is absent.
     *
     * The id comes back with the answer, so a result arriving after the user has already closed
     * the sheet and started something else can be discarded rather than shown.
     */
    fun run(task: AssistTask, text: String): Int {
        if (text.isEmpty() || text.length > AssistProtocol.MAX_SELECTION_CHARS) {
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
        // A service that died between binding and sending is a normal outcome -- it stops itself
        // on an idle timer. Reconnecting on the next request is the whole recovery.
        if (runCatching { target.send(message) }.isFailure) {
            service = null
            pendingRun = message
        }
    }
}
