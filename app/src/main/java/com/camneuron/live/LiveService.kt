package com.camneuron.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.camneuron.core.camera.CameraEngine
import com.camneuron.live.rtmp.RtmpUrl

/**
 * 방송하는 동안 켜 두는 포그라운드 서비스. 화면을 끄거나 다른 앱으로 가도 카메라·마이크·네트워크가 계속 돈다.
 * 설정(서버 주소, 스트림 키)은 [LivePrefs]에서 읽는다.
 */
class LiveService : Service() {
    private var session: LiveSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLive()
            ACTION_STOP -> stopLive()
        }
        return START_NOT_STICKY
    }

    private fun startLive() {
        if (session != null) return
        val url = RtmpUrl.parse(LivePrefs.serverUrl(this))
        val key = LivePrefs.streamKey(this)
        if (url == null || key.isEmpty()) {
            LiveState.update { LiveState.Status(message = getString(R.string.error_settings)) }
            stopSelf()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), foregroundTypes())
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (e: Exception) {
            // 앱이 화면에 없을 때 시작하면 Android 14부터 카메라 유형을 거부한다.
            Log.w(TAG, "startForeground failed", e)
            LiveState.update { LiveState.Status(message = getString(R.string.error_foreground)) }
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camneuron-live:stream")
            .apply { acquire() }
        session = LiveSession(applicationContext, CameraEngine.get(this), url, key, LivePrefs.quality(this)) {
            // 처음 연결부터 실패 (이유는 세션이 LiveState에 남긴다). 세션 스레드에서 오므로 메인 스레드에서 정리한다.
            main.post { stopLive() }
        }.also { it.start() }
    }

    private fun stopLive() {
        val running = session
        session = null
        // 다음 방송이 가린 화면으로 시작하지 않게 한다. 마이크 끄기는 버튼에 보이므로 그대로 둔다.
        LiveControls.setCovered(false)
        // 세션은 연결을 닫고 인코더를 떼는 데 시간이 걸릴 수 있어 메인 스레드를 막지 않게 따로 멈춘다.
        if (running != null) Thread({ running.stop() }, "live-stop").start()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session?.let { s -> Thread({ s.stop() }, "live-stop").start() }
        session = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.btn_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LiveService"
        private const val CHANNEL_ID = "live"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "com.camneuron.live.START"
        private const val ACTION_STOP = "com.camneuron.live.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LiveService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveService::class.java).setAction(ACTION_STOP))
        }
    }
}
