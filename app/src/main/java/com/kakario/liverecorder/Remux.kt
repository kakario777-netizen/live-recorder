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

    private const val GAP_US = 250_000L       // 이보다 길게 비면 진짜 멈춘 곳 → 고르게 펴지 않음
    private const val WIN_US = 1_000_000L     // 1초 단위로 장면 간격을 고르게 폄 (앞뒤 기준점은 그대로라 소리와 안 어긋남)

    /** 성공하면 null, 실패하면 이유. 고르게 펴기에서 문제가 나면 예전 방식으로 한 번 더 */
    fun run(src: File, dst: File, progress: (Int) -> Unit): String? {
        val r = try { runOnce(src, dst, progress, smooth = true) } catch (e: Throwable) { e.message ?: "오류" }
        if (r == null) return null
        dst.delete()
        RecordService.addLog("간격 고르게 펴기 실패($r) → 예전 방식으로 정리: ${src.name}")
        return runOnce(src, dst, progress, smooth = false)
    }

    /** 앞부분 영상 200장을 보고 '보여 주는 순서 ≠ 저장 순서'(B프레임) 구조인지 확인 */
    private fun hasReorder(src: File): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.absolutePath)
            var vt = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { if (vt >= 0) return true; vt = i }
            if (vt < 0) return true
            ex.selectTrack(vt)
            var prev = -1L; var n = 0; var back = 0
            while (n < 200) {
                val t = ex.sampleTime
                if (t < 0) break
                if (prev >= 0 && t < prev && prev - t < 1_000_000) back++
                prev = t; n++
                if (!ex.advance()) break
            }
            return back > 2
        } catch (e: Exception) { return true } finally { ex.release() }
    }

    private class F(val data: ByteArray, val t: Long, val flags: Int)

    private fun runOnce(src: File, dst: File, progress: (Int) -> Unit, smooth: Boolean): String? {
        val reorder = hasReorder(src)
        val doSmooth = smooth && !reorder
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
            var dupDrop = 0; var backDrop = 0; var smoothed = 0
            val recent = HashMap<Int, LinkedHashSet<Long>>()   // 트랙별 최근 원래 시간 (겹쳐 들어온 같은 조각 찾기)
            // 영상 1초 묶음: 앞뒤 장면 시간은 그대로, 사이 장면만 같은 간격으로
            val grp = ArrayList<F>()
            var vOut = -1
            fun writeV(f: F, t: Long) {
                info.set(0, f.data.size, t, f.flags)
                muxer!!.writeSampleData(vOut, ByteBuffer.wrap(f.data), info)
            }
            fun flush(keepLast: Boolean) {
                if (grp.isEmpty()) return
                val k = grp.size - 1
                if (k >= 2) {
                    val a = grp[0].t; val b = grp[k].t
                    val step = (b - a).toDouble() / k
                    var moved = false
                    for (i in 0 until (if (keepLast) k else k + 1)) {
                        val nt = a + (step * i).toLong()
                        if (Math.abs(nt - grp[i].t) > 4000) moved = true
                        writeV(grp[i], nt)
                    }
                    if (moved) smoothed++
                } else for (i in 0 until (if (keepLast) k else k + 1)) writeV(grp[i], grp[i].t)
                val last = grp[k]
                grp.clear()
                if (keepLast) grp.add(last)
            }
            while (true) {
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val out = map[ti]
                if (out != null) {
                    val pts = ex.sampleTime
                    // 겹쳐 들어온 같은 장면(같은 원래 시간)은 한 번만 (시간 보정 계산 전에 걸러야 엉뚱하게 안 밀림)
                    val rs = recent.getOrPut(ti) { LinkedHashSet() }
                    if (!rs.add(pts)) { ex.advance(); dupDrop++; continue }
                    if (rs.size > 400) rs.remove(rs.first())
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
                    // 영상이 뒤로 감(겹친 조각 일부) → 같은 장면이 다시 나와 덜컹거리므로 뺌 (B프레임 구조면 건드리지 않음)
                    if (ti !in audio && !reorder && prev != null && t <= prev) { ex.advance(); backDrop++; continue }
                    lastOut[ti] = maxOf(prev ?: 0L, t)
                    val fl = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    if (ti !in audio && doSmooth) {
                        vOut = out
                        val data = ByteArray(n); buf.position(0); buf.get(data, 0, n); buf.clear()
                        val lt = grp.lastOrNull()?.t
                        if (lt != null && t - lt > GAP_US) flush(false)          // 진짜 멈춘 곳: 그대로 둠
                        grp.add(F(data, t, fl))
                        if (t - grp[0].t >= WIN_US) flush(true)
                    } else {
                        info.set(0, n, t, fl)
                        muxer.writeSampleData(out, buf, info)
                    }
                    if (ti !in audio) { vBytes += n; vUs = maxOf(vUs, t) }
                    readBytes += n
                    val pct = (readBytes * 100 / total).toInt().coerceAtMost(99)
                    if (pct != lastPct) { lastPct = pct; progress(pct) }
                }
                ex.advance()
            }
            flush(false)
            muxer.stop()
            lastVideoBytes = vBytes; lastVideoUs = vUs
            val parts = ArrayList<String>()
            if (fixes > 0) parts.add("끊긴 곳 ${fixes}군데 시간 바로잡음")
            if (dropped + dupDrop + backDrop > 0) parts.add("겹친 조각 ${dropped + dupDrop + backDrop}개 뺌")
            if (smoothed > 0) parts.add("들쭉날쭉한 장면 간격 ${smoothed}구간 고르게 폄")
            if (reorder && smooth) parts.add("B프레임 구조라 간격 펴기는 안 함")
            if (parts.isNotEmpty()) RecordService.addLog(parts.joinToString(", ") + ": ${src.name}")
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
