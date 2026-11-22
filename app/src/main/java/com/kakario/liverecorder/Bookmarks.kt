package com.kakario.liverecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 즐겨찾기: 이름 + 방송 페이지 주소.
 * 폰끼리 동기화할 때 합치기 위해 항목마다 바뀐 시각(t)과 삭제 표시(del)를 같이 저장한다.
 */
object Bookmarks {
    class Item(val name: String, val url: String, val t: Long = System.currentTimeMillis(), val del: Boolean = false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)

    /** 삭제 표시까지 포함한 전체 (동기화용) */
    fun all(ctx: Context): List<Item> = try { fromJson(JSONArray(prefs(ctx).getString("bookmarks", "[]"))) } catch (e: Exception) { emptyList() }

    fun list(ctx: Context): List<Item> = all(ctx).filter { !it.del }

    fun fromJson(a: JSONArray): List<Item> = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Item(o.optString("name"), o.getString("url"), o.optLong("t", 0), o.optBoolean("del", false))
    }

    fun toJson(l: List<Item>): JSONArray {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("t", it.t).put("del", it.del)) }
        return a
    }

    fun saveAll(ctx: Context, l: List<Item>) {
        prefs(ctx).edit().putString("bookmarks", toJson(l).toString()).apply()
    }

    /** 두 목록 합치기: 주소마다 더 최근에 바뀐 쪽을 따름 (삭제도 포함) */
    fun merge(a: List<Item>, b: List<Item>): List<Item> {
        val m = LinkedHashMap<String, Item>()
        (a + b).forEach { x -> val cur = m[x.url]; if (cur == null || x.t > cur.t) m[x.url] = x }
        return m.values.sortedByDescending { it.t }
    }

    private fun changed(ctx: Context, l: List<Item>) {
        saveAll(ctx, l)
        Sync.pushSoon(ctx)
    }

    /** 같은 주소가 있으면 이름만 바꿈 */
    fun add(ctx: Context, name: String, url: String) =
        changed(ctx, listOf(Item(name, url)) + all(ctx).filter { it.url != url })

    fun rename(ctx: Context, url: String, name: String) =
        changed(ctx, all(ctx).map { if (it.url == url) Item(name, url) else it })

    fun remove(ctx: Context, url: String) =
        changed(ctx, all(ctx).map { if (it.url == url) Item(it.name, url, System.currentTimeMillis(), true) else it })

    fun has(ctx: Context, url: String) = list(ctx).any { it.url == url }
}
