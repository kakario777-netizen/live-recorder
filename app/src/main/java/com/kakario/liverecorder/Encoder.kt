package com.kakario.liverecorder

import android.content.Context
import android.content.Intent
import org.apache.commons.net.ftp.FTPClient
import java.io.File

/**
 * 인코딩폰 역할: NAS의 '_변환대기' 폴더에 녹화폰이 넘긴 큰 H.264 원본을
 * 오래된 것부터 하나씩 받아 → H.265로 변환 → 원래 폴더에 올리고 → '_변환대기' 원본을 지움.
 * 중간에 꺼져도 원본은 NAS에 그대로 있으니 다시 켜지면 처음부터 이어서 함.
 */
object Encoder {
    const val DIR = "_변환대기"
    @Volatile var active = false; private set
    @Volatile var currentName: String? = null; private set
    @Volatile var stage = ""; private set
    @Volatile var percent = 0; private set
    @Volatile private var thread: Thread? = null

    fun on(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getBoolean("encoderMode", false)
    fun set(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("main", Context.MODE_PRIVATE).edit().putBoolean("encoderMode", v).apply()
        if (v) start(ctx)
    }

    /** 사람이 읽는 현재 상태 한 줄 */
    fun status(): String = currentName?.let { "$stage ${percent}% · $it" } ?: "인코딩폰: 기다리는 중"

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!on(app) || !FtpConfig.ready(app)) return
        synchronized(this) {
            if (thread?.isAlive == true) return
            active = true
            try { app.startForegroundService(Intent(app, RecordService::class.java).setAction(RecordService.ACTION_UPLOAD)) } catch (_: Exception) {}
            thread = Thread({ loop(app) }, "encoder").also { it.start() }
        }
    }

    private fun loop(ctx: Context) {
        RecordService.addLog("인코딩폰 시작: NAS '$DIR' 폴더를 확인합니다")
        Awake.hold(ctx, "encode", true)
        try {
            while (on(ctx)) {
                val did = try { once(ctx) } catch (e: Exception) {
                    RecordService.addLog("인코딩폰 오류(1분 뒤 다시): ${e.message}"); false
                } finally { currentName = null; stage = ""; percent = 0 }
                if (!did) Thread.sleep(60_000)
            }
        } finally {
            Awake.hold(ctx, "encode", false)
            active = false
            synchronized(this) { thread = null }
            RecordService.addLog("인코딩폰 멈춤")
        }
    }

    private class Remote(val dir: String, val rel: String, val name: String, val size: Long)

    /** 하나 처리하면 true, 할 게 없으면 false */
    private fun once(ctx: Context): Boolean {
        val base = FtpConfig.folder(ctx).trimEnd('/')
        val root = "$base/$DIR"
        // 1. 할 일 찾기 (오래된 것부터)
        val list = ArrayList<Remote>()
        var f = Uploader.connect(ctx)
        try {
            fun walk(dir: String, rel: String, depth: Int) {
                for (x in f.listFiles(dir) ?: emptyArray()) {
                    val n = x.name.substringAfterLast('/')
                    if (n == "." || n == "..") continue
                    if (x.isDirectory && depth < 3) walk("$dir/$n", if (rel.isEmpty()) n else "$rel/$n", depth + 1)
                    else if (x.isFile && n.lowercase().endsWith(".mp4")) list.add(Remote(dir, rel, n, x.size))
                }
            }
            walk(root, "", 0)
        } finally { quit(f) }
        val job = list.minByOrNull { Tidy.timeKey(it.name) } ?: return false

        // 2. 받기
        currentName = job.name; stage = "받는 중"; percent = 0
        val work = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "encoder").apply { mkdirs() }
        work.listFiles()?.forEach { it.delete() }
        val free = work.usableSpace
        if (free < job.size * 2 + 500L * 1024 * 1024) throw Exception("폰 저장공간 부족 (${free / 1024 / 1024}MB 남음)")
        val src = File(work, job.name); val out = File(work, "out_" + job.name)
        f = Uploader.connect(ctx)
        try {
            val ins = f.retrieveFileStream("${job.dir}/${job.name}") ?: throw Exception("받기 실패 (${f.replyString.trim()})")
            var done = 0L
            ins.use { i -> src.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; if (job.size > 0) percent = (done * 100 / job.size).toInt() }
            } }
            if (!f.completePendingCommand()) throw Exception("받기 마무리 실패")
        } finally { quit(f) }
        if (src.length() != job.size) throw Exception("받은 크기가 달라요 (${src.length()} / ${job.size})")

        // 3. 변환 (그래픽 칩 안 거치는 직접 변환)
        stage = "변환 중"; percent = 0
        val t0 = System.currentTimeMillis()
        val err = Direct.run(src, out, bitrateFor(src), { p -> percent = p }, { !on(ctx) })
        val finalDir = if (job.rel.isEmpty()) base else "$base/${job.rel}"
        f = Uploader.connect(ctx)
        try {
            Uploader.mkdirs(f, finalDir)
            if (err != null) {
                // 변환이 안 되면 원본 그대로 원래 폴더로 옮김 (NAS 안에서 이름만 바꿈, 다시 올리지 않음)
                if (!on(ctx)) return true
                if (!f.rename("${job.dir}/${job.name}", "$finalDir/${job.name}")) throw Exception("원본 옮기기 실패")
                moveAss(f, job, finalDir)
                RecordService.addLog("인코딩폰: 변환 안 됨($err) → 원본 그대로 원래 폴더로: ${job.name}")
                return true
            }
            // 4. 올리기 (.part로 올린 뒤 크기 확인하고 진짜 이름으로)
            stage = "올리는 중"; percent = 0
            val part = "$finalDir/${job.name}.part"
            out.inputStream().use { i ->
                val os = f.storeFileStream(part) ?: throw Exception("올리기 실패 (${f.replyString.trim()})")
                var done = 0L; val total = out.length()
                os.use { o ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; percent = (done * 100 / total).toInt() }
                }
                if (!f.completePendingCommand()) throw Exception("올리기 마무리 실패")
            }
            if (size(f, part) != out.length()) throw Exception("올린 크기가 달라요")
            f.deleteFile("$finalDir/${job.name}")
            if (!f.rename(part, "$finalDir/${job.name}")) throw Exception("서버에서 이름 바꾸기 실패")
            if (size(f, "$finalDir/${job.name}") != out.length()) throw Exception("최종 확인 실패")
            // 5. 다 확인됐으니 '_변환대기' 원본 지움, 자막도 같이 옮김
            f.deleteFile("${job.dir}/${job.name}")
            moveAss(f, job, finalDir)
        } finally { quit(f) }
        val min = (System.currentTimeMillis() - t0) / 60000
        RecordService.addLog("인코딩폰: 변환 완료 ${job.size / 1024 / 1024}MB → ${out.length() / 1024 / 1024}MB (${min}분): ${job.name}")
        src.delete(); out.delete()
        return true
    }

    private fun moveAss(f: FTPClient, job: Remote, finalDir: String) {
        val ass = job.name.substringBeforeLast('.') + ".ass"
        if (size(f, "${job.dir}/$ass") > 0) { f.deleteFile("$finalDir/$ass"); f.rename("${job.dir}/$ass", "$finalDir/$ass") }
    }

    /** 원본 영상 비트레이트의 55% (녹화폰 변환과 같은 기준) */
    private fun bitrateFor(src: File): Int = try {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(src.absolutePath)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (ms > 5000) {
                val v = (src.length() * 8 * 1000 / ms - 160_000).coerceAtLeast(150_000)
                (v * 55 / 100).coerceIn(150_000, minOf(v, 8_000_000)).toInt()
            } else 2_000_000
        } finally { r.release() }
    } catch (_: Exception) { 2_000_000 }

    private fun size(f: FTPClient, path: String): Long = try {
        f.mlistFile(path)?.size ?: f.listFiles(path)?.firstOrNull()?.size ?: -1
    } catch (_: Exception) { -1 }

    private fun quit(f: FTPClient) {
        try { f.logout() } catch (_: Exception) {}
        try { f.disconnect() } catch (_: Exception) {}
    }
}
