package com.kakario.liverecorder

import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Key(val method: String, val uri: String?, val iv: String?, val format: String?)

class Segment(val seq: Long, val url: String, val key: Key?, val map: String?)

class Variant(val url: String, val bandwidth: Long, val resolution: String?)

class Playlist(
    val isMaster: Boolean,
    val variants: List<Variant>,
    val segments: List<Segment>,
    val target: Int,
    val mediaSeq: Long,
    val endList: Boolean,
    val keys: List<Key>,
    val hasMap: Boolean,
)

object Hls {
    private fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        val re = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        re.findAll(line.substringAfter(':')).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return out
    }

    fun parse(text: String, base: String): Playlist {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val variants = ArrayList<Variant>()
        val segs = ArrayList<Segment>()
        val keys = ArrayList<Key>()
        var target = 4
        var mediaSeq = 0L
        var endList = false
        var key: Key? = null
        var map: String? = null
        var pendingVariant: Map<String, String>? = null
        var idx = 0L
        var isMaster = false
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-STREAM-INF") -> { isMaster = true; pendingVariant = attrs(l) }
                l.startsWith("#EXT-X-TARGETDURATION") -> target = l.substringAfter(':').toDoubleOrNull()?.toInt() ?: 4
                l.startsWith("#EXT-X-MEDIA-SEQUENCE") -> mediaSeq = l.substringAfter(':').toLongOrNull() ?: 0
                l.startsWith("#EXT-X-ENDLIST") -> endList = true
                l.startsWith("#EXT-X-KEY") -> {
                    val a = attrs(l)
                    key = if (a["METHOD"] == "NONE") null
                    else Key(a["METHOD"] ?: "?", a["URI"]?.let { resolveUrl(base, it) }, a["IV"], a["KEYFORMAT"])
                    key?.let { keys.add(it) }
                }
                l.startsWith("#EXT-X-MAP") -> map = attrs(l)["URI"]?.let { resolveUrl(base, it) }
                l.startsWith("#") -> {}
                else -> {
                    if (pendingVariant != null) {
                        val a = pendingVariant
                        variants.add(Variant(resolveUrl(base, l), a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["RESOLUTION"]))
                        pendingVariant = null
                    } else {
                        segs.add(Segment(mediaSeq + idx, resolveUrl(base, l), key, map))
                        idx++
                    }
                }
            }
        }
        return Playlist(isMaster, variants, segs, target, mediaSeq, endList, keys, map != null)
    }

    fun isDrm(p: Playlist) = p.keys.any {
        it.method.startsWith("SAMPLE-AES") || (it.format != null && it.format != "identity")
    }
}

class DrmException : Exception("복제 방지(DRM)가 걸린 영상이라 녹화할 수 없어요")

/**
 * 생방송 재생목록을 계속 다시 받아, 새로 생긴 조각만 이어 붙여 저장한다.
 * 화면과 상관없이 돌아가므로 화면을 끄거나 다른 앱을 써도 녹화된다.
 */
