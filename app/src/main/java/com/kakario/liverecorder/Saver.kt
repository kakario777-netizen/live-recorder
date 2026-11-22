package com.kakario.liverecorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream

/** 내 파일 > 다운로드 > LiveRecorder 폴더에 저장 */
object Saver {
    const val FOLDER = "Download/LiveRecorder"

    fun create(ctx: Context, name: String, mime: String): Pair<Uri, OutputStream> {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw Exception("저장 파일을 만들 수 없어요")
        val out = ctx.contentResolver.openOutputStream(uri, "w")
            ?: throw Exception("저장 파일을 열 수 없어요")
        return uri to out.buffered(256 * 1024)
    }

    /**
     * 녹화용: 다운로드/LiveRecorder 폴더에 실제 파일로 바로 쓴다.
     * 녹화 중에도 크기가 보이고, 앱이 중간에 꺼져도 그때까지 받은 영상이 남는다.
     */
    fun createLive(ctx: Context, name: String, mime: String): Triple<Uri?, String?, OutputStream> {
        return try {
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "LiveRecorder")
            dir.mkdirs()
            val f = java.io.File(dir, name)
            val out = java.io.FileOutputStream(f)
            scan(ctx, f.absolutePath)
            Triple(null, f.absolutePath, out)
        } catch (e: Exception) {
            val (u, o) = create(ctx, name, mime)
            Triple(u, null, o)
        }
    }

    /** 내 파일·갤러리에 크기가 바로 반영되게 알림 */
    fun scan(ctx: Context, path: String) {
        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
    }

    fun finish(ctx: Context, uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, v, null, null)
    }

    fun writeText(ctx: Context, name: String, text: String) {
        val (uri, out) = create(ctx, name, "text/plain")
        out.use { it.write(text.toByteArray()) }
        finish(ctx, uri)
    }
}
