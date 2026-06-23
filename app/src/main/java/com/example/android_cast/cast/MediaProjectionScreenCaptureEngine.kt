package com.example.android_cast.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.ResultReceiver
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Real ScreenCaptureEngine: MediaProjection → VirtualDisplay surface → hardware
 * MediaCodec H.264 encoder. Output NALs are passed to a downstream RTP packetizer
 * via [onAccessUnit].
 *
 * Construction requires a result Intent from [MediaProjectionManager.getScreenCaptureIntent]
 * which the Activity side obtains through the user-granted permission flow.
 *
 * Cannot be exercised in unit tests — verified manually on a device.
 */
class MediaProjectionScreenCaptureEngine(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent,
    private var width: Int = 1920,
    private var height: Int = 1080,
    private val fps: Int = 30,
    private var bitrate: Int = 8_000_000,
    /** Called for every access unit (one IDR or P frame's NALs). Wire to RTP packetizer. */
    private val onAccessUnit: (List<ByteArray>, presentationTimeUs: Long) -> Unit,
) : ScreenCaptureEngine {

    enum class Resolution(val w: Int, val h: Int, val defaultBitrate: Int) {
        R1080(1920, 1080, 8_000_000),
        R720(1280, 720, 4_000_000),
        R480(854, 480, 1_500_000),
    }

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    @Volatile private var running: Boolean = false

    /** Mid-session resolution change. Reconfigures encoder + virtual display without
     *  tearing down the projection. */
    fun setResolution(res: Resolution) {
        width = res.w
        height = res.h
        bitrate = res.defaultBitrate
        // Trigger an IDR on next frame by requesting a key frame on the running codec.
        // Re-creating the encoder is the most reliable path on stock OEMs.
        running = false
        encoder?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        encoder = null
        virtualDisplay?.release()
        virtualDisplay = null
        running = true
        // The session's drain loop will re-arm the encoder on its next iteration
        // via [ensureEncoderStarted]; see start() for the actual creation flow.
        ensureEncoderStarted()
    }

    private fun ensureEncoderStarted() {
        if (encoder != null) return
        // Mirrors the createEncoder() logic in start() but applied after resolution change.
        val (enc, surface) = createEncoder()
        encoder = enc
        val proj = projection ?: return
        virtualDisplay = proj.createVirtualDisplay(
            "MirrorCast", width, height, dpi(), 0, surface, null, null,
        )
        enc.start()
    }

    override suspend fun start(receiver: Receiver): Flow<CastState> = flow {
        require(!running) { "engine already running" }
        running = true
        emit(CastState.Connecting)

        val projection = createProjection()
        val (encoder, inputSurface) = createEncoder()
        this@MediaProjectionScreenCaptureEngine.projection = projection
        this@MediaProjectionScreenCaptureEngine.encoder = encoder

        android.util.Log.i("MirrorCast", "creating VirtualDisplay ${width}x${height}")
        val virtualDisplay = projection.createVirtualDisplay(
            "MirrorCast",
            width, height, dpi(),
            0,
            inputSurface,
            null, null,
        ) ?: run {
            android.util.Log.e("MirrorCast", "createVirtualDisplay returned null")
            emit(CastState.Failed("createVirtualDisplay returned null"))
            return@flow
        }
        android.util.Log.i("MirrorCast", "VirtualDisplay created, starting encoder")
        encoder.start()

        emit(CastState.Streaming(receiver = receiver, fps = fps))

        try {
            drainEncoderLoop(encoder)
        } catch (t: Throwable) {
            android.util.Log.e("MirrorCast", "drainEncoderLoop threw", t)
        }
        android.util.Log.i("MirrorCast", "drain loop exited, releasing")

        virtualDisplay.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()
        projection.stop()

        emit(CastState.Idle)
    }.flowOn(Dispatchers.Default)

    override fun stop() {
        running = false
    }

    private fun createProjection(): MediaProjection {
        val mgr = context.getSystemService<MediaProjectionManager>()
            ?: error("MediaProjectionManager unavailable")
        return mgr.getMediaProjection(resultCode, resultData)
            ?: error("getMediaProjection returned null")
    }

    private fun createEncoder(): Pair<MediaCodec, Surface> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        return encoder to inputSurface
    }

    private fun drainEncoderLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var emittedFrames = 0
        var firstFrameLogged = false
        var spsPpsSent = false
        while (running) {
            val outIndex = encoder.dequeueOutputBuffer(info, 10_000L)
            when {
                outIndex >= 0 -> {
                    val buf = encoder.getOutputBuffer(outIndex) ?: continue
                    val nals = extractNalUnits(buf, info)
                    if (!firstFrameLogged) {
                        android.util.Log.i("MirrorCast",
                            "first encoder output: size=${info.size} nals=${nals.size} flags=0x${Integer.toHexString(info.flags)}")
                        firstFrameLogged = true
                    }
                    if (nals.isNotEmpty()) {
                        emittedFrames++
                        // On the first output, prepend SPS+PPS so the receiver can
                        // initialize its decoder before any slice NAL arrives.
                        val toSend = if (!spsPpsSent) {
                            val params = extractSpsPps(encoder.outputFormat)
                            spsPpsSent = true
                            if (params.isNotEmpty()) {
                                android.util.Log.i("MirrorCast",
                                    "prepending SPS+PPS: ${params.map { it.size }}")
                                params + nals
                            } else nals
                        } else nals
                        onAccessUnit(toSend, info.presentationTimeUs)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = encoder.outputFormat
                    android.util.Log.i("MirrorCast", "encoder format: $fmt")
                    // Emit SPS/PPS immediately on format change so the receiver
                    // can init the decoder even before the first slice arrives.
                    val params = extractSpsPps(fmt)
                    if (params.isNotEmpty() && !spsPpsSent) {
                        spsPpsSent = true
                        android.util.Log.i("MirrorCast",
                            "format-change: sending SPS+PPS: ${params.map { it.size }}")
                        onAccessUnit(params, 0L)
                    }
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Thread.sleep(5)
            }
        }
        android.util.Log.i("MirrorCast", "drain loop ended after $emittedFrames frames")
    }

    /** Pulls SPS (csd-0) and PPS (csd-1) from the encoder's output format.
     *  CSD buffers come Annex-B framed: `00 00 00 01 <NAL>`. We strip the start
     *  code and emit each NAL separately so the RTP packetizer can wrap them. */
    private fun extractSpsPps(format: MediaFormat): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (key in listOf("csd-0", "csd-1")) {
            val bb = format.getByteBuffer(key) ?: continue
            bb.rewind()
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            // Split on Annex-B start codes (00 00 00 01 or 00 00 01).
            var i = 0
            while (i < bytes.size) {
                // Find next start code at position i.
                val scLen = when {
                    i + 4 <= bytes.size &&
                        bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() &&
                        bytes[i+2] == 0.toByte() && bytes[i+3] == 1.toByte() -> 4
                    i + 3 <= bytes.size &&
                        bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() &&
                        bytes[i+2] == 1.toByte() -> 3
                    else -> { i += 1; continue }
                }
                val nalStart = i + scLen
                // Find next start code or end.
                var j = nalStart + 1
                while (j < bytes.size) {
                    if (j + 4 <= bytes.size &&
                        bytes[j] == 0.toByte() && bytes[j+1] == 0.toByte() &&
                        bytes[j+2] == 0.toByte() && bytes[j+3] == 1.toByte()) break
                    if (j + 3 <= bytes.size &&
                        bytes[j] == 0.toByte() && bytes[j+1] == 0.toByte() &&
                        bytes[j+2] == 1.toByte()) break
                    j += 1
                }
                if (j > nalStart) {
                    out.add(bytes.copyOfRange(nalStart, j))
                }
                i = j
            }
        }
        return out
    }

    /** Splits encoder output into individual NAL byte arrays. Handles both
     *  length-prefixed (AVCC) and start-code-prefixed (Annex-B) framing. */
    private fun extractNalUnits(buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo): List<ByteArray> {
        val results = mutableListOf<ByteArray>()
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)

        // Detect framing from first 4 bytes.
        val isAnnexB = bytes.size >= 4 &&
            ((bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) ||
             (bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()))

        if (isAnnexB) {
            var i = 0
            while (i < bytes.size) {
                val scLen = when {
                    i + 4 <= bytes.size &&
                        bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() &&
                        bytes[i+2] == 0.toByte() && bytes[i+3] == 1.toByte() -> 4
                    i + 3 <= bytes.size &&
                        bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() &&
                        bytes[i+2] == 1.toByte() -> 3
                    else -> { i += 1; continue }
                }
                val nalStart = i + scLen
                var j = nalStart + 1
                while (j < bytes.size) {
                    if (j + 4 <= bytes.size &&
                        bytes[j] == 0.toByte() && bytes[j+1] == 0.toByte() &&
                        bytes[j+2] == 0.toByte() && bytes[j+3] == 1.toByte()) break
                    if (j + 3 <= bytes.size &&
                        bytes[j] == 0.toByte() && bytes[j+1] == 0.toByte() &&
                        bytes[j+2] == 1.toByte()) break
                    j += 1
                }
                if (j > nalStart) results.add(bytes.copyOfRange(nalStart, j))
                i = j
            }
        } else {
            // AVCC: length-prefixed NALs in big-endian uint32.
            val tmp = java.nio.ByteBuffer.wrap(bytes)
            while (tmp.remaining() >= 4) {
                val len = tmp.int
                if (len <= 0 || len > tmp.remaining()) break
                val nal = ByteArray(len)
                tmp.get(nal)
                results.add(nal)
            }
        }
        return results
    }

    private fun dpi(): Int {
        val wm = context.getSystemService<WindowManager>() ?: return 1
        return wm.defaultDisplay?.let { DisplayMetrics().also { d -> it.getRealMetrics(d) }.densityDpi } ?: 1
    }
}
