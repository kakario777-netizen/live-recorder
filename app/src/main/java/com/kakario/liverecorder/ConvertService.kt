package com.kakario.liverecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * 저장된 영상을 폰의 하드웨어 인코더로 H.265(.mp4)로 바꾼다.
 * 여러 개를 고르면 차례대로 처리. 화면을 꺼도 계속 진행된다.
 */
class ConvertService : Service() {

    class Job(val uri: Uri, val name: String, val size: Long)

    companion object {
        private const val CH = "conv"
        private const val NID = 8
        const val ACTION_CANCEL = "cancel"

        val queue = ArrayDeque<Job>()
        @Volatile var current: Job? = null
        @Volatile var percent = 0
        @Volatile var lastResult = ""
        var onChange: (() -> Unit)? = null

        fun enqueue(ctx: Context, jobs: List<Job>) {
            synchronized(queue) { jobs.forEach { j -> if (queue.none { it.uri == j.uri } && current?.uri != j.uri) queue.add(j) } }
            ctx.startForegroundService(Intent(ctx, ConvertService::class.java))
        }

        fun isBusy(uri: Uri) = current?.uri == uri || synchronized(queue) { queue.any { it.uri == uri } }
    }

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var wake: PowerManager.WakeLock? = null
    private var tmp: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(text: String, ongoing: Boolean, pct: Int = -1): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "H.265 변환", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, Intent(this, FilesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (ongoing) "H.265 변환 중" else "H.265 변환")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (pct >= 0) b.setProgress(100, pct, false)
        if (ongoing) {
            val cancel = PendingIntent.getService(this, 3, Intent(this, ConvertService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "모두 취소", cancel).build())
        }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notif("준비 중…", true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
        if (intent?.action == ACTION_CANCEL) {
            synchronized(queue) { queue.clear() }
            transformer?.cancel()
            finishJob("취소했어요", false)
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liverec:conv").apply { acquire(12 * 3600 * 1000L) }
        }
        if (current == null) next()
        return START_NOT_STICKY
    }

    /** 원본 평균 화질의 약 55%로 — H.265는 같은 화질에 용량이 절반 정도 */
    private fun targetBitrate(job: Job): Int? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, job.uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (ms < 5000) null
            else {
                val bps = job.size * 8 * 1000 / ms - 160_000
                (bps * 55 / 100).coerceIn(800_000, 12_000_000).toInt()
            }
        } finally { r.release() }
    } catch (e: Exception) { null }

    private fun next() {
        val job = synchronized(queue) { queue.removeFirstOrNull() }
        if (job == null) {
            current = null
            try { wake?.release() } catch (_: Exception) {}
            wake = null
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(NID, notif(lastResult.ifBlank { "끝났어요" }, false))
            onChange?.invoke()
            stopSelf()
            return
        }
        current = job
        percent = 0
        onChange?.invoke()

        val out = File(getExternalFilesDir(null) ?: filesDir, "converting.mp4").also { it.delete() }
        tmp = out
        val bitrate = targetBitrate(job)
        val enc = DefaultEncoderFactory.Builder(this).setEnableFallback(true)
        if (bitrate != null) enc.setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(enc.build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Thread { save(job, out) }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    finishJob("실패: ${job.name} (${exportException.errorCodeName})", true)
                }
            })
            .build()
        transformer = t
        RecordService.addLog("H.265 변환 시작: ${job.name}" + (bitrate?.let { " (목표 ${it / 1000}kbps)" } ?: ""))
        t.start(MediaItem.fromUri(job.uri), out.absolutePath)
        poll()
    }

    private val holder = ProgressHolder()
    private fun poll() {
        val t = transformer ?: return
        val job = current ?: return
        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            percent = holder.progress
            val left = synchronized(queue) { queue.size }
            getSystemService(NotificationManager::class.java)
                .notify(NID, notif("${job.name} · ${percent}%" + if (left > 0) " (대기 $left)" else "", true, percent))
            onChange?.invoke()
        }
        main.postDelayed({ poll() }, 1000)
    }

    /**
     * 직접 변환 결과 저장: 변환본은 원래 이름(.mp4)으로 그 자리에 두고,
     * 원본은 휴지통으로 옮겨 7일 보관 (자동 변환과 같은 방식). 자동 업로드가 켜져 있으면 올림.
     */
    private fun save(job: Job, out: File) {
        try {
            val before = job.size / 1024 / 1024
            val after = out.length() / 1024 / 1024
            val p = Tidy.pathFor(job.name)
            val msg: String
            if (p != null) {
                val src = File(p)
                val trash = File(Uploader.trashDir(), src.nameWithoutExtension + " (변환 전 원본)." + src.extension)
                if (trash.exists()) trash.delete()
                if (!src.renameTo(trash)) throw Exception("원본을 휴지통으로 옮길 수 없어요")
                trash.setLastModified(System.currentTimeMillis())
                Saver.scan(this, src.absolutePath)
                val dst = File(src.parentFile, src.nameWithoutExtension + ".mp4")
                out.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                Saver.scan(this, dst.absolutePath)
                Tidy.markTidied(this, dst.name)
                msg = "완료: ${dst.name} (${before}MB → ${after}MB, 원본은 휴지통 ${Uploader.trashLabel(this)})"
                Uploader.enqueue(this, dst.name, dst.absolutePath, Names.streamerFromFileName(dst.name))
            } else {
                // 직접 경로를 모르는 파일: 옆에 새 파일로 저장 (원본은 그대로)
                val name = job.name.substringBeforeLast('.') + "_h265.mp4"
                val (uri, os) = Saver.create(this, name, "video/mp4")
                os.use { o -> out.inputStream().use { it.copyTo(o, 256 * 1024) } }
                Saver.finish(this, uri)
                msg = "완료: $name (${before}MB → ${after}MB)"
            }
            out.delete()
            RecordService.addLog(msg)
            main.post { finishJob(msg, true) }
        } catch (e: Exception) {
            out.delete()
            main.post { finishJob("저장 실패: ${e.message}", true) }
        }
    }

    private fun finishJob(msg: String, goNext: Boolean) {
        main.removeCallbacksAndMessages(null)
        lastResult = msg
        transformer = null
        current = null
        tmp?.delete()
        onChange?.invoke()
        if (goNext) next() else {
            try { wake?.release() } catch (_: Exception) {}
            wake = null
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(NID, notif(msg, false))
            stopSelf()
        }
    }
}

/** 다운로드/LiveRecorder 폴더 안 영상 목록 */
object Library {
    class Item(val uri: Uri, val name: String, val size: Long, val date: Long)

    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // 휴지통 같은 하위 폴더는 빼고 녹화 폴더 바로 안의 파일만
        val sel = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${android.provider.MediaStore.MediaColumns.IS_PENDING} = 0"
        ctx.contentResolver.query(col, proj, sel, arrayOf("${Saver.FOLDER}/"),
            "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val low = name.lowercase()
                if (!(low.endsWith(".ts") || low.endsWith(".mp4"))) continue
                out.add(Item(ContentUris.withAppendedId(col, c.getLong(0)), name, c.getLong(2), c.getLong(3)))
            }
        }
        return out
    }
}
