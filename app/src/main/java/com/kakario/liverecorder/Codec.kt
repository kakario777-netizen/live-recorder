package com.kakario.liverecorder

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File

/** 영상이 어떤 코덱(H.264 / H.265 등)으로 되어 있는지 알아낸다 */
object Codec {
    fun label(mime: String?): String = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265"
        MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
        MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
        null -> "알 수 없음"
        else -> mime.removePrefix("video/").uppercase()
    }

    fun isHevc(mime: String?) = mime == MediaFormat.MIMETYPE_VIDEO_HEVC

    /** H.265 이상으로 압축 효율이 좋은 코덱(H.265·AV1) — 다시 H.265로 바꿀 필요 없음 */
    fun isEfficient(mime: String?) = mime == MediaFormat.MIMETYPE_VIDEO_HEVC || mime == MediaFormat.MIMETYPE_VIDEO_AV1

    private fun videoMime(ex: MediaExtractor): String? {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/")) return m
        }
        return null
    }

    fun probeBytes(ctx: Context, data: ByteArray, ext: String): String? {
        val f = File(ctx.cacheDir, "probe.$ext")
        return try {
            f.writeBytes(data)
            val ex = MediaExtractor()
            try { ex.setDataSource(f.absolutePath); videoMime(ex) } finally { ex.release() }
        } catch (e: Exception) {
            null
        } finally {
            f.delete()
        }
    }

    fun probeUri(ctx: Context, uri: Uri): String? = try {
        val ex = MediaExtractor()
        try { ex.setDataSource(ctx, uri, null); videoMime(ex) } finally { ex.release() }
    } catch (e: Exception) {
        null
    }

    /** 재생목록의 CODECS 값으로 미리 판단 (hvc1/hev1 = H.265, avc1 = H.264) */
    fun fromCodecsAttr(codecs: String?): String? {
        val c = codecs?.lowercase() ?: return null
        return when {
            c.contains("hvc1") || c.contains("hev1") -> MediaFormat.MIMETYPE_VIDEO_HEVC
            c.contains("avc1") || c.contains("avc3") -> MediaFormat.MIMETYPE_VIDEO_AVC
            c.contains("av01") -> MediaFormat.MIMETYPE_VIDEO_AV1
            else -> null
        }
    }

    fun phoneHasHevcEncoder(): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
    }
}
