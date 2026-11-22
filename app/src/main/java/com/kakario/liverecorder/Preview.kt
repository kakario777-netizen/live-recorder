package com.kakario.liverecorder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 녹화 전 미리보기: 실제로 영상 조각 하나를 받아서 첫 화면을 썸네일로 만든다.
 * 썸네일이 나오면 녹화도 되는 주소, 안 나오면 그 이유를 글로 보여 준다.
 */
object Preview {
    class Result(val bitmap: Bitmap?, val text: String, val ok: Boolean)

    /** 재생기가 받아 간 실제 조각(초기화 조각 + 최근 조각)으로 썸네일 만들기 */
    fun fromPlayer(ctx: Context, streamId: String, ua: String): Result {
        return try {
            val seg = Sniff.latestSeg(streamId) ?: return Result(null, "재생기가 받은 조각이 아직 없어요", false)
            val r = Net.get(seg.url, seg, ua, maxBytes = 16 * 1024 * 1024)
            if (r.code !in 200..299) return Result(null, "조각 받기 실패 (HTTP ${r.code})", false)
            val mp4 = seg.url.substringBefore('?').endsWith(".mp4")
            var data = r.body
            if (mp4) {
                val init = Sniff.initFor(Sniff.trackOf(seg.url)) ?: return Result(null, "시작 정보(init)가 아직 없어요", false)
                val i = Net.get(init.url, init, ua)
                if (i.code !in 200..299) return Result(null, "시작 정보 받기 실패 (HTTP ${i.code})", false)
                data = i.body + data
            }
            frame(ctx, data, if (mp4) "mp4" else "ts", null, "재생 중인 화질 · " + Sniff.trackOf(seg.url).substringAfter('_'))
        } catch (e: Exception) {
            Result(null, "확인 실패: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    private fun frame(ctx: Context, data: ByteArray, ext: String, fallbackCodec: String?, tail: String): Result {
        val f = File(ctx.cacheDir, "thumb_${System.nanoTime()}.$ext")
        f.writeBytes(data)
        var bmp: Bitmap? = null
        var w = 0; var h = 0
        try {
            val mr = MediaMetadataRetriever()
            try {
                mr.setDataSource(f.absolutePath)
                bmp = mr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: mr.frameAtTime
                w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } finally { mr.release() }
        } catch (_: Exception) {}
        val codec = Codec.probeBytes(ctx, data, ext) ?: fallbackCodec
        f.delete()
        val small = bmp?.let { b -> if (b.width > 320) Bitmap.createScaledBitmap(b, 320, b.height * 320 / b.width, true) else b }
        val size = if (w > 0 && h > 0) "${w}x$h · " else ""
        return Result(small, "$size${Codec.label(codec)} · $tail", true)
    }

    fun make(ctx: Context, c: Captured, ua: String, siblings: List<Captured>): Result {
        val notes = ArrayList<String>()
        return try {
            val (_, pl, plCodec) = HlsRecorder.resolve(c, ua, siblings) { notes.add(it) }
            if (Hls.isDrm(pl)) return Result(null, "DRM이 걸려 있어 녹화 불가", false)
            val res = notes.firstOrNull { it.startsWith("화질 선택") }?.substringAfter("화질 선택: ")?.substringBefore(" (")
            // 저지연이면 최신 독립 부분 조각, 아니면 마지막 통째 조각
            val seg = pl.parts.lastOrNull { it.independent || it.part == 0 } ?: pl.segments.lastOrNull()
                ?: return Result(null, "목록에 영상 조각이 없어요", false)
            val r = Net.get(seg.url, c, ua, maxBytes = 16 * 1024 * 1024)
            if (r.code !in 200..299) return Result(null, "영상 조각 받기 실패 (HTTP ${r.code})", false)
            var data = r.body
            val ext = if (seg.map != null) "mp4" else "ts"
            if (seg.map != null) {
                val m = Net.get(seg.map, c, ua)
                if (m.code in 200..299) data = m.body + data
            }
            if (seg.key != null) return Result(null, "암호화된 방송 (녹화는 시도 가능)", true)
            val f = File(ctx.cacheDir, "thumb_${System.nanoTime()}.$ext")
            f.writeBytes(data)
            var bmp: Bitmap? = null
            var w = 0; var h = 0
            try {
                val mr = MediaMetadataRetriever()
                try {
                    mr.setDataSource(f.absolutePath)
                    bmp = mr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: mr.frameAtTime
                    w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                } finally { mr.release() }
            } catch (_: Exception) {}
            val codec = Codec.probeBytes(ctx, data, ext) ?: plCodec
            f.delete()
            val small = bmp?.let { b ->
                val tw = 320
                if (b.width > tw) Bitmap.createScaledBitmap(b, tw, b.height * tw / b.width, true) else b
            }
            val size = when {
                w > 0 && h > 0 -> "${w}x$h"
                res != null -> res
                else -> "?"
            }
            val kind = if (pl.endList) "끝난 영상" else if (pl.parts.isNotEmpty()) "생방송(저지연)" else "생방송"
            val warn = if (pl.endList && pl.segments.size <= 3 && pl.parts.isEmpty()) "\n주의: 방송 꺼짐 화면일 수 있어요" else ""
            Result(small, "$size · ${Codec.label(codec)} · $kind$warn", true)
        } catch (e: Exception) {
            Result(null, "확인 실패: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }
}
