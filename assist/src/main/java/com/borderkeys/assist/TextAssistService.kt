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
 * The text assistant, in its own process.
 *
 * The process boundary is the whole design, not an implementation detail. A quantised model is
 * hundreds of megabytes resident. Loading one into the keyboard would make the process that
 * every other application types through into the largest thing on the device, and Android's
 * memory killer sorts by exactly that -- the keyboard would be reclaimed while backgrounded and
 * pay a cold start on the next tap in any text field. Here the model is loaded when the user
 * asks for something, released after ninety seconds of silence, and the process can be killed
 * at any moment without the keyboard noticing.
 *
 * It has no queue and no session. One request, one answer, and the answer is shown to the user
 * with a button next to it. Nothing is inserted anywhere unless they press it.
 *
 * Nothing here reaches the network, because nothing in this application can. The model is a file
 * the user chose, verified against a known hash before it was mapped.
 */
class TextAssistService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var handle = 0L
    private var loadedModelName: String? = null

    /** The file name of the model currently mapped, so a task that wants a different one for its
     *  category knows to switch and a task that wants the same one does not reload it. */
    private var loadedModelFile: String? = null

    /**
     * Unloads the model after a period of silence, then stops the process.
     *
     * Ninety seconds is long enough that a user reading a summary and then asking for a rewrite
     * does not pay a second load, and short enough that a model does not sit in memory because
     * somebody used the feature once this morning.
     */
    private val idleRunnable = Runnable { releaseAndStop() }

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            AssistProtocol.MSG_RUN -> {
                restartIdleTimer()
                handleRun(message)
                true
            }
            AssistProtocol.MSG_CANCEL -> {
                // The user closed the sheet. The generation stops at the next token rather than
                // running to completion for an answer nobody will see.
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
        // Below default priority on purpose: generation is a long CPU burn in a background
        // process, and the foreground application's frames matter more than this finishing a
        // few hundred milliseconds sooner.
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
        // Posted through workerHandler, after nativeUnload, rather than written here directly:
        // handleRun reads loadedModelName from that same thread once its own work is done, and
        // Handler's FIFO ordering is what keeps this from landing on the main thread in the
        // middle of a request that is still running when the idle timer happens to fire.
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
            // Same reasoning as handleRun's try/catch: nothing else on this thread would catch
            // an exception thrown here, and letting one escape kills the process instead of just
            // this one status reply.
            try {
                val model = runBlocking { DataGraph.assistModels.activeVerifiedModel() }
                val data = android.os.Bundle().apply {
                    putInt(
                        AssistProtocol.KEY_ERROR,
                        if (model == null) AssistProtocol.ERROR_NO_MODEL else AssistProtocol.ERROR_NONE,
                    )
                    putString(AssistProtocol.KEY_MODEL_NAME, model?.displayName)
                    // Only meaningful once something is actually loaded to have measured it
                    // against -- a status query is exactly the availability check made before
                    // the first request of a session, when nothing has loaded yet, so this is
                    // routinely absent and that is the expected case, not a failure.
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
        // The instruction is checked on this side as well, for the reason the length below is:
        // a rule enforced by the asker holds only while the asker is the one it was written for.
        if (task == AssistTask.CUSTOM &&
            (written.isBlank() || written.length > AssistTask.MAX_INSTRUCTION_CHARS)
        ) {
            replyWithError(reply, requestId, AssistProtocol.ERROR_NO_INSTRUCTION)
            return
        }
        // Checked here as well as in the keyboard. The keyboard is the only caller today, but a
        // limit enforced on one side of a process boundary is a limit that holds only as long as
        // that side is the one asking.
        if (text.length > AssistProtocol.MAX_SELECTION_CHARS) {
            replyWithError(reply, requestId, AssistProtocol.ERROR_TOO_LONG)
            return
        }

        workerHandler.post {
            // The whole request behind one try/catch: an exception thrown here -- native or
            // otherwise -- reaches nothing that would otherwise catch it. This is a worker
            // thread's own message loop, not a coroutine with a dispatcher's failure handler
            // behind it and not the main thread with an Android crash reporter watching it;
            // letting it escape kills the whole :assist process before this reply is ever sent,
            // which leaves the caller's ChunkedAssistRunner job waiting forever rather than
            // learning that anything went wrong at all.
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

        // Read fresh on every request rather than only at load time: this process outlives
        // one settings change, and a slider dragged in Settings should be felt on the next
        // action, not only after the ninety-second idle timeout has thrown the model out and
        // something reloads it.
        val preferences = DataGraph.themes.currentPreferences()

        // Which model this task's category is pointed at, falling back to the active one --
        // see AssistCategory. A file it names but no longer has, or one whose bytes no
        // longer match, is ignored the same way.
        val preferredFile = when (task.category) {
            AssistCategory.TRANSLATE -> preferences.assistTranslateModel
            AssistCategory.WRITE -> preferences.assistWriteModel
        }
        val model = runBlocking {
            DataGraph.assistModels.verifiedModelByFileName(preferredFile)
                ?: DataGraph.assistModels.activeVerifiedModel()
        }
        if (model == null) {
            // Either nothing was imported, or the file no longer hashes to what it did. The
            // distinction is in the database, and Settings shows it.
            replyWithError(reply, requestId, AssistProtocol.ERROR_NO_MODEL)
            return
        }
        // A load only when the model in memory is not the one this task wants -- a category
        // switch pays a cold load, staying on one model does not.
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

        // Cheap regardless -- it only rebuilds the sampler chain, the same few hundred bytes
        // every time, never the model.
        AssistNative.nativeSetSamplingParams(
            current, preferences.assistTemperature, preferences.assistTopP,
        )

        val status = IntArray(1)
        val truncatedOut = BooleanArray(1)
        // Every task but one carries its whole instruction. The exception is assembled here
        // rather than sent whole, so what wraps the user's words is this build's constant
        // and not something the request could have replaced.
        val instruction = if (task == AssistTask.CUSTOM) {
            AssistTask.customInstruction(written)
        } else {
            task.instruction
        }
        // Off for a custom instruction: "wrap the answer in quotes" is a reasonable thing to
        // type there, and the native cleanup exists to remove formatting nobody asked for --
        // not formatting the request asked for by name. See AssistNative.nativeRun's own doc.
        val cleanFormatting = task != AssistTask.CUSTOM
        val answer = AssistNative.nativeRun(
            current, instruction, text, task.outputRatio, task.minOutputTokens,
            AssistTask.MAX_OUTPUT_TOKENS, task.usesRemainingContext, continueJob,
            cleanFormatting, status, truncatedOut,
        )
        if (answer == null) {
            replyWithError(reply, requestId, mapNativeStatus(status[0]))
            return
        }
        val payload = android.os.Bundle().apply {
            putInt(AssistProtocol.KEY_REQUEST_ID, requestId)
            putInt(AssistProtocol.KEY_ERROR, AssistProtocol.ERROR_NONE)
            // Already cleaned and trimmed on the native side (TextAssist::run's cleanResult)
            // -- the one place that turns a small model's raw generation into an answer, not
            // a second trim here repeating part of that job.
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
        // The keyboard can go away mid-request -- the user switched apps, or the IME was
        // restarted. A dead peer is the normal end of a request, not an error to report.
        runCatching { reply.send(message) }
    }

    private fun mapNativeStatus(status: Int): Int = when (status) {
        NATIVE_ERR_NO_MODEL -> AssistProtocol.ERROR_NO_MODEL
        NATIVE_ERR_TOO_LONG -> AssistProtocol.ERROR_TOO_LONG
        NATIVE_ERR_BUSY -> AssistProtocol.ERROR_BUSY
        else -> AssistProtocol.ERROR_FAILED
    }

    /**
     * Half the available cores, at least two, at most four.
     *
     * All of them would be wrong: this runs in the background while the user is looking at
     * another application, and saturating every core to finish a summary a second earlier costs
     * that application its frames and the device its battery.
     */
    private fun inferenceThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private companion object {
        const val IDLE_TIMEOUT_MILLIS = 90_000L

        // Mirrors TextAssist::Status in text_assist.hpp. NATIVE_ERR_EXCEPTION falls through
        // mapNativeStatus's own `else` to the same AssistProtocol.ERROR_FAILED every other
        // unlisted status already does -- named here only so a reader matching this file against
        // that enum finds every value accounted for, not because the mapping needs its own line.
        const val NATIVE_ERR_NO_MODEL = -1
        const val NATIVE_ERR_TOO_LONG = -4
        const val NATIVE_ERR_BUSY = -7
        const val NATIVE_ERR_EXCEPTION = -9
    }
}
