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
