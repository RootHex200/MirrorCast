package com.example.android_cast.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service of type `mediaProjection` that owns the live cast session.
 * Survives screen lock + app backgrounding; persistent notification lets the user
 * tap to stop casting.
 *
 * The Activity that obtained the MediaProjection permission result intent passes
 * it into the service via [startWith].
 */
class CastForegroundService : Service() {

    private var engine: MediaProjectionScreenCaptureEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCasting()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val receiver = intent.getParcelableExtra<ReceiverExtra>(EXTRA_RECEIVER)?.toReceiver()
                    ?: return START_NOT_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_INTENT)
                    ?: return START_NOT_STICKY

                startForegroundWithNotification()
                startCasting(receiver, resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        ensureChannel()
        val stopIntent = Intent(this, CastForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MirrorCast")
            .setContentText("Casting your screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "Stop casting", pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startCasting(receiver: Receiver, resultCode: Int, resultData: Intent) {
        android.util.Log.i("MirrorCast", "startCasting: host=${receiver.host}:${receiver.port}")
        Thread {
            // Mac receiver from issue #1 supports RTSP-over-TCP with interleaved RTP.
            val transport = SocketRtspTransport(host = receiver.host, port = receiver.port)
            val rtsp = RtspClient(transport)
            try {
                android.util.Log.i("MirrorCast", "connecting TCP to ${receiver.host}:${receiver.port}")
                rtsp.connect()
                android.util.Log.i("MirrorCast", "TCP connected, sending OPTIONS")

                rtsp.announce(SelfDescribingSdp(receiver.host).sdp())
                android.util.Log.i("MirrorCast", "ANNOUNCE sent")

                val channel = rtsp.setup()
                android.util.Log.i("MirrorCast", "SETUP done, channel=$channel")

                rtsp.record()
                android.util.Log.i("MirrorCast", "RECORD sent, starting encoder")

                // Batched sender: each access unit's RTP packets are 4-byte-interleaved
                // and accumulated, then flushed to the socket once at AU-end. Was: one
                // write+flush per RTP packet (100+ syscalls/sec at 1080p/30fps/multi-slice).
                var framesSent = 0
                val batcher = AccessUnitBatcher(transport = object : AccessUnitBatcher.Transport {
                    override fun writeAndFlush(bytes: ByteArray) {
                        try {
                            transport.writeRaw(bytes)
                            framesSent++
                            if (framesSent % 30 == 1) {
                                android.util.Log.i("MirrorCast", "flushed $framesSent AUs")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MirrorCast", "write failed after $framesSent AUs", e)
                            throw e
                        }
                    }
                })
                // Packetizer for the queued path: its sendAccessUnit calls go through the
                // AuFramedSender (the batcher adapter), which the queue supplies.
                val packetizer = RtpPacketizer(sender = RtpPacketizer.Sender { _, _ -> /* unused: AU-aware overload is called */ })
                val queue = AccessUnitQueue(batcher)

                val eng = MediaProjectionScreenCaptureEngine(
                    context = applicationContext,
                    resultCode = resultCode,
                    resultData = resultData,
                    onAccessUnit = { nals, pts ->
                        try {
                            // Enqueue is non-blocking: parameter-set AUs bypass synchronously,
                            // slice AUs go through the channel with DROP_OLDEST backpressure.
                            queue.enqueue(AccessUnit(nals, pts), packetizer, ptsUs = pts)
                        } catch (e: Exception) {
                            android.util.Log.e("MirrorCast", "enqueue failed (nals=${nals.size} pts=$pts)", e)
                        }
                    },
                )
                engine = eng
                android.util.Log.i("MirrorCast", "starting MediaProjection encoder")
                kotlinx.coroutines.runBlocking {
                    // Consumer coroutine on Dispatchers.IO drains the queue and forwards
                    // each AU to the batcher. Structured concurrency: when the engine flow
                    // completes or throws, the runBlocking scope cancels the consumer too.
                    coroutineScope {
                        val consumer = launch(Dispatchers.IO) {
                            try {
                                queue.consume()
                            } catch (e: Exception) {
                                android.util.Log.e("MirrorCast", "consumer ended", e)
                            }
                        }
                        try {
                            eng.start(receiver).collect { state ->
                                android.util.Log.i("MirrorCast", "engine state: $state")
                            }
                        } finally {
                            queue.close()
                            consumer.cancel()
                        }
                    }
                }
                android.util.Log.i("MirrorCast", "engine flow completed")
            } catch (t: Throwable) {
                android.util.Log.e("MirrorCast", "cast failed", t)
            }
        }.apply { isDaemon = true; name = "cast-engine" }.start()
    }

    private fun stopCasting() {
        engine?.stop()
        engine = null
    }

    private fun ensureChannel() {
        val mgr = getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MirrorCast",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Screen cast in progress"
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "cast"
        private const val NOTIF_ID = 42
        const val ACTION_START = "com.example.android_cast.START"
        const val ACTION_STOP = "com.example.android_cast.STOP"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_INTENT = "resultIntent"

        fun startWith(
            context: Context,
            receiver: Receiver,
            resultCode: Int,
            resultData: Intent,
        ) {
            val intent = Intent(context, CastForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RECEIVER, ReceiverExtra.from(receiver))
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_INTENT, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CastForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}

/**
 * Parcelable wrapper so [Receiver] can ride inside a service Intent without coupling
 * the data class itself to Android parcelability.
 */
@kotlinx.parcelize.Parcelize
data class ReceiverExtra(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val paired: Boolean = false,
) : android.os.Parcelable {
    fun toReceiver(): Receiver = Receiver(id, name, host, port, paired)

    companion object {
        fun from(r: Receiver) = ReceiverExtra(r.id, r.name, r.host, r.port, r.paired)
    }
}
