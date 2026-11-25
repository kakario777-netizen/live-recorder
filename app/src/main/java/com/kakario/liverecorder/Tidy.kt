package com.kakario.liverecorder

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * 녹화 파일 정리 — 조각을 이어 붙인 파일에 '목차'를 붙여 일반 MP4로 만든다.
 * 다시 압축하지 않고 옮겨 담기만 해서(트랜스먹스) 화질·용량은 그대로이고 금방 끝난다.
 * 앱 전용 작업 공간에서 만든 뒤, 성공하면 원래 파일 자리에 완성본으로 바꿔 넣는다.
 */
object Tidy {
    class Job(val name: String, val path: String?, val uri: Uri?, val streamer: String = "", var force: Boolean = false, var fails: Int = 0)

    private val queue = ArrayDeque<Job>()
    /** H.264라 변환해야 하는데 충전 중이 아니어서 기다리는 파일 (업로드도 안 함) */
    private val waitCharge = ArrayList<Job>()
    /** H.265 변환이 2번 실패한 파일: 자동으로 다시 시도하지 않음 (H.264 그대로 보관, 업로드 안 함) */
    private val failed = ArrayList<Job>()
    @Volatile private var transformer: Transformer? = null
    @Volatile private var curOut: File? = null
    private var appCtx: Context? = null
    private var loaded = false
    @Volatile var current: Job? = null; private set
    @Volatile var percent = 0; private set
    @Volatile var lastResult = ""; private set
    var onChange: (() -> Unit)? = null
    var onIdle: (() -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val holder = ProgressHolder()
    /** 중복 시작 정보를 뺀 임시본 (정리가 실패해도 이걸로 원본을 바꿔 둠) */
    @Volatile private var dedupUsed: File? = null

    fun busy(): Boolean = current != null || synchronized(queue) { queue.isNotEmpty() || waitCharge.isNotEmpty() }

    /** 파일 이름의 녹화 시각(yyyyMMdd HHmmss)으로 오래된 것부터 */
    fun timeKey(name: String): String = Regex("""(\d{8}) (\d{6})""").find(name)?.let { it.groupValues[1] + it.groupValues[2] } ?: "99999999999999"
    /** 100개 넘게 밀리면 짧은(작은) 파일부터 — 개수를 먼저 줄임. 아니면 오래된 것부터 */
    const val MANY = 100
    private fun sortQueue() {
        val l = if (queue.size >= MANY) queue.sortedBy { j -> (j.path ?: pathFor(j.name))?.let { File(it).length() } ?: Long.MAX_VALUE }
                else queue.sortedBy { timeKey(it.name) }
        queue.clear(); queue.addAll(l)
    }
    /** 현황용: 대기 중인 이름들(오래된 순), 충전 대기 수 */
    fun queuedNames(): List<String> = synchronized(queue) { queue.map { it.name } }

    fun waitingCount(): Int = synchronized(queue) { waitCharge.size }

    private fun persist() {
        val ctx = appCtx ?: return
        val a = org.json.JSONArray()
        synchronized(queue) {
            (listOfNotNull(current) + queue + waitCharge).distinctBy { it.name }.forEach {
                a.put(org.json.JSONObject().put("name", it.name).put("path", it.path ?: "").put("uri", it.uri?.toString() ?: "").put("streamer", it.streamer).put("fails", it.fails))
            }
            failed.forEach {
                a.put(org.json.JSONObject().put("name", it.name).put("path", it.path ?: "").put("uri", it.uri?.toString() ?: "").put("streamer", it.streamer).put("fails", it.fails).put("failed", true))
            }
        }
        ctx.getSharedPreferences("main", Context.MODE_PRIVATE).edit().putString("tidyQueue", a.toString()).apply()
    }

    /** 서비스가 켜질 때: 충전 감지 시작 + 남아 있던 정리·변환 대기 불러오기 */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (appCtx != null) return
        appCtx = app
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
        }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                if (i.action == android.content.Intent.ACTION_POWER_CONNECTED) onPowerConnected(app) else onPowerDisconnected(app)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        ensureThread()
        if (!loaded) {
            loaded = true
            try {
                val a = org.json.JSONArray(app.getSharedPreferences("main", Context.MODE_PRIVATE).getString("tidyQueue", "[]"))
                for (k in 0 until a.length()) {
                    val o = a.getJSONObject(k)
                    val p = o.optString("path").ifEmpty { null }
                    if (p != null && !File(p).exists()) continue
                    val j = Job(o.getString("name"), p, o.optString("uri").ifEmpty { null }?.let { Uri.parse(it) }, o.optString("streamer"), fails = o.optInt("fails", 0))
                    if (o.optBoolean("failed")) synchronized(queue) { failed.add(j) } else enqueue(app, j)
                }
            } catch (_: Exception) {}
        }
    }

    private fun onPowerConnected(ctx: Context) {
        if (true) {   // 예전에 변환 실패로 보관만 하던 파일도 그대로 업로드
            val fl = synchronized(queue) { val l = failed.toList(); failed.clear(); l }
            fl.forEach { Uploader.enqueue(ctx, it.name, it.path ?: pathFor(it.name), it.streamer, allowH264 = true) }
        }
        val moved = synchronized(queue) { val l = waitCharge.toList(); waitCharge.clear(); queue.addAll(l); sortQueue(); l.size }
        if (moved > 0) {
            RecordService.addLog("충전 대기하던 ${moved}개 → 바로 처리")
            onChange?.invoke()
            ensureThread()
            handler?.post { if (current == null) next(ctx) }
        }
    }

    private fun onPowerDisconnected(ctx: Context) {
        val job = current ?: return
        return   // 충전기를 뽑아도 변환은 계속 (기다리지 않음)
        if (!converting || job.force) return
        if (charging(ctx)) return   // 충전기를 뽑아도 배터리 80% 이상이면 계속 변환
        RecordService.addLog("배터리 80% 아래 + 충전기 없음 → 변환 멈춤 (충전기를 꽂으면 처음부터 다시 변환): ${job.name}")
        handler?.post {
            try { transformer?.cancel() } catch (_: Exception) {}
            transformer = null
            curOut?.delete()
            dedupUsed?.delete(); dedupUsed = null
            synchronized(queue) { waitCharge.add(job) }
            current = null; converting = false
            persist()
            onChange?.invoke()
            next(ctx)
        }
    }

    private fun ensureThread() {
        if (thread == null) {
            thread = HandlerThread("tidy").also { it.start() }
            handler = Handler(thread!!.looper)
            handler?.postDelayed(chargeCheck, 60_000)
        }
    }

    /** 파일 목록 표시용: 정리 중이면 "정리 중… 37%", 대기 중이면 "정리 대기" */
    @Volatile var converting = false; private set
    @Volatile var convStartAt = 0L; private set
    @Volatile private var convBefore = 0L

    /** 설정 '변환 기록 남기기': 파일마다 크기·걸린 시간·속도를 기록 */
    fun convLogOn(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getBoolean("convLog", false)
    fun convLogFile(ctx: Context) = File(ctx.filesDir, "convert_log.txt")
    private fun logConv(ctx: Context, job: Job, ok: Boolean, err: String) {
        if (!convLogOn(ctx)) return
        try {
            val sec = ((System.currentTimeMillis() - convStartAt) / 1000).coerceAtLeast(1)
            val after = (job.path ?: pathFor(job.name))?.let { File(it).length() } ?: 0L
            val mb = convBefore / 1048576
            val line = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA).format(java.util.Date()) +
                " | " + (if (ok) "성공" else "실패($err)") + " | ${mb}MB → ${if (ok) after / 1048576 else 0}MB | ${sec / 60}분 ${sec % 60}초 | " +
                String.format("%.0f", mb * 60.0 / sec) + "MB/분 | 대기 ${synchronized(queue) { queue.size }}개 | ${job.name}\n"
            val f = convLogFile(ctx)
            if (f.length() > 1_000_000) f.writeText(f.readText().takeLast(500_000))
            f.appendText(line)
        } catch (_: Exception) {}
    }
    /** 앱이 직접 다시 쓰는 단계(시간 바로잡기) 중인지 */
    @Volatile private var remuxing = false

    /** H.264로 녹화된 파일은 정리하면서 H.265로 변환 (기본 켜짐, 충전 중일 때만) */
    /** H.265 변환 후 업로드 (설정, 기본 켜짐). 변환은 기다림 없이 바로 하고, 끝나면 곧바로 업로드 */
    fun autoH265(ctx: Context): Boolean = ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getBoolean("autoH265", true)

    /**
     * 변환해도 되는지: 배터리 80% 이상이거나 충전기가 꽂혀 있으면 OK.
     * (100% 완충·배터리 보호 85%면 꽂혀 있어도 '충전 중 아님'으로 나오는 문제 대비)
     */
    private fun charging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level < 0) 100 else level * 100 / scale
        return plugged || pct >= 80
    }

    /** 파일 목록 '지금 변환': 충전과 상관없이 바로 변환 */
    fun forceNow(ctx: Context, name: String) {
        val app = ctx.applicationContext
        val job = synchronized(queue) { waitCharge.firstOrNull { it.name == name }?.also { waitCharge.remove(it) } } ?: return
        job.force = true
        synchronized(queue) { queue.addFirst(job) }
        RecordService.addLog("지금 변환 (충전 상관없이): $name")
        persist(); onChange?.invoke()
        ensureThread()
        handler?.post { if (current == null) next(app) }
    }

    /** 변환 실패 파일 '다시 시도' */
    fun retry(ctx: Context, name: String) {
        val app = ctx.applicationContext
        val job = synchronized(queue) { failed.firstOrNull { it.name == name }?.also { failed.remove(it) } } ?: return
        job.fails = 0; job.force = true
        synchronized(queue) { queue.addFirst(job) }
        RecordService.addLog("변환 다시 시도: $name")
        persist(); onChange?.invoke()
        ensureThread()
        handler?.post { if (current == null) next(app) }
    }

    /** 변환 실패 파일 'H.264 그대로 업로드' (사용자가 직접 누른 경우만) */
    fun uploadAsIs(ctx: Context, name: String) {
        val job = synchronized(queue) { failed.firstOrNull { it.name == name }?.also { failed.remove(it) } } ?: return
        persist(); onChange?.invoke()
        RecordService.addLog("변환 없이 H.264 그대로 업로드 (직접 선택): $name")
        Uploader.enqueue(ctx, job.name, job.path ?: pathFor(job.name), job.streamer, allowH264 = true)
    }

    /** 남은 파일 자동 정리: 정리 안 됐고, 어디에도 안 걸려 있고, 15분 넘게 안 바뀐 녹화 파일 */
    fun sweep(ctx: Context) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Saver.DIR)
        val active = RecordService.fileStates().keys
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".mp4") }?.forEach { f ->
            val n = f.name
            if (n in active || isTidied(ctx, n) || statusOf(n) != null || Uploader.statusOf(n) != null) return@forEach
            if (now - f.lastModified() < 15 * 60_000L || f.length() == 0L) return@forEach
            RecordService.addLog("남아 있던 파일 자동 정리: $n")
            enqueue(ctx, Job(n, f.absolutePath, null, Names.streamerFromFileName(n)))
        }
    }

    /** 파일 목록 표시용: 정리 안 된 채 남은 파일이 곧 자동 정리되는지 */
    fun pendingSweep(f: Long): String =
        if (System.currentTimeMillis() - f < 15 * 60_000L) "녹화 끝 · 곧 자동 정리" else "자동 정리 대기"

    fun isFailed(name: String) = synchronized(queue) { failed.any { it.name == name } }

    /** 충전 대기 파일이 있으면 1분마다 충전기 확인 (꽂힌 채로 대기에 들어간 경우 대비) */
    private val chargeCheck = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            try { Uploader.cleanTrash(ctx) } catch (_: Exception) {}
            val ok = charging(ctx)
            if (synchronized(queue) { waitCharge.isNotEmpty() }) onPowerConnected(ctx)   // 충전 대기 없이 바로 처리
            // 변환 중인데 80% 아래로 떨어졌고 충전기도 없으면 멈춤
            if (!ok && converting && current?.force != true) onPowerDisconnected(ctx)
            handler?.postDelayed(this, 60_000)
        }
    }

    fun statusOf(name: String): String? = when {
        current?.name == name -> (if (converting && !remuxing) "H.265 변환 중… " else "정리 중… ") + "$percent%"
        synchronized(queue) { failed.any { it.name == name } } -> "변환 실패 (H.264 보관)"
        synchronized(queue) { waitCharge.any { it.name == name } } -> "충전 대기 (H.265 변환)"
        synchronized(queue) { queue.any { it.name == name } } -> appCtx?.let { if (isTidied(it, name)) "변환 대기 (차례대로)" else null } ?: "정리 대기"
        else -> null
    }

    fun isTidied(ctx: Context, name: String) =
        ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getStringSet("tidied", emptySet())!!.contains(name)

    fun markTidied(ctx: Context, name: String) {
        val p = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
        val set = HashSet(p.getStringSet("tidied", emptySet()) ?: emptySet())
        set.add(name)
        if (set.size > 2000) set.remove(set.first())
        p.edit().putStringSet("tidied", set).apply()
    }

    /** 녹화 폴더 안 실제 파일 경로(앱이 직접 만든 파일이면 있음) */
    fun pathFor(name: String): String? {
        val f = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Saver.DIR), name)
        return if (f.exists()) f.absolutePath else null
    }

    fun enqueue(ctx: Context, job: Job) {
        if (BuildConfig.LAB) return   // 실험판: 정리·변환 안 함 (받기만 시험)
        if (!job.name.lowercase().endsWith(".mp4")) {
            // .ts는 목차 없이도 잘 재생돼 정리 없이 바로 업로드
            Uploader.enqueue(ctx, job.name, job.path, job.streamer)
            return
        }
        val app = ctx.applicationContext
        if (appCtx == null) appCtx = app
        synchronized(queue) {
            if (current?.name == job.name || queue.any { it.name == job.name } || waitCharge.any { it.name == job.name }) return
            if (failed.any { it.name == job.name } && !job.force) return   // 변환 실패 파일은 자동으로 다시 넣지 않음
            queue.add(job)
            sortQueue()
        }
        persist()
        ensureThread()
        RecordService.addLog("정리 대기: ${job.name}")
        onChange?.invoke()
        handler?.post { if (current == null) next(app) }
    }

    /** 업로드가 끝나면 다음 변환을 시작하게 깨움 */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        handler?.post { if (current == null && synchronized(queue) { queue.isNotEmpty() }) next(app) }
    }

    /**
     * 순서: ① 정리(빠름)를 오래된 것부터 먼저 전부 → ② 변환은 오래된 것부터 한 파일씩.
     * 업로드가 쉬지 않게: 하나 올리는 동안 다음 하나를 변환해 두고, 변환본이 이미 1개 기다리면 그 이상은 앞서가지 않음.
     */
    private fun next(ctx: Context) {
        var waitUpload = false
        val job = synchronized(queue) {
            val j = queue.firstOrNull { !isTidied(ctx, it.name) }
                ?: queue.firstOrNull()
            j?.also { queue.remove(it) }
        }
        if (job == null) {
            current = null
            Awake.hold(ctx, "tidy", false)
            onChange?.invoke()
            if (!waitUpload) onIdle?.invoke()
            return
        }
        Awake.hold(ctx, "tidy", true)
        val tidied = isTidied(ctx, job.name)
        if (tidied && !job.force && !autoH265(ctx)) {
            // 이미 정리된 파일인데 변환은 꺼짐 → 바로 업로드로
            Uploader.enqueue(ctx, job.name, job.path ?: pathFor(job.name), job.streamer, allowH264 = true)
            persist(); onChange?.invoke()
            handler?.post { next(ctx) }
            return
        }
        // 아직 방송 칸이 쓰고 있는(또는 이어 쓸) 파일이면 건드리지 않고 나중으로
        if (RecordService.isActiveFile(job.name)) {
            // 빼 두기만 함: 녹화가 끝나면 방송 칸이 다시 넣고, 아니면 자동 정리(5분마다)가 찾아서 넣음
            RecordService.addLog("아직 녹화·이어받기 중인 파일이라 지금은 정리 안 함 (끝나면 자동으로): ${job.name}")
            persist()
            handler?.post { next(ctx) }
            return
        }
        current = job
        percent = 0
        onChange?.invoke()
        val h = handler ?: return
        val workDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tidy").apply { mkdirs() }
        val out = File(workDir, job.name).also { it.delete() }
        val orig: Uri = job.path?.let { Uri.fromFile(File(it)) } ?: job.uri
            ?: run { finish(ctx, job, out, false, "원본을 찾을 수 없어요"); return }
        var input: Uri = orig
        // 먼저 중간에 중복으로 들어간 시작 정보를 빼 둔다 (이어 녹화·업데이트 지점에서 재생이 멈추던 문제)
        val dedup = File(workDir, "dedup_" + job.name)
        val jp = job.path
        if (jp != null && !tidied) {
            try {
                val n = Mp4.removeDuplicateInits(File(jp), dedup)
                if (n > 0) {
                    RecordService.addLog("중간에 중복된 시작 정보 ${n}개 제거: ${job.name}")
                    input = Uri.fromFile(dedup)
                    dedupUsed = dedup
                } else dedup.delete()
            } catch (e: Exception) { dedup.delete() }
        }
        // H.264면 정리하면서 H.265로 변환 (충전 중일 때만, 하드웨어 인코더 있을 때만)
        val mime = Codec.probeUri(ctx, input)
        converting = autoH265(ctx) && mime == android.media.MediaFormat.MIMETYPE_VIDEO_AVC && Codec.phoneHasHevcEncoder()
        if (false) {   // 충전 대기 없음: 바로 변환
            // H.264는 변환 없이 올리지 않음: 충전기를 꽂을 때까지 기다림
            converting = false
            dedupUsed?.delete(); dedupUsed = null
            synchronized(queue) { waitCharge.add(job) }
            RecordService.addLog("H.264인데 배터리 80% 아래·충전기 없음 → 충전 대기 (꽂거나 80% 넘으면 변환 후 업로드): ${job.name}")
            current = null
            persist()
            onChange?.invoke()
            h.post { next(ctx) }
            return
        }
        RecordService.addLog((if (converting) "정리 + H.265 변환 시작: " else "정리 시작: ") + job.name)
        // 1단계: 앱이 직접 다시 쓰기 — 이어 붙인 곳의 시간 값을 순서대로 바로잡음 (다시 압축 안 함)
        var srcFile: File? = dedupUsed ?: jp?.let { File(it) }
        var videoBps = 0L
        val rem = File(workDir, "remux_" + job.name).also { it.delete() }
        if (srcFile != null && !tidied) {
            remuxing = true
            val err = Remux.run(srcFile, rem) { p -> percent = p; onChange?.invoke() }
            remuxing = false
            if (err == null && rem.length() > 0) {
                dedupUsed?.delete()
                dedupUsed = rem          // 변환이 실패해도 이 정리본으로 원본을 바꿔 둠
                srcFile = rem
                input = Uri.fromFile(rem)
                if (Remux.lastVideoUs > 5_000_000) videoBps = Remux.lastVideoBytes * 8 * 1_000_000 / Remux.lastVideoUs
                if (!converting) {
                    // 변환이 필요 없으면 이걸로 정리 끝
                    rem.renameTo(out)
                    dedupUsed = null
                    finish(ctx, job, out, true, "")
                    return
                }
                // 변환할 파일은 이어서 바로 변환 (끝나면 곧바로 업로드)
            } else {
                rem.delete()
                RecordService.addLog("직접 다시 쓰기 실패 → 기존 방식으로 정리: ${job.name} ($err)")
            }
        }
        percent = 0
        onChange?.invoke()
        val tb = Transformer.Builder(ctx).setLooper(h.looper)
        if (converting) {
            tb.setVideoMimeType(MimeTypes.VIDEO_H265)
            val src = srcFile?.length() ?: 0L
            val bitrate = if (videoBps > 0) {
                // 실제 영상 비트레이트의 55% (흐린 화면처럼 아주 낮으면 그만큼 낮게 → 원본보다 커지지 않게)
                (videoBps * 55 / 100).coerceIn(30_000, 8_000_000).toInt()
            } else try {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, input)
                    val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    if (ms > 5000 && src > 0) {
                        // 원본 영상 비트레이트의 55% (원본보다 커지지 않게, 최소 15만)
                        val srcV = (src * 8 * 1000 / ms - 160_000).coerceAtLeast(150_000)
                        (srcV * 55 / 100).coerceIn(150_000, minOf(srcV, 8_000_000)).toInt()
                    } else null
                } finally { r.release() }
            } catch (e: Exception) { null }
            val enc = DefaultEncoderFactory.Builder(ctx).setEnableFallback(true)
            if (bitrate != null) enc.setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            tb.setEncoderFactory(enc.build())
        }
        curOut = out
        val t = tb
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish(ctx, job, out, true, "")
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish(ctx, job, out, false, exportException.errorCodeName)
                }
            })
            .build()
        transformer = t
        try {
            t.start(MediaItem.fromUri(input), out.absolutePath)
        } catch (e: Exception) {
            finish(ctx, job, out, false, e.message ?: "시작 실패"); return
        }
        var lastP = -1; var lastMove = System.currentTimeMillis(); var offStall = 0L
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        convStartAt = System.currentTimeMillis()
        convBefore = srcFile?.length() ?: (job.path?.let { File(it).length() } ?: 0L)
        val poll = object : Runnable {
            override fun run() {
                if (current !== job) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    percent = holder.progress
                    onChange?.invoke()
                }
                val now = System.currentTimeMillis()
                if (percent != lastP) { lastP = percent; lastMove = now; offStall = 0L }
                else if (!pm.isInteractive) {
                    // 화면 꺼진 동안 멈춘 건 포기하지 않음 (켜지면 이어서). 3분 넘게 멈추면 이 폰 문제로 보고 안내
                    if (offStall == 0L) offStall = now
                    if (now - offStall > 3 * 60_000L) ScreenOff.warn(ctx)
                    lastMove = now
                }
                else if (now - lastMove > 5 * 60_000L) {
                    // 5분 동안 진행이 없으면 멈춘 것 → 변환 포기하고 그대로 업로드
                    RecordService.addLog("변환이 5분째 멈춤(${percent}%) → 변환 포기, 그대로 업로드: ${job.name}")
                    try { t.cancel() } catch (_: Exception) {}
                    finish(ctx, job, out, false, "멈춤")
                    return
                }
                h.postDelayed(this, 1000)
            }
        }
        h.postDelayed(poll, 1000)
    }

    private fun finish(ctx: Context, job: Job, out: File, ok: Boolean, err: String) {
        var msg: String
        if (ok && out.length() > 0) {
            msg = try {
                val before = job.path?.let { File(it).length() } ?: 0L
                replace(ctx, job, out, keepOriginalInTrash = converting)
                markTidied(ctx, job.name)
                if (converting) "정리 + H.265 변환 완료: ${job.name} (${before / 1024 / 1024}MB → ${out.length() / 1024 / 1024}MB, H.264 원본은 휴지통 ${Uploader.trashLabel(ctx)})"
                else "정리 완료: ${job.name}"
            } catch (e: Exception) {
                "정리본 저장 실패(원본 유지): ${e.message}"
            }
        } else {
            val d = dedupUsed
            msg = if (d != null && d.exists() && d.length() > 0) {
                try {
                    replace(ctx, job, d)
                    "정리는 실패했지만 중복된 시작 정보는 빼 뒀어요(끝까지 재생됨): ${job.name} $err"
                } catch (e: Exception) { "정리 실패(원본 그대로 둠): ${job.name} $err" }
            } else "정리 실패(원본 그대로 둠): ${job.name} $err"
        }
        dedupUsed?.delete(); dedupUsed = null
        out.delete()
        lastResult = msg
        RecordService.addLog(msg)
        val wasConverting = converting
        transformer = null; curOut = null
        if (wasConverting) logConv(ctx, job, ok && out.length() > 0, err)
        if (wasConverting && !ok) {
            // 변환 실패: 한 번은 줄 맨 뒤로 보내 다시, 두 번째도 실패하면 그대로(H.264) 업로드 — 붙잡고 있지 않게
            markTidied(ctx, job.name)
            job.fails++
            if (job.fails < 2) {
                synchronized(queue) { queue.addLast(job) }
                RecordService.addLog("변환 실패(1번째) → 줄 뒤로, 나중에 한 번 더: ${job.name}")
            } else {
                RecordService.addLog("변환 2번 실패 → 그대로 업로드: ${job.name}")
                Uploader.enqueue(ctx, job.name, job.path ?: pathFor(job.name), job.streamer, allowH264 = true)
            }
        } else {
            // 정리·변환이 끝난 파일 → FTP 자동 업로드
            Uploader.enqueue(ctx, job.name, job.path ?: pathFor(job.name), job.streamer)
        }
        current = null
        converting = false
        persist()
        onChange?.invoke()
        handler?.post { next(ctx) }
    }

    /** 원래 자리에 완성본으로 바꿔 넣기 */
    private fun replace(ctx: Context, job: Job, done: File, keepOriginalInTrash: Boolean = false) {
        val p = job.path
        if (p != null) {
            val target = File(p)
            val tmp = File(target.parentFile, ".tidy_" + target.name)
            done.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            if (keepOriginalInTrash) {
                // 변환 전 H.264 원본은 휴지통으로 (7일 보관)
                val trash = File(Uploader.trashDir(), target.nameWithoutExtension + " (H.264 원본)." + target.extension)
                if (trash.exists()) trash.delete()
                if (!target.renameTo(trash)) { tmp.delete(); throw Exception("원본을 휴지통으로 옮길 수 없어요") }
                trash.setLastModified(System.currentTimeMillis())
            } else if (!target.delete()) { tmp.delete(); throw Exception("원본을 지울 수 없어요") }
            if (!tmp.renameTo(target)) throw Exception("이름 바꾸기 실패")
            Saver.scan(ctx, target.absolutePath)
        } else {
            val u = job.uri ?: throw Exception("원본 없음")
            ctx.contentResolver.delete(u, null, null)
            val (nu, os) = Saver.create(ctx, job.name, "video/mp4")
            os.use { o -> done.inputStream().use { it.copyTo(o, 256 * 1024) } }
            Saver.finish(ctx, nu)
        }
    }
}
