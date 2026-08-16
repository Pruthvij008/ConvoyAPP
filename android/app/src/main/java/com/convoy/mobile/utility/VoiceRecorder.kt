package com.convoy.mobile.utility

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Hold-to-record voice notes.
 *
 * A clip rather than a live walkie-talkie, and the reason is the road: a
 * recording that fails to upload can be retried, whereas a live stream in a
 * tunnel is simply gone. On a highway drive, "gone" happens often.
 *
 * AAC in an MP4 container because it is the one combination every Android
 * device since API 16 can both record and play, and it compresses speech
 * hard enough that a five-second clip is a few tens of kilobytes — which
 * matters on a patchy connection.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /**
     * Starts recording. Returns false if the microphone could not be opened
     * — another app may hold it, which is common with navigation running.
     */
    fun start(): Boolean {
        if (isRecording) return true

        val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")

        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Speech, not music. 32 kbps mono is clearly intelligible and
                // roughly a tenth the size of a default-quality recording.
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(22_050)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = rec
            outputFile = file
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording: ${e.message}")
            runCatching { file.delete() }
            recorder = null
            false
        }
    }

    /**
     * Stops and returns the clip, or null if it was too short to be a
     * message.
     *
     * The minimum exists because a tap that was meant as a tap — not a hold
     * — produces a 200 ms file of nothing, and sending those would fill the
     * chat with silence.
     */
    fun stop(): Recording? {
        val rec = recorder ?: return null
        val file = outputFile
        val durationMs = System.currentTimeMillis() - startedAt

        recorder = null
        outputFile = null

        try {
            rec.stop()
        } catch (e: Exception) {
            // stop() throws if it is called before any audio was captured.
            // The file is unusable in that case.
            Log.w(TAG, "Recorder stopped with no audio: ${e.message}")
            rec.release()
            file?.delete()
            return null
        }
        rec.release()

        if (file == null || !file.exists() || durationMs < MIN_DURATION_MS) {
            file?.delete()
            return null
        }

        return Recording(file = file, durationMs = durationMs)
    }

    /** Abandons the recording and deletes the file. */
    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        rec.release()
        outputFile?.delete()
        outputFile = null
    }

    data class Recording(val file: File, val durationMs: Long)

    companion object {
        /** Below this, it was a mis-tap rather than a message. */
        const val MIN_DURATION_MS = 700L

        /** Hard stop. A held button in a pocket should not record forever. */
        const val MAX_DURATION_MS = 60_000L

        private const val TAG = "VoiceRecorder"
    }
}

/**
 * Plays voice notes, one at a time.
 *
 * Single-instance on purpose: two clips talking over each other is worse
 * than either alone, and in a car it is genuinely confusing.
 */
class VoicePlayer {

    private var player: MediaPlayer? = null
    private var playingUrl: String? = null

    val nowPlaying: String? get() = playingUrl

    fun play(url: String, onFinished: () -> Unit) {
        stop()
        try {
            player = MediaPlayer().apply {
                setDataSource(url)
                setOnCompletionListener {
                    playingUrl = null
                    onFinished()
                    release()
                    player = null
                }
                setOnErrorListener { _, _, _ ->
                    playingUrl = null
                    onFinished()
                    true
                }
                prepareAsync()
                setOnPreparedListener { it.start() }
            }
            playingUrl = url
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed: ${e.message}")
            playingUrl = null
            onFinished()
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        playingUrl = null
    }

    private companion object {
        const val TAG = "VoicePlayer"
    }
}
