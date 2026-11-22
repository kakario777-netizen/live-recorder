package com.kakario.liverecorder

import java.io.OutputStream
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Key(val method: String, val uri: String?, val iv: String?, val format: String?)

/** 받을 조각 하나. part = -1 이면 통째 조각, 0 이상이면 저지연(LL-HLS) 부분 조각 */
class Segment(val seq: Long, val url: String, val key: Key?, val map: String?, val part: Int = -1, val independent: Boolean = false) {
    val order: Long get() = seq * 1000 + (if (part < 0) 999 else part)
}

class Variant(val url: String, val bandwidth: Long, val resolution: String?, val codecs: String?)

class Playlist(
    val isMaster: Boolean,
    val variants: List<Variant>,
    val segments: List<Segment>,
    val parts: List<Segment>,
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
        val parts = ArrayList<Segment>()
        val keys = ArrayList<Key>()
        var target = 4
        var mediaSeq = 0L
        var endList = false
        var key: Key? = null
        var map: String? = null
        var pendingVariant: Map<String, String>? = null
        var idx = 0L
        var partIdx = 0
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
                l.startsWith("#EXT-X-PART:") -> {
                    // 부분 조각은 자기가 속한 통째 조각 줄보다 먼저 나온다
                    val a = attrs(l)
                    val uri = a["URI"]
                    if (uri != null) {
                        parts.add(Segment(mediaSeq + idx, resolveUrl(base, uri), key, map, partIdx, a["INDEPENDENT"] == "YES"))
                    }
                    partIdx++
                }
                l.startsWith("#") -> {}
                else -> {
                    if (pendingVariant != null) {
                        val a = pendingVariant
                        variants.add(Variant(resolveUrl(base, l), a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["RESOLUTION"], a["CODECS"]))
                        pendingVariant = null
                    } else {
                        segs.add(Segment(mediaSeq + idx, resolveUrl(base, l), key, map))
                        idx++
                        partIdx = 0
                    }
                }
            }
        }
        return Playlist(isMaster, variants, segs, parts, target, mediaSeq, endList, keys, map != null)
    }

    fun isDrm(p: Playlist) = p.keys.any {
        it.method.startsWith("SAMPLE-AES") || (it.format != null && it.format != "identity")
    }

    private fun queryMap(url: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return out
        q.split('&').forEach { kv ->
            if (kv.isNotEmpty()) out[kv.substringBefore('=')] = kv.substringAfter('=', "")
        }
        return out
    }

    private fun withQuery(url: String, q: Map<String, String>): String {
        val base = url.substringBefore('?')
        return if (q.isEmpty()) base else base + "?" + q.entries.joinToString("&") { if (it.value.isEmpty()) it.key else "${it.key}=${it.value}" }
    }

    /** 저지연 방송의 '기다렸다 받기' 꼬리표(_HLS_msn 등)를 떼서, 다시 받을 때마다 최신 목록이 오게 한다 */
    fun cleanUrl(url: String): String {
        val q = queryMap(url)
        val kept = q.filterKeys { !it.startsWith("_HLS_") }
        return if (kept.size == q.size) url else withQuery(url, kept)
    }

    /** 파일 이름 앞부분(예: 212631638_720p → 212631638) — 같은 방송끼리 묶는 데 사용 */
    private fun stem(url: String): String =
        URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8").substringBefore('_').substringBefore('.')

    /**
     * 대표 목록(master)에 적힌 화질별 주소에는 접근 키가 빠져 있는 사이트가 있다.
     * 같은 방송의 다른 목록 주소에 붙어 있던 값(pkey 등)을 빠진 것만 채워 넣는다.
     */
    fun borrowQuery(url: String, siblings: List<Captured>): String {
        val st = stem(url)
        val donor = siblings.firstOrNull { it.url != url && stem(it.url) == st && queryMap(it.url).size > queryMap(url).size }
            ?: return url
        val q = queryMap(url)
        queryMap(donor.url).forEach { (k, v) -> if (!k.startsWith("_HLS_") && !q.containsKey(k)) q[k] = v }
        return withQuery(url, q)
    }
}

