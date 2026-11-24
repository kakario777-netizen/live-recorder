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
        /** 개수 제한은 없음(속도 한도로만 제한). 폰 보호용 안전 상한만 둠 */
        const val MAX_SLOTS = 30
        /** 전체 받기 속도 한도: 초당 7.5MB */
        const val MAX_RATE = 7_500_000L

        /** 새 방송 녹화를 시작해도 되는지: 전체 속도 7.5MB/s 안에 여유가 있으면 (한 개 약 0.6MB/s 예상) */
        fun canStart(s: Slot): Boolean {
            val others = list().filter { it !== s }
            if (others.count { it.isDownloading } >= MAX_SLOTS) return false
            return others.sumOf { it.rateBps() } + 600_000 <= MAX_RATE
        }

        fun totalRate(): Long = list().sumOf { it.rateBps() }
        /** 상태 표시용: "받는 중 3개 · 2.4/7.5MB/s" */
        fun rateText(): String = String.format("%.1f/%.1fMB/s", totalRate() / 1_000_000.0, MAX_RATE / 1_000_000.0)
        const val ACTION_STOP_ALL = "stop_all"
        const val ACTION_ADD = "add"
        const val ACTION_STOP_ONE = "stop_one"
        const val ACTION_RESUME = "resume"
        const val ACTION_TIDY = "tidy"
        const val ACTION_UPLOAD = "upload"
        const val ACTION_SYNC = "sync"
        const val ACTION_WATCH = "watch"

        /** 자동 녹화: 방송이 켜진 걸 확인했을 때 녹화 칸 만들기 (끝나면 칸이 스스로 빠짐) */
        fun addWatch(ctx: Context, pageUrl: String, name: String) {
            val ua = try { android.webkit.WebSettings.getDefaultUserAgent(ctx) } catch (e: Exception) { "" }
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_ADD)
                .putExtra("url", pageUrl).putExtra("ua", ua).putExtra("name", name).putExtra("watch", true))
        }

        /** 자동 녹화가 켜진 즐겨찾기가 있으면 지켜보기 시작 */
        fun startWatch(ctx: Context) {
            if (!Watcher.any(ctx)) return
            try { ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_WATCH)) } catch (_: Exception) {}
        }

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
        var instance: RecordService? = null; private set

        val running: Boolean get() = synchronized(slots) { slots.isNotEmpty() }

        fun addLog(s: String) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            synchronized(log) {
                log.add("[$t] $s")
                if (log.size > 300) log.removeAt(0)
            }
            saveLogLine(s)
            onChange?.invoke()
        }

        // ---------- 오래 남는 기록 (앱이 꺼져도 남음, 약 3MB까지) ----------
        @Volatile var logFile: java.io.File? = null
        private val logFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
        private fun saveLogLine(s: String) {
            val f = logFile ?: Lab.appCtx?.let { java.io.File(it.filesDir, "record_log.txt") }?.also { logFile = it } ?: return
            try {
                synchronized(logFmt) {
                    f.appendText("[" + logFmt.format(Date()) + "] " + s.replace('\n', ' ') + "\n")
                    if (f.length() > 3_000_000) {
                        val keep = f.readText().takeLast(1_500_000)
                        f.writeText(keep.substringAfter('\n'))
                    }
                }
            } catch (_: Exception) {}
        }

        /** 최근 몇 시간 기록 (파일에서) */
        fun recentLog(hours: Int): String {
            val f = logFile ?: return ""
            if (!f.exists()) return ""
            val cut = System.currentTimeMillis() - hours * 3600_000L
            val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val fmt = SimpleDateFormat("yyyy MM-dd HH:mm:ss", Locale.KOREA)
            val lines = try { f.readLines() } catch (e: Exception) { return "" }
            val i = lines.indexOfFirst { l ->
                val t = try { fmt.parse("$y " + l.substring(1, 15))?.time } catch (e: Exception) { null }
                t != null && t >= cut && t <= System.currentTimeMillis() + 3600_000L
            }
            return if (i < 0) "" else lines.drop(i).joinToString("\n")
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

        /** 지금 방송 칸이 붙잡고 있는 파일 → 상태 글 (녹화 중 / 이어받기 대기) */
        fun fileStates(): Map<String, String> = list().filter { !it.isEnded() }.flatMap { it.fileStates() }.toMap()
        fun isActiveFile(name: String) = fileStates().containsKey(name)

        fun resume(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(ACTION_RESUME))
        }

        // ----- 이어서 녹화할 목록 저장 -----
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)

        /** 저장된 '진행 중이던 녹화' (앱·폰이 꺼지기 전) */
        class Job(val url: String, val ua: String, val name: String, val path: String?, val watch: Boolean = false)

        fun savedJobs(ctx: Context): List<Job> = try {
            val a = JSONArray(prefs(ctx).getString("activeJobs", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Job(o.getString("url"), o.optString("ua"), o.optString("name"), o.optString("path").ifEmpty { null }, o.optBoolean("watch")) }
        } catch (e: Exception) { emptyList() }

        /** 생방 대기로 다시 시작할 때 이어 붙일 파일 (10분 안에 쓴 것만) */
        fun rememberResume(ctx: Context, url: String, path: String) =
            ctx.getSharedPreferences("resumeMap", Context.MODE_PRIVATE).edit().putString(url, path).apply()
        fun resumeFor(ctx: Context, url: String): String? {
            val p = ctx.getSharedPreferences("resumeMap", Context.MODE_PRIVATE).getString(url, null) ?: return null
            val f = java.io.File(p)
            return if (f.exists() && System.currentTimeMillis() - f.lastModified() < 10 * 60_000L) p else null
        }

        fun clearSaved(ctx: Context) = prefs(ctx).edit().remove("activeJobs").apply()

        private fun saveJobs(ctx: Context) {
            val a = JSONArray()
            list().filter { !it.userStopped }.forEach { a.put(JSONObject().put("url", it.pageUrl).put("ua", it.ua).put("name", it.fixedName).put("path", it.currentPath ?: "").put("watch", it.watch)) }
            prefs(ctx).edit().putString("activeJobs", a.toString()).apply()
        }

        /** 녹화 파일을 새로 열었을 때: 이어 녹화용으로 경로 저장 */
        fun fileOpened() { instance?.let { saveJobs(it) } }

        /** 업데이트 직후: 묻지 않고 바로 재개 */
        fun resumeAfterUpdate(ctx: Context) {
            val p = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
            val work = (p.getString("tidyQueue", "[]") ?: "[]").length > 2 || (ctx.getSharedPreferences("ftp", Context.MODE_PRIVATE).getString("queue", "[]") ?: "[]").length > 2
            // 즐겨찾기 자동 대기만 있어도(녹화 목록이 비어 있어도) 다시 켜야 대기·정리·업로드가 이어짐
            if (savedJobs(ctx).isEmpty() && !Watcher.any(ctx) && !work && !CloudBm.recordHere(ctx)) return
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
        Tidy.init(this)
        Lab.appCtx = applicationContext
        CloudBm.startLoop(this)
        logFile = java.io.File(filesDir, "record_log.txt")
        try { Lab.loadKeys(this) } catch (_: Exception) {}
        if (Watcher.any(this)) Watcher.start(this)
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
            ?: Tidy.waitingCount().takeIf { it > 0 }?.let { "충전 대기 ${it}개 (충전기를 꽂으면 H.265 변환 후 업로드)" }
        val head = if (l.isNotEmpty()) "받는 중 ${l.count { it.isDownloading }}개 · ${rateText()} · 등록 ${l.size}개" else null
        status = (listOfNotNull(head) + l.map { "${it.id}. ${it.streamer.ifEmpty { it.host }} — ${it.summary()}" } + listOfNotNull(tidyLine, upLine)).joinToString("\n")
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
            override fun run() { lastNotify = 0; updateNotification(); sweepTick(); if (running || Tidy.busy() || Uploader.busy() || Sync.isRecorder(this@RecordService)) ticker.postDelayed(this, 3000) }
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

    /** 다시 켜질 때: 녹화하던 방송(이어 쓸 파일 있음)을 먼저 바로 열고, 방송 대기는 3초 간격으로 천천히 열기 */
    /** 이번 업데이트에서 한 번만: 등록돼 있던 것 전부(받는 중이던 것 포함) → 즐겨찾기 + 생방 대기 */
    private fun migrateOnce(jobs: List<Job>): List<Job> {
        if (BuildConfig.LAB) return jobs
        val mp = getSharedPreferences("main", MODE_PRIVATE)
        if (mp.getBoolean("migratedToWatch", false)) return jobs
        mp.edit().putBoolean("migratedToWatch", true).apply()
        if (jobs.isEmpty()) return jobs
        val have = Bookmarks.list(this).map { it.url }.toSet()
        jobs.forEach { j -> if (j.url !in have) Bookmarks.add(this, j.name.ifEmpty { Names.streamerFromUrl(j.url) }, j.url) }
        Bookmarks.setWatch(this, jobs.map { it.url }, true)
        jobs.forEach { j -> j.path?.let { rememberResume(this, j.url, it) } }
        addLog("등록돼 있던 ${jobs.size}개를 모두 즐겨찾기 '생방 대기'로 옮김 → 녹화 목록은 비우고, 방송 중인 건 곧바로 직접 받기로 이어 받음")
        return jobs.map { Job(it.url, it.ua, it.name, it.path, watch = true) }
    }

    private fun resumeAll(jobs0: List<Job>) {
        val jobs = migrateOnce(jobs0)
        if (jobs.any { it.watch }) Watcher.start(this)
        val fresh = jobs.filter { j -> j.path?.let { java.io.File(it) }?.let { it.exists() && System.currentTimeMillis() - it.lastModified() < 10 * 60_000L } == true }
        val rest = jobs - fresh.toSet()
        if (fresh.isNotEmpty()) addLog("녹화하던 ${fresh.size}개 먼저 이어 받기 시작, 방송 대기 ${rest.size}개는 차례로")
        fresh.forEach { addSlot(it.url, it.ua, it.name, it.path, watch = it.watch) }
        // 자동 녹화 칸이었는데 이어 쓸 파일이 없으면 다시 만들지 않음 (지켜보기가 알아서 다시 시작)
        rest.filter { !it.watch }.forEachIndexed { i, it -> addSlot(it.url, it.ua, it.name, it.path, startDelay = 5_000L + i * 3_000L) }
    }

    private var lastSweep = 0L
    /** 5분마다: 아무도 손대지 않은 녹화 파일(오류 등으로 남은 것)을 찾아 자동 정리 */
    private fun sweepTick() {
        val now = System.currentTimeMillis()
        if (now - lastSweep < 5 * 60_000L) return
        lastSweep = now
        Thread { try { Tidy.sweep(this) } catch (_: Exception) {} }.start()
    }

    private fun addSlot(url: String, ua: String, name: String, resumePath: String? = null, startDelay: Long = 0, watch: Boolean = false): Boolean {
        synchronized(slots) {
            if (slots.size >= 300) { addLog("녹화 칸은 최대 300개까지예요"); return false }
            if (slots.any { it.pageUrl == url }) { if (!watch) addLog("이미 녹화 중인 방송이에요"); return false }
            val s = Slot(this, nextId++, url, ua, name, resumePath, watch) { updateNotification() }
            slots.add(s)
            s.start(startDelay)
        }
        saveJobs(this)
        addLog("녹화 추가: ${maskUrl(url)}")
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shuttingDown = false
        try { Uploader.cleanTrash(this) } catch (_: Exception) {}
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
                val u = intent.getStringExtra("url") ?: return START_STICKY
                val w = intent.getBooleanExtra("watch", false)
                addSlot(u, intent.getStringExtra("ua") ?: "", intent.getStringExtra("name") ?: "",
                    resumePath = if (w) resumeFor(this, u) else null, watch = w)
            }
            ACTION_WATCH -> {
                ensureForegroundQuiet()
                Watcher.start(this)
                return START_STICKY
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
                Tidy.enqueue(this, Tidy.Job(name, Tidy.pathFor(name), uri, Names.streamerFromFileName(name)))
                return START_STICKY
            }
            ACTION_RESUME -> {
                ensureForeground()
                when (intent.getStringExtra("why")) {
                    "update" -> addLog("업데이트 완료 → 녹화 자동 재개")
                    "crash" -> addLog("앱이 오류로 꺼졌다가 스스로 다시 켜짐 → 녹화 자동 재개 (" +
                        getSharedPreferences("main", MODE_PRIVATE).getString("lastCrash", "") + ")")
                }
                resumeAll(savedJobs(this))
            }
            null -> {
                // 시스템이 꺼진 서비스를 다시 살림 → 직접 끈 게 아니므로 이어서 녹화
                ensureForeground()
                val jobs = savedJobs(this)
                if (jobs.isNotEmpty()) addLog("앱이 다시 살아남 → 녹화 ${jobs.size}개 이어서 진행")
                resumeAll(jobs)
            }
        }
        Uploader.kick(this)
        startSyncLoop()
        if (!running && !Tidy.busy() && !Uploader.busy() && intent?.action != ACTION_ADD) finishService()
        return START_STICKY
    }

    fun finishService() {
        // 녹화 담당 폰은 녹화가 없어도 원격 명령을 받으려고 계속 켜 둠
        if ((Sync.isRecorder(this) || Watcher.any(this)) && !shuttingDown) {
            if (Watcher.any(this)) Watcher.start(this)
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
