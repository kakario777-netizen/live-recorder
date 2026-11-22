package com.kakario.liverecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 녹화 서비스 — 녹화 칸(Slot)을 최대 3개 돌린다.
 * 알림창에 '녹화 중'이 떠 있는 동안은 앱을 내리거나 화면을 꺼도 계속 녹화된다.
 * 진행 중인 녹화 목록은 저장해 두고, 앱이 강제로 꺼졌다 살아나면 이어서 녹화한다.
 */
class RecordService : Service() {

    companion object {
        const val MAX_SLOTS = 10
        const val ACTION_STOP_ALL = "stop_all"
        const val ACTION_ADD = "add"
        const val ACTION_STOP_ONE = "stop_one"
        const val ACTION_RESUME = "resume"
        const val ACTION_TIDY = "tidy"
        const val ACTION_UPLOAD = "upload"
        const val ACTION_SYNC = "sync"

        /** 남은 업로드 처리 시작 (설정 저장 후, 앱 켤 때 등) */
        fun startUploads(ctx: Context) {
            if (!FtpConfig.ready(ctx) || Uploader.pendingCount(ctx) == 0) return
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_UPLOAD))
        }

        fun uploadOne(ctx: Context, name: String, path: String, streamer: String) {
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_UPLOAD)
                .putExtra("name", name).putExtra("path", path).putExtra("streamer", streamer))
        }
        private const val CH = "rec"
        private const val NID = 7

        val slots = ArrayList<Slot>()
        private var nextId = 1
        @Volatile var status = ""
        val log = ArrayList<String>()
        var onChange: (() -> Unit)? = null
        private var instance: RecordService? = null

        val running: Boolean get() = synchronized(slots) { slots.isNotEmpty() }

        fun addLog(s: String) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            synchronized(log) {
                log.add("[$t] $s")
                if (log.size > 300) log.removeAt(0)
            }
            onChange?.invoke()
        }

        fun logText(): String = synchronized(log) { log.joinToString("\n") }

        fun list(): List<Slot> = synchronized(slots) { slots.toList() }

        fun add(ctx: Context, pageUrl: String, ua: String, name: String) {
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_ADD)
                .putExtra("url", pageUrl).putExtra("ua", ua).putExtra("name", name))
        }

        fun stopOne(ctx: Context, id: Int) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP_ONE).putExtra("id", id))
        }

        fun stopAll(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP_ALL))
        }

        /** 파일 목록에서 직접 '정리' 요청 */
        fun tidy(ctx: Context, name: String, uri: android.net.Uri) {
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_TIDY)
                .putExtra("name", name).putExtra("uri", uri.toString()))
        }

        /** 녹화 중인 파일 이름들 (파일 목록에서 '녹화 중' 표시용) */
        fun recordingNames(): Set<String> = list().map { it.fileName }.filter { it.isNotEmpty() }.toSet()

        fun resume(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_RESUME))
        }

        // ----- 이어서 녹화할 목록 저장 -----
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)

        /** 저장된 '진행 중이던 녹화' (앱·폰이 꺼지기 전) */
        class Job(val url: String, val ua: String, val name: String, val path: String?)

        fun savedJobs(ctx: Context): List<Job> = try {
            val a = JSONArray(prefs(ctx).getString("activeJobs", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Job(o.getString("url"), o.optString("ua"), o.optString("name"), o.optString("path").ifEmpty { null }) }
        } catch (e: Exception) { emptyList() }

        fun clearSaved(ctx: Context) = prefs(ctx).edit().remove("activeJobs").apply()

        private fun saveJobs(ctx: Context) {
            val a = JSONArray()
            list().filter { !it.userStopped }.forEach { a.put(JSONObject().put("url", it.pageUrl).put("ua", it.ua).put("name", it.fixedName).put("path", it.currentPath ?: "")) }
            prefs(ctx).edit().putString("activeJobs", a.toString()).apply()
        }

        /** 녹화 파일을 새로 열었을 때: 이어 녹화용으로 경로 저장 */
        fun fileOpened() { instance?.let { saveJobs(it) } }

        /** 업데이트 직후: 묻지 않고 바로 재개 */
        fun resumeAfterUpdate(ctx: Context) {
            if (savedJobs(ctx).isEmpty()) return
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_RESUME).putExtra("why", "update"))
        }

        fun slotEnded(s: Slot) {
            val svc = instance
            synchronized(slots) { slots.remove(s) }
            if (svc != null) {
                if (!svc.shuttingDown) saveJobs(svc)
                svc.updateNotification()
                if (!running) svc.finishService()
            }
            onChange?.invoke()
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastNotify = 0L
    @Volatile var shuttingDown = false
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    private fun notification(text: String, ongoing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "녹화", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = list().size
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (ongoing) "녹화 ${n}개" else "라이브 녹화")
            .setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 1,
                Intent(this, RecordService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "모두 중지", stop).build())
        }
        return b.build()
    }

    fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1500) return
        lastNotify = now
        val l = list()
        val tidyLine = Tidy.current?.let { "정리 중… ${Tidy.percent}% — ${it.name}" }
        val upLine = Uploader.current?.let { "업로드 중… ${Uploader.percent}% — ${it.name}" }
        status = (l.map { "${it.id}. ${it.host} — ${it.summary()}" } + listOfNotNull(tidyLine, upLine)).joinToString("\n")
        if (l.isEmpty() && !Tidy.busy() && !Uploader.busy() && Sync.isRecorder(this)) status = "녹화 담당 폰 · 원격 명령 대기 중"
        if (l.isNotEmpty() || Tidy.busy() || Uploader.busy() || Sync.isRecorder(this)) getSystemService(NotificationManager::class.java).notify(NID, notification(status, true))
        onChange?.invoke()
    }

    /** 담당 폰 대기 상태 알림 */
    private fun ensureForegroundQuiet() {
        val n = notification("녹화 담당 폰 · 원격 명령 대기 중", true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
        startSyncLoop()
    }

    private fun ensureForeground() {
        val n = notification("준비 중…", true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liverec:rec").apply { acquire(24 * 3600 * 1000L) }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "liverec:wifi").apply { acquire() }
        }
        if (netCb == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ticker.postDelayed({ list().forEach { it.onNetworkChanged() } }, 1500)
                }
            }
            try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb); netCb = cb } catch (_: Exception) {}
        }
        // 3초마다 알림 갱신
        ticker.removeCallbacksAndMessages(null)
        ticker.post(object : Runnable {
            override fun run() { lastNotify = 0; updateNotification(); if (running || Tidy.busy() || Uploader.busy() || Sync.isRecorder(this@RecordService)) ticker.postDelayed(this, 3000) }
        })
    }

    private var syncThread: Thread? = null

    /** 녹화 담당 폰: 15초마다 원격 명령 확인, 30초마다 현황 올리기, 5분마다 즐겨찾기·설정 맞추기 */
    private fun startSyncLoop() {
        if (!Sync.isRecorder(this)) return
        if (syncThread?.isAlive == true) return
        syncThread = Thread({
            var n = 0
            while (Sync.isRecorder(this) && !shuttingDown) {
                try {
                    Sync.pollCommands(this)
                    if (n % 2 == 0) Sync.pushStatus(this)
                    if (n % 20 == 0) Sync.syncData(this)
                } catch (_: Exception) {}
                n++
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
            }
        }, "sync").also { it.start() }
    }

    private fun addSlot(url: String, ua: String, name: String, resumePath: String? = null): Boolean {
        synchronized(slots) {
            if (slots.size >= MAX_SLOTS) { addLog("동시 녹화는 최대 ${MAX_SLOTS}개예요"); return false }
            if (slots.any { it.pageUrl == url }) { addLog("이미 녹화 중인 방송이에요"); return false }
            val s = Slot(this, nextId++, url, ua, name, resumePath) { updateNotification() }
            slots.add(s)
            s.start()
        }
        saveJobs(this)
        addLog("녹화 추가: ${maskUrl(url)}")
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shuttingDown = false
        try { Uploader.cleanTrash() } catch (_: Exception) {}
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                list().forEach { it.stop() }
                clearSaved(this)
                addLog("모두 중지")
                if (!running) finishService()
                return START_NOT_STICKY
            }
            ACTION_STOP_ONE -> {
                val id = intent.getIntExtra("id", -1)
                list().firstOrNull { it.id == id }?.stop()
                return START_STICKY
            }
            ACTION_ADD -> {
                ensureForeground()
                addSlot(intent.getStringExtra("url") ?: return START_STICKY, intent.getStringExtra("ua") ?: "", intent.getStringExtra("name") ?: "")
            }
            ACTION_SYNC -> {
                ensureForeground()
                startSyncLoop()
                return START_STICKY
            }
            ACTION_UPLOAD -> {
                ensureForeground()
                val name = intent.getStringExtra("name")
                val path = intent.getStringExtra("path")
                if (name != null && path != null) Uploader.enqueue(this, name, path, intent.getStringExtra("streamer") ?: "")
                else Uploader.kick(this)
                if (!Uploader.busy() && !running && !Tidy.busy()) finishService()
                return START_STICKY
            }
            ACTION_TIDY -> {
                ensureForeground()
                val name = intent.getStringExtra("name") ?: return START_STICKY
                val uri = intent.getStringExtra("uri")?.let { android.net.Uri.parse(it) }
                Tidy.enqueue(this, Tidy.Job(name, Tidy.pathFor(name), uri))
                return START_STICKY
            }
            ACTION_RESUME -> {
                ensureForeground()
                if (intent.getStringExtra("why") == "update") addLog("업데이트 완료 → 녹화 자동 재개")
                savedJobs(this).forEach { addSlot(it.url, it.ua, it.name, it.path) }
            }
            null -> {
                // 시스템이 꺼진 서비스를 다시 살림 → 직접 끈 게 아니므로 이어서 녹화
                ensureForeground()
                val jobs = savedJobs(this)
                if (jobs.isNotEmpty()) addLog("앱이 다시 살아남 → 녹화 ${jobs.size}개 이어서 진행")
                jobs.forEach { addSlot(it.url, it.ua, it.name, it.path) }
            }
        }
        Uploader.kick(this)
        startSyncLoop()
        if (!running && !Tidy.busy() && !Uploader.busy() && intent?.action != ACTION_ADD) finishService()
        return START_STICKY
    }

    fun finishService() {
        // 녹화 담당 폰은 녹화가 없어도 원격 명령을 받으려고 계속 켜 둠
        if (Sync.isRecorder(this) && !shuttingDown) {
            ensureForegroundQuiet()
            return
        }
        // 정리 중인 파일이 있으면 끝날 때까지 기다렸다가 종료
        if (Tidy.busy() || Uploader.busy()) {
            val again = { ticker.post { if (!running && !Tidy.busy() && !Uploader.busy()) finishService() } ; Unit }
            Tidy.onIdle = again
            Uploader.onIdle = again
            return
        }
        Tidy.onIdle = null
        Uploader.onIdle = null
        ticker.removeCallbacksAndMessages(null)
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        wake = null; wifi = null
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCb = null
        stopForeground(STOP_FOREGROUND_DETACH)
        status = "녹화 없음"
        getSystemService(NotificationManager::class.java).notify(NID, notification("녹화가 모두 끝났어요", false))
        onChange?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        // 시스템이 끄는 경우: 저장 목록은 남겨 두고(이어서 녹화), 파일만 안전하게 닫는다
        shuttingDown = true
        list().forEach { it.halt() }
        instance = null
        super.onDestroy()
    }
}
