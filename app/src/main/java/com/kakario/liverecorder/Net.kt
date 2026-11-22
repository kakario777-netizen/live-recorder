package com.kakario.liverecorder

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 웹 화면에서 잡힌 영상 주소 하나 (주소 + 그때 보낸 요청 머리말) */
data class Captured(
    val url: String,
    val kind: String,            // hls / dash / flv / mp4 / seg
    val headers: Map<String, String>,
    val pageUrl: String,
    val time: Long = System.currentTimeMillis(),
)

/** 웹뷰가 주고받는 요청 중 영상 관련 주소를 모아 둔다 */
object Sniff {
    private val items = LinkedHashMap<String, Captured>()
    private val segSamples = LinkedHashMap<String, Captured>()
    var listener: (() -> Unit)? = null

    /** '재생기 따라 저장' 중일 때 재생기가 받아 가는 모든 조각을 넘겨받는 곳 */
    @Volatile var follow: ((Captured) -> Unit)? = null
    /** 화질(트랙)별 마지막 초기화 조각(init)과 최근 조각 — 재생기 따라 저장·썸네일용 */
    private val lastInit = HashMap<String, Captured>()
    private val recentSeg = LinkedHashMap<String, Captured>()

    /** 조각 파일 이름에서 방송 번호(첫 덩어리) */
    fun streamId(url: String): String = url.substringBefore('?').substringAfterLast('/').substringBefore('_').substringBefore('.')

    /** 조각 파일 이름에서 화질 트랙(예: 212631638_480p_av1) — 숫자만 있는 덩어리나 init 앞까지 */
    fun trackOf(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        val t = name.split('_')
        val out = ArrayList<String>()
        for ((i, x) in t.withIndex()) {
            if (i > 0 && (x == "init" || x.all { it.isDigit() })) break
            out.add(x)
        }
        return out.joinToString("_")
    }

    fun isInit(url: String) = url.substringBefore('?').substringAfterLast('/').contains("init", true)

    @Synchronized fun initFor(track: String): Captured? = lastInit[track]

    /** 이 방송의 가장 최근 조각(독립 조각 우선) */
    @Synchronized fun latestSeg(id: String): Captured? =
        recentSeg.values.lastOrNull { streamId(it.url) == id && it.url.contains("part0") }
            ?: recentSeg.values.lastOrNull { streamId(it.url) == id }

    @Synchronized fun latestSegAny(): Captured? = recentSeg.values.lastOrNull()?.let { latestSeg(streamId(it.url)) }

    @Synchronized fun hasSegsFor(id: String) = recentSeg.values.any { streamId(it.url) == id }

    fun classify(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") || url.contains(".m3u8?") -> "hls"
            path.endsWith(".mpd") -> "dash"
            path.endsWith(".flv") -> "flv"
            path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") ||
                path.endsWith(".m4v") || path.endsWith(".m4a") -> "seg"
            path.endsWith(".mp4") && Regex("""(part|init|seg|chunk|frag|_\d+)[^/]*\.mp4$""").containsMatchIn(path) -> "seg"
            path.endsWith(".mp4") -> "mp4"
            else -> null
        }
    }

    @Synchronized
    fun add(c: Captured) {
        // 연결 확인용 가짜 목록(ping)은 빼기
        if (c.url.substringBefore('?').endsWith("/ping.m3u8")) return
        if (c.kind == "seg") {
            follow?.invoke(c)
            if (isInit(c.url)) lastInit[trackOf(c.url)] = c
            else {
                recentSeg.remove(c.url); recentSeg[c.url] = c
                if (recentSeg.size > 60) recentSeg.remove(recentSeg.keys.first())
            }
            // 조각 파일은 진단용으로 앞의 몇 개만 남긴다
            val host = hostOf(c.url)
            if (segSamples.keys.count { hostOf(it) == host } < 3) segSamples[c.url] = c
            return
        }
        // 같은 재생목록이 계속 다시 불리므로 쿼리 빼고 같은 주소면 최신 것으로 교체
        val key = c.url.substringBefore('?')
        val isNew = !items.containsKey(key)
        items.remove(key)
        items[key] = c
        if (items.size > 40) items.remove(items.keys.first())
        if (isNew) listener?.invoke()
    }

    @Synchronized fun list(): List<Captured> = items.values.toList().reversed()
    @Synchronized fun segments(): List<Captured> = segSamples.values.toList()
    @Synchronized fun clear() { items.clear(); segSamples.clear(); lastInit.clear(); recentSeg.clear(); listener?.invoke() }
    fun hlsCount() = list().count { it.kind == "hls" }
}

fun hostOf(url: String): String = try { URL(url).host } catch (e: Exception) { "?" }

/** 진단 파일에 쓸 때 쿼리 값(토큰 등)은 가린다 */
fun maskUrl(url: String): String {
    val base = url.substringBefore('?')
    val q = url.substringAfter('?', "")
    if (q.isEmpty()) return base
    val masked = q.split('&').joinToString("&") {
        val k = it.substringBefore('=')
        val v = it.substringAfter('=', "")
        if (v.length > 8) "$k=…(${v.length}자)" else it
    }
    return "$base?$masked"
}

class HttpResult(val code: Int, val type: String?, val body: ByteArray, val finalUrl: String)

object Net {
    private val skip = setOf("range", "accept-encoding", "host", "content-length", "connection")

    fun get(url: String, src: Captured?, ua: String, timeoutMs: Int = 15000, maxBytes: Int = 64 * 1024 * 1024): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        src?.headers?.forEach { (k, v) -> if (k.lowercase() !in skip) conn.setRequestProperty(k, v) }
        conn.setRequestProperty("User-Agent", ua)
        if (src != null && src.headers.keys.none { it.equals("referer", true) }) {
            conn.setRequestProperty("Referer", src.pageUrl)
        }
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) break
                }
            }
            return HttpResult(code, conn.contentType, out.toByteArray(), conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }
}

fun resolveUrl(base: String, ref: String): String = URL(URL(base), ref.trim()).toString()
