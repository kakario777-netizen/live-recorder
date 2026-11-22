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
    class Job(val name: String, val path: String?, val uri: Uri?, val streamer: String = "")

    private val queue = ArrayDeque<Job>()
    /** H.264라 변환해야 하는데 충전 중이 아니어서 기다리는 파일 (업로드도 안 함) */
    private val waitCharge = ArrayList<Job>()
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

    fun waitingCount(): Int = synchronized(queue) { waitCharge.size }

    private fun persist() {
        val ctx = appCtx ?: return
        val a = org.json.JSONArray()
        synchronized(queue) {
            (listOfNotNull(current) + queue + waitCharge).distinctBy { it.name }.forEach {
                a.put(org.json.JSONObject().put("name", it.name).put("path", it.path ?: "").put("uri", it.uri?.toString() ?: "").put("streamer", it.streamer))
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
        if (!loaded) {
            loaded = true
            try {
                val a = org.json.JSONArray(app.getSharedPreferences("main", Context.MODE_PRIVATE).getString("tidyQueue", "[]"))
                for (k in 0 until a.length()) {
                    val o = a.getJSONObject(k)
                    val p = o.optString("path").ifEmpty { null }
                    if (p != null && !File(p).exists()) continue
                    enqueue(app, Job(o.getString("name"), p, o.optString("uri").ifEmpty { null }?.let { Uri.parse(it) }, o.optString("streamer")))
                }
            } catch (_: Exception) {}
        }
    }

    private fun onPowerConnected(ctx: Context) {
        val moved = synchronized(queue) { val l = waitCharge.toList(); waitCharge.clear(); queue.addAll(l); l.size }
        if (moved > 0) {
            RecordService.addLog("충전기 연결됨 → H.265 변환 시작 (${moved}개)")
            onChange?.invoke()
            ensureThread()
            handler?.post { if (current == null) next(ctx) }
        }
    }

    private fun onPowerDisconnected(ctx: Context) {
        val job = current ?: return
        if (!converting) return
        RecordService.addLog("충전기가 빠져서 변환 멈춤 → 다시 꽂으면 처음부터 변환: ${job.name}")
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
        }
    }

    /** 파일 목록 표시용: 정리 중이면 "정리 중… 37%", 대기 중이면 "정리 대기" */
    @Volatile var converting = false; private set

    /** H.264로 녹화된 파일은 정리하면서 H.265로 변환 (기본 켜짐, 충전 중일 때만) */
    fun autoH265(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getBoolean("autoH265", true)

    private fun charging(ctx: Context): Boolean {
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        return bm?.isCharging == true
    }

    fun statusOf(name: String): String? = when {
        current?.name == name -> (if (converting) "H.265 변환 중… " else "정리 중… ") + "$percent%"
        synchronized(queue) { waitCharge.any { it.name == name } } -> "충전 대기 (H.265 변환)"
        synchronized(queue) { queue.any { it.name == name } } -> "정리 대기"
        else -> null
    }

    fun isTidied(ctx: Context, name: String) =
        ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getStringSet("tidied", emptySet())!!.contains(name)

    private fun markTidied(ctx: Context, name: String) {
        val p = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
        val set = HashSet(p.getStringSet("tidied", emptySet()) ?: emptySet())
        set.add(name)
        if (set.size > 2000) set.remove(set.first())
        p.edit().putStringSet("tidied", set).apply()
    }

    /** 녹화 폴더 안 실제 파일 경로(앱이 직접 만든 파일이면 있음) */
    fun pathFor(name: String): String? {
        val f = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LiveRecorder"), name)
        return if (f.exists()) f.absolutePath else null
    }

    fun enqueue(ctx: Context, job: Job) {
        if (!job.name.lowercase().endsWith(".mp4")) {
            // .ts는 목차 없이도 잘 재생돼 정리 없이 바로 업로드
            Uploader.enqueue(ctx, job.name, job.path, job.streamer)
            return
        }
        val app = ctx.applicationContext
        if (appCtx == null) appCtx = app
        synchronized(queue) {
            if (current?.name == job.name || queue.any { it.name == job.name } || waitCharge.any { it.name == job.name }) return
            queue.add(job)
        }
        persist()
        ensureThread()
        RecordService.addLog("정리 대기: ${job.name}")
        onChange?.invoke()
        handler?.post { if (current == null) next(app) }
    }

    private fun next(ctx: Context) {
        val job = synchronized(queue) { queue.removeFirstOrNull() }
        if (job == null) {
            current = null
            onChange?.invoke()
            onIdle?.invoke()
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
        if (jp != null) {
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
        if (converting && !charging(ctx)) {
            // H.264는 변환 없이 올리지 않음: 충전기를 꽂을 때까지 기다림
            converting = false
            dedupUsed?.delete(); dedupUsed = null
            synchronized(queue) { waitCharge.add(job) }
            RecordService.addLog("H.264라 충전기를 꽂으면 H.265로 변환해서 올려요 (충전 대기): ${job.name}")
            current = null
            persist()
            onChange?.invoke()
            h.post { next(ctx) }
            return
        }
        RecordService.addLog((if (converting) "정리 + H.265 변환 시작: " else "정리 시작: ") + job.name)
        val tb = Transformer.Builder(ctx).setLooper(h.looper)
        if (converting) {
            tb.setVideoMimeType(MimeTypes.VIDEO_H265)
            val src = job.path?.let { File(it).length() } ?: 0L
            val bitrate = try {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, input)
                    val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    if (ms > 5000 && src > 0) ((src * 8 * 1000 / ms - 160_000) * 55 / 100).coerceIn(800_000, 8_000_000).toInt() else null
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
        val poll = object : Runnable {
            override fun run() {
                if (current !== job) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    percent = holder.progress
                    onChange?.invoke()
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
                if (converting) "정리 + H.265 변환 완료: ${job.name} (${before / 1024 / 1024}MB → ${out.length() / 1024 / 1024}MB, H.264 원본은 휴지통 7일)"
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
        if (wasConverting && !ok) {
            // 변환 실패: H.264를 그대로 올리지 않고, 다음 충전 때 다시 시도
            synchronized(queue) { waitCharge.add(job) }
            RecordService.addLog("H.265 변환 실패 → 올리지 않고 다음에 충전기를 꽂을 때 다시 시도: ${job.name}")
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
