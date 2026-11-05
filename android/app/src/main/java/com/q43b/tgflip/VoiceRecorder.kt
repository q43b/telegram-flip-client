package com.q43b.tgflip

import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// Records a voice message (F2 > Record voice message in a chat). A dialog shows the time
// while recording: OK = send, Back = throw it away. Records straight into OGG/Opus, the
// format Telegram keeps voice messages in, which Android can do from version 10.
// Recordings stop by themselves at MAX_SECONDS; OK still sends then.
// The microphone permission is asked for by the chat screen before start().
class VoiceRecorder(
    private val activity: AppCompatActivity,
    private val onRecorded: (file: File, seconds: Int) -> Unit,
) {
    private val file = File(activity.cacheDir, "recording.ogg")
    private var recorder: MediaRecorder? = null
    private var dialog: AlertDialog? = null
    private var ticker: Job? = null
    private var startedAt = 0L
    private var seconds = 0  // set when the recording stops

    val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun start() {
        if (recorder != null || dialog != null || !isSupported) return
        recorder = try {
            newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioChannels(1)
                setAudioSamplingRate(48000)
                setAudioEncodingBitRate(32000)  // what Telegram's own apps use, about 240 KB a minute
                setMaxDuration(MAX_SECONDS * 1000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording()
                }
                setOutputFile(file.path)
                prepare()
                start()
            }
        } catch (e: Exception) {  // the microphone is busy, or the phone can't record this format
            Toast.makeText(activity, R.string.voice_record_failed, Toast.LENGTH_LONG).show()
            return
        }
        startedAt = SystemClock.elapsedRealtime()
        seconds = 0
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.voice_recording_title)
            .setMessage(dialogText())
            .setPositiveButton(R.string.voice_send) { _, _ -> finish(send = true) }
            .setNegativeButton(R.string.voice_discard) { _, _ -> finish(send = false) }
            .setOnCancelListener { finish(send = false) }  // Back
            // OK sends, whichever button has focus. On key-up, like sending text.
            .setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                    return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_UP) finish(send = true)
                true
            }
            .show()
        ticker = activity.lifecycleScope.launch {
            while (true) {
                delay(1000)
                dialog?.setMessage(dialogText())
            }
        }
    }

    // Leaving the chat throws the recording away
    fun cancel() = finish(send = false)

    private fun finish(send: Boolean) {
        if (dialog == null) return
        val ok = stopRecording()
        ticker?.cancel()
        ticker = null
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        when {
            !send -> file.delete()
            !ok || seconds < 1 -> {
                file.delete()
                Toast.makeText(activity, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
            }
            else -> onRecorded(file, seconds)
        }
    }

    // false if nothing usable was recorded (the recorder throws when stopped too soon)
    private fun stopRecording(): Boolean {
        val r = recorder ?: return seconds > 0  // stopped already, at the time limit
        recorder = null
        seconds = ((SystemClock.elapsedRealtime() - startedAt + 500) / 1000).toInt()
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            seconds = 0
            false
        }
        r.release()
        dialog?.setMessage(dialogText())
        return ok
    }

    private fun dialogText(): String {
        if (recorder == null) return activity.getString(R.string.voice_limit_reached, MessageFormat.duration(seconds))
        val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        return activity.getString(R.string.voice_recording, MessageFormat.duration(elapsed))
    }

    @Suppress("DEPRECATION")  // the context version needs API 31; the phone has 29
    private fun newRecorder() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity) else MediaRecorder()

    private companion object {
        const val MAX_SECONDS = 5 * 60
    }
}
