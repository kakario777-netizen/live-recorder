package com.kakario.liverecorder

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
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
    class Job(val name: String, val path: String?, val uri: Uri?)

    private val queue = ArrayDeque<Job>()
    @Volatile var current: Job? = null; private set
    @Volatile var percent = 0; private set
    @Volatile var lastResult = ""; private set
    var onChange: (() -> Unit)? = null
    var onIdle: (() -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val holder = ProgressHolder()

    fun busy(): Boolean = current != null || synchronized(queue) { queue.isNotEmpty() }

    /** 파일 목록 표시용: 정리 중이면 "정리 중… 37%", 대기 중이면 "정리 대기" */
    fun statusOf(name: String): String? = when {
        current?.name == name -> "정리 중… $percent%"
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
        if (!job.name.lowercase().endsWith(".mp4")) return  // .ts는 목차 없이도 잘 재생돼 정리 안 함
        val app = ctx.applicationContext
        synchronized(queue) {
            if (current?.name == job.name || queue.any { it.name == job.name }) return
            queue.add(job)
        }
        if (thread == null) {
            thread = HandlerThread("tidy").also { it.start() }
            handler = Handler(thread!!.looper)
        }
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
        val input = job.path?.let { Uri.fromFile(File(it)) } ?: job.uri
        if (input == null) { finish(ctx, job, out, false, "원본을 찾을 수 없어요"); return }
        RecordService.addLog("정리 시작: ${job.name}")
        val t = Transformer.Builder(ctx)
            .setLooper(h.looper)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish(ctx, job, out, true, "")
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish(ctx, job, out, false, exportException.errorCodeName)
                }
            })
            .build()
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
                replace(ctx, job, out)
                markTidied(ctx, job.name)
                "정리 완료: ${job.name}"
            } catch (e: Exception) {
                "정리본 저장 실패(원본 유지): ${e.message}"
            }
        } else {
            msg = "정리 실패(원본 그대로 둠): ${job.name} $err"
        }
        out.delete()
        lastResult = msg
        RecordService.addLog(msg)
        current = null
        onChange?.invoke()
        handler?.post { next(ctx) }
    }

    /** 원래 자리에 완성본으로 바꿔 넣기 */
    private fun replace(ctx: Context, job: Job, done: File) {
        val p = job.path
        if (p != null) {
            val target = File(p)
            val tmp = File(target.parentFile, ".tidy_" + target.name)
            done.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            if (!target.delete()) { tmp.delete(); throw Exception("원본을 지울 수 없어요") }
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