class HlsRecorder(
    private val src: Captured,
    private val ua: String,
    private val log: (String) -> Unit,
    private val stopped: () -> Boolean,
    private val openOut: (ext: String) -> OutputStream,
    private val progress: (bytes: Long, segs: Int) -> Unit,
) {
    var bytes = 0L; private set
    var segCount = 0; private set
    var endReason = ""; private set

    private val keyCache = HashMap<String, ByteArray>()

    private fun fetch(url: String, tries: Int = 3): ByteArray {
        var last: Exception? = null
        repeat(tries) { i ->
            try {
                val r = Net.get(url, src, ua)
                if (r.code in 200..299) return r.body
                last = Exception("HTTP ${r.code}")
            } catch (e: Exception) { last = e }
            if (stopped()) throw InterruptedException()
            Thread.sleep(700L * (i + 1))
        }
        throw last ?: Exception("받기 실패")
    }

    private fun decrypt(data: ByteArray, s: Segment): ByteArray {
        val k = s.key ?: return data
        if (k.method != "AES-128") return data
        val uri = k.uri ?: return data
        val keyBytes = keyCache.getOrPut(uri) { fetch(uri) }
        val iv = if (k.iv != null) {
            val hex = k.iv.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            ByteArray(16).also { b -> var v = s.seq; for (i in 15 downTo 8) { b[i] = (v and 0xff).toByte(); v = v shr 8 } }
        }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun run() {
        var url = src.url
        var first = Net.get(url, src, ua)
        if (first.code !in 200..299) throw Exception("재생목록 받기 실패 (HTTP ${first.code})")
        var pl = Hls.parse(String(first.body), first.finalUrl)
        if (pl.isMaster) {
            val best = pl.variants.maxByOrNull { it.bandwidth } ?: throw Exception("화질 목록이 비어 있어요")
            log("화질 선택: ${best.resolution ?: "?"} (${best.bandwidth / 1000}kbps), 후보 ${pl.variants.size}개")
            url = best.url
            first = Net.get(url, src, ua)
            if (first.code !in 200..299) throw Exception("화질 재생목록 받기 실패 (HTTP ${first.code})")
            pl = Hls.parse(String(first.body), first.finalUrl)
        }
        if (Hls.isDrm(pl)) throw DrmException()
        val ext = if (pl.hasMap) "mp4" else "ts"
        log("녹화 시작: 조각 길이 ${pl.target}초, 형식 .$ext" + if (pl.keys.isNotEmpty()) ", 암호화(AES-128) 풀어서 저장" else "")

        openOut(ext).use { out ->
            var lastSeq = -1L
            var lastNew = System.currentTimeMillis()
            var wroteMap: String? = null
            var fails = 0
            var curText: String? = String(first.body)
            var curBase = first.finalUrl
            while (!stopped()) {
                if (curText == null) {
                    try {
                        val r = Net.get(url, src, ua)
                        if (r.code !in 200..299) throw Exception("HTTP ${r.code}")
                        curText = String(r.body); curBase = r.finalUrl
                        fails = 0
                    } catch (e: Exception) {
                        fails++
                        log("재생목록 다시 받기 실패 ($fails): ${e.message}")
                        if (fails >= 20) { endReason = "재생목록을 계속 못 받음 (방송 종료 또는 주소 만료)"; break }
                        Thread.sleep(2000); continue
                    }
                }
                val p = Hls.parse(curText ?: continue, curBase)
                curText = null
                // 방송이 재시작되며 번호가 처음부터 다시 시작된 경우
                val maxSeq = p.segments.lastOrNull()?.seq ?: -1
                if (lastSeq >= 0 && maxSeq >= 0 && maxSeq < lastSeq - 30) { log("조각 번호 초기화 감지 → 이어서 녹화"); lastSeq = -1 }
                val fresh = p.segments.filter { it.seq > lastSeq }
                for (s in fresh) {
                    if (stopped()) break
                    try {
                        if (s.map != null && s.map != wroteMap) {
                            val m = fetch(s.map); out.write(m); bytes += m.size; wroteMap = s.map
                        }
                        val data = decrypt(fetch(s.url), s)
                        out.write(data)
                        bytes += data.size
                        segCount++
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        log("조각 ${s.seq} 건너뜀: ${e.message}")
                    }
                    lastSeq = s.seq
                    progress(bytes, segCount)
                }
                if (fresh.isNotEmpty()) lastNew = System.currentTimeMillis()
                if (p.endList) { endReason = "방송이 끝났어요"; break }
                if (System.currentTimeMillis() - lastNew > 90_000) { endReason = "90초 동안 새 영상이 없어 종료 (방송 종료 추정)"; break }
                val wait = (p.target * 1000L / 2).coerceIn(1000, 5000)
                var waited = 0L
                while (waited < wait && !stopped()) { Thread.sleep(250); waited += 250 }
            }
            out.flush()
        }
        if (endReason.isEmpty()) endReason = "직접 중지"
    }
}
