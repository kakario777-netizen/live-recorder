package com.kakario.liverecorder

import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * '재생기 따라 저장': 앱 화면 속 사이트 재생기가 실제로 받아 가는 영상 조각을
 * 같은 주소로 한 번 더 받아 순서대로 이어 붙인다. 재생기가 재생 중이어야 한다.
 */
class FollowRecorder(
    private val streamId: String,
    private val ua: String,
    private val log: (String) -> Unit,
    private val stopped: () -> Boolean,
    private val openOut: (ext: String) -> OutputStream,
    private val progress: (bytes: Long, segs: Int) -> Unit,
    private val onCodec: (mime: String?, fromPlaylist: Boolean) -> Unit,
    private val probe: (data: ByteArray, ext: String) -> String?,
) {
    var bytes = 0L; private set
    var segCount = 0; private set
    var failCount = 0; private set
    var endReason = ""; private set

    private fun fetch(c: Captured): ByteArray {
        var last: Exception? = null
        for (i in 0 until 3) {
            try {
                val r = Net.get(c.url, c, ua)
                if (r.code in 200..299) return r.body
                last = Exception("HTTP ${r.code}")
                if (r.code == 404 || r.code == 403) break
            } catch (e: Exception) { last = e }
            if (stopped()) throw InterruptedException()
            Thread.sleep(400L * (i + 1))
        }
        throw last ?: Exception("받기 실패")
    }

    fun run() {
        val q = LinkedBlockingQueue<Captured>()
        Sniff.follow = { c -> if (Sniff.streamId(c.url) == streamId) q.offer(c) }
        try {
            val sample = Sniff.latestSeg(streamId)
            val ext = if (sample == null || sample.url.substringBefore('?').endsWith(".mp4")) "mp4" else "ts"
            log("재생기 따라 저장 시작 (방송 $streamId, .$ext) — 앱 화면에서 방송이 재생 중이어야 해요")
            openOut(ext).use { out ->
                val seen = HashSet<String>()
                var curTrack: String? = null
                var codecChecked = false
                var lastNew = System.currentTimeMillis()
                var warned = false
                while (!stopped()) {
                    val c = q.poll(1, TimeUnit.SECONDS)
                    val now = System.currentTimeMillis()
                    if (c == null) {
                        if (!warned && now - lastNew > 20_000) { warned = true; log("20초째 새 조각이 없어요 — 방송 화면이 멈췄는지 확인해 주세요") }
                        if (now - lastNew > 90_000) { endReason = "90초 동안 재생기가 영상을 받지 않아 종료 (방송 종료 또는 재생 멈춤)"; break }
                        continue
                    }
                    if (!seen.add(c.url.substringBefore('?'))) continue
                    if (seen.size > 5000) seen.clear()
                    lastNew = now; warned = false
                    if (Sniff.isInit(c.url)) continue  // 초기화 조각은 해당 화질 조각이 올 때 씀
                    try {
                        val track = Sniff.trackOf(c.url)
                        var head = ByteArray(0)
                        if (ext == "mp4" && track != curTrack) {
                            val init = Sniff.initFor(track)
                            if (init == null) { log("화질($track) 시작 정보가 아직 없어 이 조각은 건너뜀"); continue }
                            head = fetch(init)
                            out.write(head); bytes += head.size
                            if (curTrack != null) log("재생 화질이 바뀜: $curTrack → $track")
                            curTrack = track
                        }
                        val data = fetch(c)
                        out.write(data); out.flush()
                        bytes += data.size
                        segCount++
                        if (!codecChecked) {
                            codecChecked = true
                            onCodec(probe(head + data, ext), false)
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        failCount++
                        if (failCount <= 5 || failCount % 20 == 0) log("조각 받기 실패($failCount): ${e.message}")
                    }
                    progress(bytes, segCount)
                }
                out.flush()
            }
        } finally {
            Sniff.follow = null
        }
        if (endReason.isEmpty()) endReason = "직접 중지"
    }
}
