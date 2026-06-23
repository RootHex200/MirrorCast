package com.example.android_cast.cast

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.getSystemService
import kotlin.concurrent.thread

/**
 * Captures system audio (or mic when system audio is unavailable on the OEM ROM)
 * and encodes it to AAC-LC frames for the audio RTP packetizer.
 *
 * AudioRecord's playback-capture path requires API 29+ and a MediaProjection callback
 * when capturing playback. Capturing MIC has no such restriction.
 */
class AudioCaptureEngine(
    private val context: Context,
    private val projection: MediaProjection? = null,
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 2,
    private val bitrate: Int = 128_000,
    private val onFrame: (ByteArray, presentationTimeUs: Long) -> Unit,
) {
    private var running: Boolean = false
    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null

    fun start() {
        require(!running) { "audio engine already running" }
        running = true
        thread(name = "cast-audio", isDaemon = true) { runLoop() }
    }

    fun stop() {
        running = false
    }

    private fun runLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = (minBuf.coerceAtLeast(8192) * 2)

        val config = audioConfig()
        val recorder = AudioRecord(
            config.first,
            sampleRate,
            if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        this.recorder = recorder
        recorder.startRecording()

        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferBytes)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        this.encoder = encoder

        val info = MediaCodec.BufferInfo()
        val pcmBuf = ByteArray(bufferBytes)
        while (running) {
            val read = recorder.read(pcmBuf, 0, pcmBuf.size)
            if (read <= 0) continue

            val inIdx = encoder.dequeueInputBuffer(10_000L)
            if (inIdx >= 0) {
                val ib = encoder.getInputBuffer(inIdx) ?: continue
                ib.clear()
                ib.put(pcmBuf, 0, read)
                val pts = System.nanoTime() / 1000L
                encoder.queueInputBuffer(inIdx, 0, read, pts, 0)
            }
            val outIdx = encoder.dequeueOutputBuffer(info, 0L)
            while (outIdx >= 0) {
                val out = encoder.getOutputBuffer(outIdx)
                if (out != null && info.size > 7) {
                    // Strip the 7-byte ADTS header MediaCodec adds; packetize raw AAC payload.
                    val payload = ByteArray(info.size - 7)
                    out.position(info.offset + 7)
                    out.get(payload)
                    onFrame(payload, info.presentationTimeUs)
                }
                encoder.releaseOutputBuffer(outIdx, false)
                if (!running) break
                // drain more
            }
        }

        recorder.stop()
        recorder.release()
        encoder.stop()
        encoder.release()
        this.recorder = null
        this.encoder = null
    }

    private fun audioConfig(): Pair<Int, Int> {
        // On Android 10+ prefer playback capture (system audio) when MediaProjection is given.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
            return Pair(MediaRecorderAudioSource.REMOTE_SUBMIX, 0)
        }
        return Pair(MediaRecorderAudioSource.MIC, 0)
    }

    /** AudioRecord source constants wrapper. */
    private object MediaRecorderAudioSource {
        const val MIC = android.media.MediaRecorder.AudioSource.MIC
        const val REMOTE_SUBMIX = android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX
    }

    private object Companion
}
