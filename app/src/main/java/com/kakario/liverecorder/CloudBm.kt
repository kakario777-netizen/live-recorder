package com.kakario.liverecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 즐겨찾기 폰끼리 맞추기 — 구글 앱스 스크립트(내 구글 드라이브에 파일 하나)로.
 * - 이 폰에서 바뀌면 바로 올림
 * - 앱이 켜져 있는 동안(녹화 서비스 포함) 30초마다 '판 번호'만 물어보고, 바뀌었을 때만 내려받음
 */
object CloudBm {
    private val client by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloudBm", Context.MODE_PRIVATE)
    /** 기본 주소 (내 구글 스크립트) — 두 폰 모두 설정 없이 바로 맞춰지게 앱에 넣어 둠 */
    private val DEFAULT_URL: String by lazy {
        try {
            val k = "lrbm2026".toByteArray()
            val b = android.util.Base64.decode("==QVXh0VCJDFBEGY1hmXk8FNOpWZnxRJEpxDZp3ADIhF541CbNmLPhAP+dURThBOl0DVxNXX+ACGvM0AzdHLWdBWOAnAFYQIL4QVLZVes0UADVUXCFFDP0VAZFlHXFQBdMQUcQkQEAREfkRHKEUHWYAB".reversed(), android.util.Base64.DEFAULT)
            String(ByteArray(b.size) { (b[it].toInt() xor k[it % k.size].toInt()).toByte() })
        } catch (e: Exception) { "" }
    }
    fun url(ctx: Context) = prefs(ctx).getString("url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
    fun on(ctx: Context) = url(ctx).startsWith("https://script.google.com/")
    fun setUrl(ctx: Context, u: String) = prefs(ctx).edit().putString("url", u.trim()).putLong("ver", 0).putBoolean("dirty", true).apply()
    /** 이 폰에서 생방 대기(자동 녹화)를 할지 — 녹화 안 하는 폰은 꺼 둠 */
    fun recordHere(ctx: Context) = prefs(ctx).getBoolean("recordHere", true)
    fun setRecordHere(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("recordHere", on).apply()

    @Volatile var lastResult = ""; private set
    @Volatile private var loopOn = false
    private val lock = Object()

    /** 이 폰에서 즐겨찾기가 바뀜 → 곧 올림 */
    fun changed(ctx: Context) {
        if (!on(ctx)) return
        prefs(ctx).edit().putBoolean("dirty", true).apply()
        val app = ctx.applicationContext
        Thread { Thread.sleep(800); sync(app) }.start()
    }

    /** 30초마다 확인 (앱·녹화 서비스가 살아 있는 동안) */
    fun startLoop(ctx: Context) {
        if (loopOn) return
        loopOn = true
        val app = ctx.applicationContext
        Thread({
            while (true) {
                try { if (on(app)) sync(app) } catch (_: Exception) {}
                // 푸시가 되면 바뀔 때 바로 오니 10분마다 확인만 (빠진 푸시 대비), 안 되면 30초마다
                try { Thread.sleep(if (Push.ready) 10 * 60_000L else 30_000L) } catch (_: InterruptedException) { break }
            }
        }, "cloudBm").apply { isDaemon = true }.start()
    }

    private fun get(u: String): JSONObject {
        client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("응답 ${r.code}")
            return try { JSONObject(s) } catch (e: Exception) { throw Exception("주소가 맞는지 확인 (스크립트 응답이 아님)") }
        }
    }

    private fun post(u: String, body: String): JSONObject {
        val rb = body.toRequestBody("text/plain; charset=utf-8".toMediaType())
        client.newCall(okhttp3.Request.Builder().url(u).post(rb).build()).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("응답 ${r.code}")
            return JSONObject(s)
        }
    }

    /** 맞추기: 결과 문구 돌려줌 */
    fun sync(ctx: Context): String = synchronized(lock) {
        val base = url(ctx)
        if (!on(ctx)) return "구글 주소가 없어요"
        val p = prefs(ctx)
        val dirty = p.getBoolean("dirty", false)
        try {
            val ver = p.getLong("ver", 0)
            val r = get("$base?v=$ver")
            val remoteVer = r.optLong("ver", 0)
            if (r.optBoolean("same") && !dirty) { lastResult = "최신 상태"; return lastResult }
            val local = Bookmarks.all(ctx)
            val remote = r.optString("data").takeIf { it.startsWith("{") }?.let { JSONObject(it) }
            val remoteB = remote?.optJSONArray("b")?.let { runCatching { Bookmarks.fromJson(it) }.getOrNull() } ?: emptyList()
            val remoteC = remote?.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            var merged = if (r.optBoolean("same")) local else Bookmarks.merge(local, remoteB)
            // 다른 폰에서 새로 들어온 즐겨찾기는 녹화 맡은 폰에서 바로 '생방 대기'로
            val had = local.map { it.url }.toSet()
            val fresh = merged.filter { !it.del && !it.watch && it.url !in had }.map { it.url }.toSet()
            if (fresh.isNotEmpty() && recordHere(ctx)) {
                merged = merged.map { if (it.url in fresh) Bookmarks.Item(it.name, it.url, System.currentTimeMillis(), it.del, it.cat, true) else it }
                RecordService.addLog("다른 폰에서 온 즐겨찾기 ${fresh.size}개 → 생방 대기로 등록")
                if (RecordService.instance != null) Watcher.start(ctx) else RecordService.startWatch(ctx)
            }
            val cats = (Bookmarks.cats(ctx) + remoteC).distinct()
            Bookmarks.saveAll(ctx, merged)
            Bookmarks.setCats(ctx, cats)
            val out = JSONObject().put("b", Bookmarks.toJson(merged)).put("c", JSONArray(cats)).toString()
            var newVer = remoteVer
            if (dirty || remote == null || out != remote.toString()) {
                newVer = post(base, out).optLong("ver", remoteVer)
            }
            p.edit().putLong("ver", newVer).putBoolean("dirty", false).apply()
            lastResult = "맞춤 완료 (즐겨찾기 ${merged.count { !it.del }}개)"
            if (!r.optBoolean("same") && remote != null && dirty.not()) RecordService.addLog("다른 폰에서 바뀐 즐겨찾기를 받아 옴 (${merged.count { !it.del }}개)")
        } catch (e: Exception) {
            lastResult = "맞추기 실패: ${e.message ?: e.javaClass.simpleName}"
        }
        lastResult
    }

    /** 구글 앱스 스크립트에 붙여 넣을 코드 */
    val SCRIPT = """
const FILE = 'live-recorder-bookmarks.json';
function file_() {
  const it = DriveApp.getFilesByName(FILE);
  return it.hasNext() ? it.next() : DriveApp.createFile(FILE, '');
}
function out_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function doGet(e) {
  const ver = Number(PropertiesService.getScriptProperties().getProperty('ver') || 0);
  if (e.parameter.v && Number(e.parameter.v) === ver) return out_({ ver: ver, same: true });
  return out_({ ver: ver, data: file_().getBlob().getDataAsString('UTF-8') });
}
function doPost(e) {
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    file_().setContent(e.postData.contents);
    const ver = Date.now();
    PropertiesService.getScriptProperties().setProperty('ver', String(ver));
    return out_({ ver: ver });
  } finally { lock.releaseLock(); }
}
""".trimIndent()
}
