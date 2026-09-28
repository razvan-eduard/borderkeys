// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.assist

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.AssistCategory
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask
import kotlinx.coroutines.runBlocking

/**
 * The text assistant, in its own process. The model is loaded when the user asks for something
 * and released after [IDLE_TIMEOUT_MILLIS] of silence; the process can be killed at any moment.
 * No queue and no session: one request, one answer. The model is a file the user chose, verified
 * against a known hash before it was mapped.
 */
class TextAssistService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var handle = 0L
    private var loadedModelName: String? = null

    /** The file name of the model currently mapped. */
    private var loadedModelFile: String? = null

    /** Unloads the model after a period of silence, then stops the process. */
    private val idleRunnable = Runnable { releaseAndStop() }

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            AssistProtocol.MSG_RUN -> {
                restartIdleTimer()
                handleRun(message)
                true
            }
            AssistProtocol.MSG_CANCEL -> {
                // The user closed the sheet: generation stops at the next token.
                if (handle != 0L) {
                    AssistNative.nativeCancel(handle)
                }
                true
            }
            AssistProtocol.MSG_QUERY_STATUS -> {
                restartIdleTimer()
                handleStatusQuery(message)
                true
            }
            else -> false
        }
    })

    override fun onCreate() {
        super.onCreate()
        DataGraph.install(applicationContext)
        // Below default priority.
        worker = HandlerThread("borderkeys-assist", Process.THREAD_PRIORITY_BACKGROUND)
        worker.start()
        workerHandler = Handler(worker.looper)
        handle = AssistNative.nativeCreate()
        restartIdleTimer()
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleRunnable)
        val toDestroy = handle
        handle = 0L
        workerHandler.post {
            if (toDestroy != 0L) {
                AssistNative.nativeUnload(toDestroy)
                AssistNative.nativeDestroy(toDestroy)
            }
            worker.quitSafely()
        }
        super.onDestroy()
    }

    private fun restartIdleTimer() {
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MILLIS)
    }

    private fun releaseAndStop() {
        val current = handle
        // On the worker thread, which handleRun reads these from, after any request still running.
        if (current != 0L) {
            workerHandler.post {
                AssistNative.nativeUnload(current)
                loadedModelName = null
                loadedModelFile = null
            }
        } else {
            loadedModelName = null
            loadedModelFile = null
        }
        stopSelf()
    }

    // ---- requests ----------------------------------------------------------------------------

    private fun handleStatusQuery(message: Message) {
        val reply = message.replyTo ?: return
        workerHandler.post {
            // Caught here, as in handleRun.
            try {
                val model = runBlocking { DataGraph.assistModels.activeVerifiedModel() }
                val data = android.os.Bundle().apply {
                    putInt(
                        AssistProtocol.KEY_ERROR,
                        if (model == null) AssistProtocol.ERROR_NO_MODEL else AssistProtocol.ERROR_NONE,
                    )
                    putString(AssistProtocol.KEY_MODEL_NAME, model?.displayName)
                    // Absent until a model has been loaded.
                    val current = handle
                    if (current != 0L && AssistNative.nativeIsLoaded(current)) {
                        val ratio = AssistNative.nativeCharsPerToken(current)
                        if (ratio > 0f) {
                            putFloat(AssistProtocol.KEY_CHARS_PER_TOKEN, ratio)
                        }
                    }
                }
                send(reply, AssistProtocol.MSG_STATUS, data)
            } catch (error: Throwable) {
                send(
                    reply, AssistProtocol.MSG_STATUS,
                    android.os.Bundle().apply { putInt(AssistProtocol.KEY_ERROR, AssistProtocol.ERROR_FAILED) },
                )
            }
        }
    }

    private fun handleRun(message: Message) {
        val reply = message.replyTo ?: return
        val data = message.data ?: return
        val requestId = data.getInt(AssistProtocol.KEY_REQUEST_ID)
        val task = AssistTask.fromId(data.getInt(AssistProtocol.KEY_TASK))
        val text = data.getString(AssistProtocol.KEY_TEXT).orEmpty()
        val written = data.getString(AssistProtocol.KEY_INSTRUCTION).orEmpty()
        val continueJob = data.getBoolean(AssistProtocol.KEY_CONTINUE_JOB)

        if (task == null || text.isEmpty()) {
            replyWithError(reply, requestId, AssistProtocol.ERROR_FAILED)
            return
        }
        // The instruction is checked on this side as well.
        if (task == AssistTask.CUSTOM &&
            (written.isBlank() || written.length > AssistTask.MAX_INSTRUCTION_CHARS)
        ) {
            replyWithError(reply, requestId, AssistProtocol.ERROR_NO_INSTRUCTION)
            return
        }
        // Checked here as well as in the keyboard.
        if (text.length > AssistProtocol.MAX_SELECTION_CHARS) {
            replyWithError(reply, requestId, AssistProtocol.ERROR_TOO_LONG)
            return
        }

        workerHandler.post {
            // The whole request behind one try/catch, so the caller always gets a reply.
            try {
                handleRunLocked(reply, requestId, task, text, written, continueJob)
            } catch (error: Throwable) {
                replyWithError(reply, requestId, AssistProtocol.ERROR_FAILED)
            }
        }
    }

    private fun handleRunLocked(
        reply: Messenger,
        requestId: Int,
        task: AssistTask,
        text: String,
        written: String,
        continueJob: Boolean,
    ) {
        val current = handle
        if (current == 0L) {
            replyWithError(reply, requestId, AssistProtocol.ERROR_FAILED)
            return
        }

        // Read on every request, not only at load time.
        val preferences = DataGraph.themes.currentPreferences()

        // The model this task's category is pointed at (AssistCategory), or the active one when
        // that file is gone or its bytes no longer match.
        val preferredFile = when (task.category) {
            AssistCategory.TRANSLATE -> preferences.assistTranslateModel
            AssistCategory.WRITE -> preferences.assistWriteModel
        }
        val model = runBlocking {
            DataGraph.assistModels.verifiedModelByFileName(preferredFile)
                ?: DataGraph.assistModels.activeVerifiedModel()
        }
        if (model == null) {
            // Nothing imported, or the file no longer hashes to what it did; Settings shows which.
            replyWithError(reply, requestId, AssistProtocol.ERROR_NO_MODEL)
            return
        }
        // Loads only when the model in memory is not the one this task wants.
        if (!AssistNative.nativeIsLoaded(current) || loadedModelFile != model.fileName) {
            if (AssistNative.nativeIsLoaded(current)) {
                AssistNative.nativeUnload(current)
            }
            val path = DataGraph.assistModels.fileFor(model).absolutePath
            val status = AssistNative.nativeLoad(
                current, path, model.contextTokens, inferenceThreads(),
            )
            if (status != 0) {
                loadedModelFile = null
                loadedModelName = null
                replyWithError(reply, requestId, AssistProtocol.ERROR_LOAD_FAILED)
                return
            }
            loadedModelFile = model.fileName
            loadedModelName = model.displayName
        }

        // Rebuilds the sampler chain only, never the model.
        AssistNative.nativeSetSamplingParams(
            current, preferences.assistTemperature, preferences.assistTopP,
        )

        val status = IntArray(1)
        val truncatedOut = BooleanArray(1)
        // A custom instruction is wrapped here, in this build's constant; every other task
        // carries its whole instruction.
        val instruction = if (task == AssistTask.CUSTOM) {
            AssistTask.customInstruction(written)
        } else {
            task.instruction
        }
        // Off for a custom instruction.
        val cleanFormatting = task != AssistTask.CUSTOM
        // UTF-8 bytes, not strings, across the boundary in both directions: JNI's modified UTF-8
        // is not UTF-8 for a supplementary character.
        val answer = AssistNative.nativeRun(
            current, instruction.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_8),
            task.outputRatio, task.minOutputTokens,
            AssistTask.MAX_OUTPUT_TOKENS, task.usesRemainingContext, continueJob,
            cleanFormatting, status, truncatedOut,
        )?.toString(Charsets.UTF_8)
        if (answer == null) {
            replyWithError(reply, requestId, mapNativeStatus(status[0]))
            return
        }
        val payload = android.os.Bundle().apply {
            putInt(AssistProtocol.KEY_REQUEST_ID, requestId)
            putInt(AssistProtocol.KEY_ERROR, AssistProtocol.ERROR_NONE)
            // Already cleaned and trimmed by TextAssist::run's cleanResult.
            putString(AssistProtocol.KEY_RESULT, answer)
            putString(AssistProtocol.KEY_MODEL_NAME, loadedModelName)
            putBoolean(AssistProtocol.KEY_TRUNCATED, truncatedOut[0])
        }
        send(reply, AssistProtocol.MSG_RESULT, payload)
    }

    private fun replyWithError(reply: Messenger, requestId: Int, error: Int) {
        send(
            reply, AssistProtocol.MSG_RESULT,
            android.os.Bundle().apply {
                putInt(AssistProtocol.KEY_REQUEST_ID, requestId)
                putInt(AssistProtocol.KEY_ERROR, error)
            },
        )
    }

    private fun send(reply: Messenger, what: Int, data: android.os.Bundle) {
        val message = Message.obtain(null, what).apply { this.data = data }
        // The keyboard may be gone by now; a failed send is ignored.
        runCatching { reply.send(message) }
    }

    private fun mapNativeStatus(status: Int): Int = when (status) {
        NATIVE_ERR_NO_MODEL -> AssistProtocol.ERROR_NO_MODEL
        NATIVE_ERR_TOO_LONG -> AssistProtocol.ERROR_TOO_LONG
        NATIVE_ERR_BUSY -> AssistProtocol.ERROR_BUSY
        else -> AssistProtocol.ERROR_FAILED
    }

    /** Half the available cores, at least two, at most four. */
    private fun inferenceThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private companion object {
        const val IDLE_TIMEOUT_MILLIS = 90_000L

        // Mirrors TextAssist::Status in text_assist.hpp; NATIVE_ERR_EXCEPTION maps to
        // ERROR_FAILED through mapNativeStatus's `else`.
        const val NATIVE_ERR_NO_MODEL = -1
        const val NATIVE_ERR_TOO_LONG = -4
        const val NATIVE_ERR_BUSY = -7
        const val NATIVE_ERR_EXCEPTION = -9
    }
}
