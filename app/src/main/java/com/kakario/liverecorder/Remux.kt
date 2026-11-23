package com.kakario.liverecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 녹화 파일 다시 쓰기 (다시 압축하지 않음).
 * 조각을 이어 붙인 파일은 끊겼다 이어진 곳마다 시간 값이 뒤로 가거나 크게 건너뛰어서
 * 정리·변환 도구가 실패한다. 영상·소리를 처음부터 읽으면서 시간 값을 순서대로 바로잡고,
 * 끊긴 구간(일시정지·재연결)은 붙여서 목차가 있는 일반 MP4로 새로 쓴다.
 */
object Remux {
    private const val JUMP_US = 3_000_000L    // 이보다 크게 건너뛰거나 뒤로 가면 '끊긴 곳'으로 봄
    private const val STEP_US = 33_000L       // 끊긴 곳을 붙일 때 띄우는 간격(1프레임 정도)

    /** 마지막으로 다시 쓴 파일의 영상만의 용량·길이 (변환 화질 계산용) */
    @Volatile var lastVideoBytes = 0L; private set
    @Volatile var lastVideoUs = 0L; private set

    /** 성공하면 null, 실패하면 이유 */
    fun run(src: File, dst: File, progress: (Int) -> Unit): String? {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            val audio = HashSet<Int>()
            var maxIn = 256 * 1024
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                ex.selectTrack(i)
                map[i] = muxer.addTrack(f)
                if (mime.startsWith("audio/")) audio.add(i)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = maxOf(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (map.isEmpty()) return "영상 트랙이 없어요"
            muxer.start()
            val buf = ByteBuffer.allocate(maxOf(maxIn, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val lastOut = HashMap<Int, Long>()     // 트랙별 마지막으로 쓴 시간
            var offset = 0L                         // 모든 트랙에 똑같이 더하는 보정값 (소리·영상 맞춤 유지)
            var started = false
            val total = src.length().coerceAtLeast(1)
            var readBytes = 0L
            var lastPct = -1
            var fixes = 0
            var dropped = 0
            var vBytes = 0L; var vUs = 0L
            lastVideoBytes = 0; lastVideoUs = 0
            while (true) {
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val out = map[ti]
                if (out != null) {
                    val pts = ex.sampleTime
                    if (!started) { offset = -pts; started = true }   // 0초부터 시작
                    var t = pts + offset
                    val prev = lastOut[ti]
                    if (prev != null && (t - prev > JUMP_US || prev - t > JUMP_US)) {
                        // 끊긴 곳: 모든 트랙 중 가장 늦은 시간 바로 뒤에 붙인다
                        val maxPrev = lastOut.values.maxOrNull() ?: prev
                        offset = maxPrev + STEP_US - pts
                        t = pts + offset
                        fixes++
                    }
                    if (t < 0) t = 0
                    if (ti in audio && prev != null && t <= prev) { ex.advance(); dropped++; continue }   // 겹친 소리 조각은 버림
                    lastOut[ti] = maxOf(prev ?: 0L, t)
                    info.set(0, n, t, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(out, buf, info)
                    if (ti !in audio) { vBytes += n; vUs = maxOf(vUs, t) }
                    readBytes += n
                    val pct = (readBytes * 100 / total).toInt().coerceAtMost(99)
                    if (pct != lastPct) { lastPct = pct; progress(pct) }
                }
                ex.advance()
            }
            muxer.stop()
            lastVideoBytes = vBytes; lastVideoUs = vUs
            if (fixes > 0 || dropped > 0) RecordService.addLog("끊긴 곳 ${fixes}군데 시간 바로잡음, 겹친 소리 ${dropped}개 뺌: ${src.name}")
            return null
        } catch (e: Exception) {
            dst.delete()
            return e.message ?: e.javaClass.simpleName
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
