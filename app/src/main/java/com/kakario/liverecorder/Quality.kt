package com.kakario.liverecorder

import android.content.Context

/** 사이트별 최대 다운 화질 (세로 픽셀: 1080 / 720 / 480). 정하지 않은 사이트는 '기본값'을 따름 */
object Quality {
    val CHOICES = listOf(1080, 720, 480)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quality", Context.MODE_PRIVATE)

    fun default(ctx: Context) = prefs(ctx).getInt("default", 720)
    fun setDefault(ctx: Context, h: Int) = prefs(ctx).edit().putInt("default", h).apply()

    fun forSite(ctx: Context, site: String): Int = prefs(ctx).getInt("site_$site", default(ctx))
    fun setSite(ctx: Context, site: String, h: Int) {
        prefs(ctx).edit().putInt("site_$site", h).apply()
        remember(ctx, site)
    }

    /** 스트리머(주소)마다 따로 정한 화질이 있으면 그걸, 없으면 사이트 설정 */
    fun forUrl(ctx: Context, url: String): Int = perUrl(ctx, url).takeIf { it > 0 } ?: forSite(ctx, Names.siteName(url))

    /** 스트리머별 화질: 0 = 사이트 설정 따름, 9999 = 나오는 것 중 최고 */
    fun perUrl(ctx: Context, url: String) = prefs(ctx).getInt("url_" + url.trimEnd('/').lowercase(), 0)
    fun setPerUrl(ctx: Context, url: String, h: Int) { setPerUrlQuiet(ctx, url, h); CloudBm.qualityChanged(ctx) }
    fun setPerUrlQuiet(ctx: Context, url: String, h: Int) = prefs(ctx).edit().putInt("url_" + url.trimEnd('/').lowercase(), h).apply()
    fun label(h: Int) = when (h) { 0 -> "사이트 설정 따름"; 9999 -> "최고 화질"; else -> "${h}p" }

    /** 설정 목록에 띄울 사이트: 직접 정한 사이트 + 즐겨찾기·녹화·방문한 사이트 */
    fun remember(ctx: Context, site: String) {
        if (site.isBlank()) return
        val set = HashSet(prefs(ctx).getStringSet("sites", emptySet()) ?: emptySet())
        if (set.add(site)) prefs(ctx).edit().putStringSet("sites", set).apply()
    }

    fun sites(ctx: Context): List<String> {
        val set = HashSet(prefs(ctx).getStringSet("sites", emptySet()) ?: emptySet())
        Bookmarks.list(ctx).forEach { set.add(Names.siteName(it.url)) }
        RecordService.list().forEach { set.add(Names.siteName(it.pageUrl)) }
        return set.filter { it.isNotBlank() }.sorted()
    }
}