class DrmException : Exception("복제 방지(DRM)가 걸린 영상이라 녹화할 수 없어요")

/**
 * 생방송 재생목록을 계속 다시 받아, 새로 생긴 조각만 이어 붙여 저장한다.
 * 화면과 상관없이 돌아가므로 화면을 끄거나 다른 앱을 써도 녹화된다.
 * 저지연(LL-HLS) 방송은 부분 조각(part)을 차례대로 받는다.
 */
class HlsRecorder(
    private val src: Captured,
    private val ua: String,
    private val log: (String) -> Unit,
    private val stopped: () -> Boolean,
    private val openOut: (ext: String) -> OutputStream,
    private val progress: (bytes: Long, segs: Int) -> Unit,
    private val onCodec: (mime: String?, fromPlaylist: Boolean) -> Unit = { _, _ -> },
    private val probe: (data: ByteArray, ext: String) -> String? = { _, _ -> null },
    private val siblings: List<Captured> = emptyList(),
) {
    var bytes = 0L; private set
    var segCount = 0; private set
    var endReason = ""; private set
    var failCount = 0; private set

    private val keyCache = HashMap<String, ByteArray>()
    private var playlistCodec: String? = null
    private var codecChecked = false
    private var mapCache: ByteArray? = null

    companion object {
        /** 녹화·썸네일 공용: 실제로 받을 영상 목록 주소와 그 내용을 정한다 (대표 목록이면 최고 화질로) */
        fun resolve(src: Captured, ua: String, siblings: List<Captured>, log: (String) -> Unit): Triple<String, Playlist, String?> {
            var url = Hls.cleanUrl(src.url)
            var r = Net.get(url, src, ua)
            if (r.code !in 200..299) throw Exception("재생목록 받기 실패 (HTTP ${r.code})")
            var pl = Hls.parse(String(r.body), r.finalUrl)
            var codec: String? = null
            if (pl.isMaster) {
                val best = pl.variants.maxByOrNull { it.bandwidth } ?: throw Exception("화질 목록이 비어 있어요")
                log("화질 선택: ${best.resolution ?: "?"} (${best.bandwidth / 1000}kbps), 후보 ${pl.variants.size}개" + (best.codecs?.let { ", 코덱 $it" } ?: ""))
                codec = Codec.fromCodecsAttr(best.codecs)
                url = Hls.cleanUrl(Hls.borrowQuery(best.url, siblings))
                if (url != Hls.cleanUrl(best.url)) log("다른 주소의 접근 키를 빌려 씀")
                r = Net.get(url, src, ua)
                if (r.code !in 200..299) throw Exception("화질 재생목록 받기 실패 (HTTP ${r.code})")
                pl = Hls.parse(String(r.body), r.finalUrl)
            }
            if (pl.endList && pl.segments.size <= 3 && pl.parts.isEmpty()) {
                log("주의: 이미 끝난 짧은 목록이에요 — 접근 키가 빠진 주소이거나 방송 꺼짐 화면일 수 있어요")
            }
            return Triple(url, pl, codec)
        }
    }

    private fun fetch(url: String, tries: Int = 3): ByteArray {
        var last: Exception? = null
        for (i in 0 until tries) {
            val r = try { Net.get(url, src, ua) } catch (e: Exception) { last = e; null }
            if (r != null) {
                if (r.code in 200..299) return r.body
                last = Exception("HTTP ${r.code}")
                // 없음/거부는 다시 해도 같으므로 바로 포기
                if (r.code == 404 || r.code == 403 || r.code == 410) break
            }
            if (stopped()) throw InterruptedException()
            Thread.sleep(500L * (i + 1))
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

    /** 이번에 받을 조각들: 저지연이면 부분 조각, 아니면 통째 조각 */
    private fun pick(p: Playlist, lastOrder: Long): List<Segment> {
        if (p.parts.isEmpty()) return p.segments.filter { it.order > lastOrder }
        val fresh = p.parts.filter { it.order > lastOrder }
        if (lastOrder < 0) {
            // 처음엔 화면이 깨지지 않게 '독립 조각'(키프레임)부터 시작
            val firstIndep = fresh.indexOfFirst { it.independent || it.part == 0 }
            return if (firstIndep >= 0) fresh.drop(firstIndep) else fresh
        }
        return fresh
    }

    fun run() {
        val (url, firstPl, plCodec) = resolve(src, ua, siblings, log)
        playlistCodec = plCodec
        if (Hls.isDrm(firstPl)) throw DrmException()
        val ll = firstPl.parts.isNotEmpty()
        val ext = if (firstPl.hasMap) "mp4" else "ts"
        log("녹화 시작: 조각 길이 ${firstPl.target}초, 형식 .$ext" +
            (if (ll) ", 저지연 방송(부분 조각 ${firstPl.parts.size}개 보임)" else "") +
            (if (firstPl.keys.isNotEmpty()) ", 암호화(AES-128) 풀어서 저장" else ""))

        openOut(ext).use { out ->
            var lastOrder = -1L
            var lastNew = System.currentTimeMillis()
            var wroteMap: String? = null
            var fails = 0
            var gapWarned = 0
            var pl: Playlist? = firstPl
            while (!stopped()) {
                if (pl == null) {
                    try {
                        val r = Net.get(url, src, ua)
                        if (r.code !in 200..299) throw Exception("HTTP ${r.code}")
                        pl = Hls.parse(String(r.body), r.finalUrl)
                        fails = 0
                    } catch (e: Exception) {
                        fails++
                        log("재생목록 다시 받기 실패 ($fails): ${e.message}")
                        if (fails >= 20) { endReason = "재생목록을 계속 못 받음 (방송 종료 또는 주소 만료)"; break }
                        Thread.sleep(2000); continue
                    }
                }
                val p = pl ?: continue
                pl = null
                val maxOrder = (p.parts.lastOrNull() ?: p.segments.lastOrNull())?.order ?: -1
                if (lastOrder >= 0 && maxOrder >= 0 && maxOrder < lastOrder - 30_000) { log("조각 번호 초기화 감지 → 이어서 녹화"); lastOrder = -1 }
                val fresh = pick(p, lastOrder)
                // 끊김 확인: 다음 조각 번호가 건너뛰었는지
                fresh.firstOrNull()?.let { f ->
                    if (lastOrder >= 0 && f.seq > lastOrder / 1000 + 1 && gapWarned < 5) {
                        gapWarned++
                        log("놓친 구간 있음: ${lastOrder / 1000} → ${f.seq} (인터넷이 느렸을 수 있어요)")
                    }
                }
                for (s in fresh) {
                    if (stopped()) break
                    try {
                        if (s.map != null && s.map != wroteMap) {
                            val m = fetch(s.map); out.write(m); bytes += m.size; wroteMap = s.map; mapCache = m
                        }
                        val data = decrypt(fetch(s.url), s)
                        out.write(data)
                        out.flush()
                        bytes += data.size
                        segCount++
                        if (!codecChecked) {
                            codecChecked = true
                            val head = if (s.map != null) (mapCache ?: ByteArray(0)) + data else data
                            val m = probe(head, ext)
                            if (m != null) onCodec(m, false) else onCodec(playlistCodec, true)
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        failCount++
                        if (failCount <= 5 || failCount % 20 == 0) log("조각 ${s.seq}" + (if (s.part >= 0) "-${s.part}" else "") + " 받기 실패($failCount): ${e.message}")
                    }
                    lastOrder = s.order
                    progress(bytes, segCount)
                }
                if (fresh.isNotEmpty()) lastNew = System.currentTimeMillis()
                if (p.endList) { endReason = "방송이 끝났어요"; break }
                if (System.currentTimeMillis() - lastNew > 90_000) { endReason = "90초 동안 새 영상이 없어 종료 (방송 종료 추정)"; break }
                // 저지연 방송은 1초마다, 일반 방송은 조각 길이의 절반마다 새 목록 확인
                val wait = if (ll) 1000L else (p.target * 1000L / 2).coerceIn(1000, 5000)
                var waited = 0L
                while (waited < wait && !stopped()) { Thread.sleep(250); waited += 250 }
            }
            out.flush()
        }
        if (endReason.isEmpty()) endReason = "직접 중지"
    }
}
