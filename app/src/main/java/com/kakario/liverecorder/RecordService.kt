package com.kakario.liverecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 녹화는 이 서비스에서 돈다. 알림창에 '녹화 중'이 떠 있는 동안은
 * 화면을 끄거나 다른 앱으로 나가도 계속 저장된다.
 */
class RecordService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        private const val CH = "rec"
        private const val NID = 7

        @Volatile var pending: Captured? = null
        @Volatile var pendingUa: String = ""
        @Volatile var running = false
        @Volatile var status = ""
        val log = ArrayList<String>()
        var onChange: (() -> Unit)? = null

        fun addLog(s: String) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            synchronized(log) {
                log.add("[$t] $s")
                if (log.size > 200) log.removeAt(0)
            }
            onChange?.invoke()
        }

        fun logText(): String = synchronized(log) { log.joinToString("\n") }

        fun start(ctx: Context, c: Captured, ua: String) {
            pending = c; pendingUa = ua
            ctx.startForegroundService(Intent(ctx, RecordService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile private var stopFlag = false
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var startedAt = 0L
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String, ongoing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "녹화", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (ongoing) "녹화 중" else "라이브 녹화")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 1,
                Intent(this, RecordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "녹화 중지", stop).build())
        }
        return b.build()
    }

    private fun notifyText(text: String, ongoing: Boolean = true) {
        getSystemService(NotificationManager::class.java).notify(NID, notification(text, ongoing))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            addLog("중지 요청")
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        val src = pending
        val n = notification("준비 중…", true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }
        if (src == null || running) return START_NOT_STICKY
        pending = null
        running = true
        stopFlag = false
        startedAt = System.currentTimeMillis()

        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liverec:rec").apply { acquire(24 * 3600 * 1000L) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "liverec:wifi").apply { acquire() }

        Thread { record(src) }.start()
        return START_NOT_STICKY
    }

    private fun record(src: Captured) {
        var uri: Uri? = null
        var fileName = ""
        addLog("주소: ${maskUrl(src.url)}")
        val rec = HlsRecorder(
            src, pendingUa,
            log = { addLog(it) },
            stopped = { stopFlag },
            openOut = { ext ->
                val host = hostOf(src.pageUrl).removePrefix("www.").replace(Regex("[^A-Za-z0-9.-]"), "_")
                val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
                fileName = "${host}_$time.$ext"
                val (u, out) = Saver.create(this, fileName, if (ext == "mp4") "video/mp4" else "video/mp2t")
                uri = u
                out
            },
            progress = { bytes, segs ->
                val now = System.currentTimeMillis()
                if (now - lastNotify > 3000) {
                    lastNotify = now
                    val min = (now - startedAt) / 60000
                    status = "녹화 중 · ${min}분 · ${bytes / 1024 / 1024}MB"
                    notifyText(status)
                    onChange?.invoke()
                }
            },
        )
        try {
            rec.run()
            addLog("녹화 끝: ${rec.endReason}")
        } catch (e: DrmException) {
            addLog(e.message ?: "DRM")
        } catch (e: Exception) {
            addLog("녹화 실패: ${e.javaClass.simpleName} ${e.message}")
        }
        uri?.let { Saver.finish(this, it) }
        val mb = rec.bytes / 1024 / 1024
        status = if (rec.bytes > 0) "저장됨: ${Saver.FOLDER}/$fileName (${mb}MB)" else "저장된 영상 없음 — 진단 저장을 눌러 보내 주세요"
        addLog(status)
        running = false
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_DETACH)
        notifyText(status, false)
        onChange?.invoke()
        stopSelf()
    }
}
