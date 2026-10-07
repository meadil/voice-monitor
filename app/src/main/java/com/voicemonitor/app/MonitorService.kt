package com.voicemonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps the microphone alive while the screen is off and the user walks around,
 * which is the entire point of this app.
 */
class MonitorService : Service() {

    private var engine: AudioEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mode: MonitorMode = MonitorMode.AUTO
    private var muted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                muted = !muted
                engine?.setMuted(muted)
                updateNotification()
                return START_STICKY
            }
        }

        mode = runCatching { MonitorMode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: "AUTO") }
            .getOrDefault(MonitorMode.AUTO)
        muted = intent?.getBooleanExtra(EXTRA_MUTED, false) ?: false

        // Must start foreground within a few seconds or the system kills us.
        startForeground(NOTIF_ID, buildNotification())

        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voice-monitor:monitor")
                .apply { acquire() }
        }

        if (engine == null) {
            lastState = MonitorBus.state
            engine = AudioEngine(this).also { e ->
                e.setListener(object : AudioEngine.Listener {
                    override fun onState(s: AudioEngine.State) {
                        lastState = s
                        MonitorBus.publish(s)
                        // Rebuilding a notification is expensive enough that doing it
                        // at audio rate visibly starves playback. Only refresh it when
                        // something the user would actually read has changed.
                        val key = "${s.running}|${s.muted}|${s.mode}|${s.error != null}|" +
                                "${(s.bufferLatencyMs / 10).toInt()}|${s.underruns / 25}"
                        if (key != lastNotifKey) {
                            lastNotifKey = key
                            updateNotification()
                        }
                    }
                })
                e.start(mode)
                e.setMuted(muted)
                MonitorBus.shadow = e.shadow
            }
        }
        return START_STICKY
    }

    private var lastState = AudioEngine.State()
    private var lastNotifKey = ""

    private fun stopEverything() {
        MonitorBus.publish(AudioEngine.State())
        engine?.setListener(null)
        engine?.stop()
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.setListener(null)
        engine?.stop()
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = getSystemService(NotificationManager::class.java)
        if (m.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Voice Monitor", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the microphone monitor running while you practise"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        m.createNotificationChannel(ch)
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mute = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val status = when {
            lastState.error != null -> lastState.error
            !lastState.running -> "Starting…"
            lastState.muted -> "Muted — still listening"
            else -> "${lastState.mode} · ${"%.0f".format(lastState.bufferLatencyMs)} ms buffer · ${lastState.underruns} gaps"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (lastState.muted) "Voice Monitor (muted)" else "Voice Monitor")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, if (lastState.muted) "Unmute" else "Mute", mute)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "voice_monitor"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.voicemonitor.app.STOP"
        const val ACTION_MUTE = "com.voicemonitor.app.MUTE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MUTED = "muted"

        fun start(ctx: Context, mode: MonitorMode, muted: Boolean) {
            val i = Intent(ctx, MonitorService::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_MUTED, muted)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }

        fun toggleMute(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_MUTE))
        }
    }
}