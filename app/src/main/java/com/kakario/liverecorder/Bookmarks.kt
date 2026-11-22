package com.kakario.liverecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 즐겨찾기: 이름 + 방송 페이지 주소 */
object Bookmarks {
    class Item(val name: String, val url: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Item> = try {
        val a = JSONArray(prefs(ctx).getString("bookmarks", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.optString("name"), o.getString("url")) }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs(ctx).edit().putString("bookmarks", a.toString()).apply()
    }

    /** 같은 주소가 있으면 이름만 바꿈 */
    fun add(ctx: Context, name: String, url: String) {
        val l = list(ctx).filter { it.url != url }.toMutableList()
        l.add(0, Item(name, url))
        save(ctx, l)
    }

    fun rename(ctx: Context, url: String, name: String) = save(ctx, list(ctx).map { if (it.url == url) Item(name, url) else it })
    fun remove(ctx: Context, url: String) = save(ctx, list(ctx).filter { it.url != url })
    fun has(ctx: Context, url: String) = list(ctx).any { it.url == url }
}
