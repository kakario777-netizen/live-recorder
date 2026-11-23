package com.kakario.liverecorder

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 녹화 파일 이름 만들기: 사이트명 + 스트리머 이름 + 날짜시간 (띄어쓰기로 구분, 넣을 부분은 설정에서 고름) */
object Names {
    fun clean(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), " ").replace(Regex("""\s+"""), " ").trim().trim('.').take(40)

    /** ko.stripchat.com → stripchat */
    fun siteName(url: String): String {
        val parts = hostOf(url).lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (parts.size <= 1) return parts.firstOrNull() ?: ""
        val tld2 = setOf("co", "com", "net", "or", "ne", "go", "ac")
        return if (parts.size >= 3 && parts[parts.size - 2] in tld2) parts[parts.size - 3] else parts[parts.size - 2]
    }

    fun streamerFromUrl(u: String): String {
        val generic = setOf("", "live", "watch", "channel", "user", "users", "profile", "room", "stream", "streams", "play",
            "video", "videos", "ko", "en", "ja", "m", "c", "s", "tv", "index.html")
        val segs = u.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "").split('/')
            .map { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }
        return clean(segs.firstOrNull { it.lowercase() !in generic && !it.all { ch -> ch.isDigit() } } ?: "")
    }

    /** "스트리머 - 사이트", "스트리머 | 사이트", "스트리머's Live ..." 에서 앞부분 */
    fun streamerFromTitle(t: String): String {
        var x = t.split(" - ", " | ", " – ", " — ", "｜").firstOrNull()?.trim() ?: ""
        x = x.replace(Regex("""(?i)('s)?\s*(live|라이브|방송|stream|chat).*$"""), "").trim()
        return clean(x)
    }

    private val knownSites = setOf("stripchat", "chaturbate", "bongacams", "cam4", "camsoda", "myfreecams", "flirt4free",
        "soop", "sooplive", "afreecatv", "chzzk", "naver", "twitch", "youtube", "kick", "pandalive", "pandatv", "tiktok", "liveme")

    /**
     * 파일 이름에서 스트리머 이름 꺼내기 (스트리머 정보가 빠진 채 넘어온 경우용)
     * "stripchat soonilove_ 20261123 094439 (티켓방 흐림) (H.264 원본).mp4" → "soonilove_"
     */
    fun streamerFromFileName(name: String): String {
        val base = name.substringBeforeLast('.').replace(Regex("""\([^)]*\)"""), " ")
        val t = base.split(' ').filter { it.isNotEmpty() && !it.all { c -> c.isDigit() } }.toMutableList()
        if (t.isNotEmpty() && t[0].lowercase() in knownSites) t.removeAt(0)
        else if (t.size >= 2) t.removeAt(0)   // 맨 앞은 사이트명
        return clean(t.joinToString(" "))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
    fun useSite(ctx: Context) = prefs(ctx).getBoolean("nameSite", true)
    fun useStreamer(ctx: Context) = prefs(ctx).getBoolean("nameStreamer", true)
    fun useTime(ctx: Context) = prefs(ctx).getBoolean("nameTime", true)
    fun setParts(ctx: Context, site: Boolean, streamer: Boolean, time: Boolean) =
        prefs(ctx).edit().putBoolean("nameSite", site).putBoolean("nameStreamer", streamer).putBoolean("nameTime", time).apply()

    /** 사용자가 직접 바꿨을 때 (다른 폰에도 맞춤) */
    fun userSetParts(ctx: Context, site: Boolean, streamer: Boolean, time: Boolean) {
        if (useSite(ctx) == site && useStreamer(ctx) == streamer && useTime(ctx) == time) return
        setParts(ctx, site, streamer, time)
        Sync.settingsChanged(ctx)
    }

    fun timeText(at: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd HHmmss", Locale.KOREA).format(Date(at))

    /** 파일 이름(확장자 제외). 다 빼면 날짜시간은 넣는다(이름 겹침 방지) */
    fun build(ctx: Context, pageUrl: String, streamer: String, at: Long = System.currentTimeMillis()): String {
        val parts = ArrayList<String>()
        if (useSite(ctx)) siteName(pageUrl).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (useStreamer(ctx)) clean(streamer).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (useTime(ctx) || parts.isEmpty()) parts.add(timeText(at))
        return parts.joinToString(" ")
    }
}
