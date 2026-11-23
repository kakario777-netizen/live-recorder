package com.kakario.liverecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 즐겨찾기: 이름 + 방송 페이지 주소.
 * 폰끼리 동기화할 때 합치기 위해 항목마다 바뀐 시각(t)과 삭제 표시(del)를 같이 저장한다.
 */
object Bookmarks {
    /** cat = 폴더 이름 (빈 글자면 폴더 없음) */
    class Item(val name: String, val url: String, val t: Long = System.currentTimeMillis(), val del: Boolean = false, val cat: String = "",
               /** 자동 녹화: 앱이 가볍게 지켜보다가 방송이 켜지면 녹화 */
               val watch: Boolean = false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)

    /** 삭제 표시까지 포함한 전체 (동기화용) */
    fun all(ctx: Context): List<Item> = try { fromJson(JSONArray(prefs(ctx).getString("bookmarks", "[]"))) } catch (e: Exception) { emptyList() }

    fun list(ctx: Context): List<Item> = all(ctx).filter { !it.del }

    fun fromJson(a: JSONArray): List<Item> = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Item(o.optString("name"), o.getString("url"), o.optLong("t", 0), o.optBoolean("del", false), o.optString("cat"), o.optBoolean("w", false))
    }

    fun toJson(l: List<Item>): JSONArray {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("t", it.t).put("del", it.del).put("cat", it.cat).put("w", it.watch)) }
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
        CloudBm.changed(ctx)
    }

    /** 같은 주소가 있으면 이름만 바꿈 */
    fun add(ctx: Context, name: String, url: String, cat: String? = null) {
        val old = all(ctx).firstOrNull { it.url == url && !it.del }
        changed(ctx, listOf(Item(name, url, cat = cat ?: old?.cat ?: "", watch = old?.watch ?: false)) + all(ctx).filter { it.url != url })
    }

    fun rename(ctx: Context, url: String, name: String) =
        changed(ctx, all(ctx).map { if (it.url == url) Item(name, url, cat = it.cat, watch = it.watch) else it })

    fun setCat(ctx: Context, url: String, cat: String) =
        changed(ctx, all(ctx).map { if (it.url == url) Item(it.name, url, cat = cat, watch = it.watch) else it })

    fun setWatch(ctx: Context, urls: Collection<String>, on: Boolean) =
        changed(ctx, all(ctx).map { if (it.url in urls && !it.del) Item(it.name, it.url, cat = it.cat, watch = on) else it })

    // ---------- 폴더 ----------
    /** 폴더 목록 (비어 있는 폴더도 유지). 항목에만 있는 폴더도 합침 */
    fun cats(ctx: Context): List<String> {
        val saved = try { JSONArray(prefs(ctx).getString("bmCats", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { emptyList() }
        return (saved + list(ctx).map { it.cat }.filter { it.isNotEmpty() }).distinct()
    }
    private fun saveCats(ctx: Context, l: List<String>) {
        prefs(ctx).edit().putString("bmCats", JSONArray(l.distinct()).toString()).apply()
        CloudBm.changed(ctx)
    }

    /** 다른 폰에서 받은 폴더 목록 저장 (다시 올리지 않음) */
    fun setCats(ctx: Context, l: List<String>) = prefs(ctx).edit().putString("bmCats", JSONArray(l.distinct()).toString()).apply()
    fun addCat(ctx: Context, name: String) { if (name.isNotBlank()) saveCats(ctx, cats(ctx) + name.trim()) }

    fun renameCat(ctx: Context, old: String, new: String) {
        if (new.isBlank()) return
        saveCats(ctx, cats(ctx).map { if (it == old) new.trim() else it })
        changed(ctx, all(ctx).map { if (it.cat == old && !it.del) Item(it.name, it.url, cat = new.trim(), watch = it.watch) else it })
    }

    /** 폴더 지우기: 안의 즐겨찾기는 지우지 않고 '폴더 없음'으로 */
    fun removeCat(ctx: Context, name: String) {
        saveCats(ctx, cats(ctx).filter { it != name })
        changed(ctx, all(ctx).map { if (it.cat == name && !it.del) Item(it.name, it.url, cat = "", watch = it.watch) else it })
    }

    fun remove(ctx: Context, url: String) =
        changed(ctx, all(ctx).map { if (it.url == url) Item(it.name, url, System.currentTimeMillis(), true) else it })

    fun has(ctx: Context, url: String) = list(ctx).any { it.url == url }
}
