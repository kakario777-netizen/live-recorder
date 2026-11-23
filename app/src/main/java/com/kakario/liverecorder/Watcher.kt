package com.kakario.liverecorder

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 자동 녹화 (즐겨찾기에서 켠 스트리머):
 *  - 방송 페이지를 열지 않고, 영상 서버에 '방송 목록(master)'만 1분마다 물어봄 → 데이터·전기 거의 안 씀
 *    (방송 중 200 / 꺼짐 404)
 *  - 켜지면 녹화 칸을 만들고, 방송이 끝나면 칸이 스스로 빠짐 (즐겨찾기는 계속 지켜봄)
 *  - 스트리머 번호를 모르면 처음 한 번만 숨은 창으로 페이지를 열어 알아내고 저장
 */
object Watcher {
    private val main = Handler(Looper.getMainLooper())
    /** 주소 → 지금 상태 (즐겨찾기 화면에 표시) */
    val status = ConcurrentHashMap<String, String>()
    @Volatile private var thread: Thread? = null
    @Volatile private var learning = false
    private val learnFailAt = ConcurrentHashMap<String, Long>()

    private fun key(url: String) = url.trimEnd('/').lowercase()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun watched(ctx: Context) = Bookmarks.list(ctx).filter { it.watch }
    fun any(ctx: Context) = watched(ctx).isNotEmpty()

    fun idOf(ctx: Context, url: String): String? = prefs(ctx).getString("id_" + key(url), null)
    fun learnId(ctx: Context, url: String, id: String) {
        if (idOf(ctx, url) == id) return
        prefs(ctx).edit().putString("id_" + key(url), id).apply()
        RecordService.addLog("스트리머 번호 저장: ${Names.streamerFromUrl(url)} = $id")
    }

    /** 서비스가 켜질 때 시작 (이미 돌고 있으면 그대로) */
    @Synchronized fun start(svc: Context) {
        if (thread?.isAlive == true) return
        val ctx = svc.applicationContext
        thread = Thread({ loop(ctx) }, "watcher").apply { isDaemon = true; start() }
    }

    private fun loop(ctx: Context) {
        RecordService.addLog("자동 녹화 지켜보기 시작 (${watched(ctx).size}명)")
        val h = mapOf("Origin" to "https://ko.stripchat.com", "Referer" to "https://ko.stripchat.com/", "Accept" to "*/*",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
        while (RecordService.instance != null) {
            val list = watched(ctx)
            if (list.isEmpty()) { status.clear(); Thread.sleep(15_000); continue }
            val t0 = System.currentTimeMillis()
            val recording = RecordService.list().map { key(it.pageUrl) }.toSet()
            for (b in list) {
                if (RecordService.instance == null) return
                val k = key(b.url)
                if (k in recording) { status[k] = "녹화 칸에서 받는 중"; continue }
                if (!b.url.contains("stripchat")) { status[k] = "이 사이트는 아직 자동 녹화 안 됨 (녹화 버튼 사용)"; continue }
                val id = idOf(ctx, b.url)
                if (id == null) { status[k] = "스트리머 번호 확인 중…"; learnLater(ctx, b); continue }
                val u = "https://edge-hls.doppiocdn.org/hls/$id/master/${id}_auto.m3u8?playlistType=lowLatency"
                val code = try { Net.get(u, null, h["User-Agent"]!!, timeoutMs = 10000, maxBytes = 64 * 1024).code } catch (e: Exception) { -1 }
                when (code) {
                    in 200..299 -> {
                        status[k] = "방송 중 → 녹화 시작"
                        RecordService.addLog("자동 녹화: ${b.name} 방송 켜짐 → 녹화 시작")
                        RecordService.addWatch(ctx, b.url, b.name)
                    }
                    404, 403 -> status[k] = "꺼짐 (1분마다 확인)"
                    else -> status[k] = "확인 실패($code) · 다시 확인 예정"
                }
                Thread.sleep(250)   // 한꺼번에 몰리지 않게
            }
            // 100명이면 한 바퀴 약 30~40초 → 다음 바퀴까지 최소 1분 간격
            val wait = 60_000L - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
        }
    }

    /** 번호를 모르는 스트리머: 숨은 창으로 페이지를 한 번 열어 번호 알아내기 (한 번에 하나씩) */
    private fun learnLater(ctx: Context, b: Bookmarks.Item) {
        if (learning) return
        val last = learnFailAt[key(b.url)] ?: 0
        if (System.currentTimeMillis() - last < 30 * 60_000L) { status[key(b.url)] = "번호 확인 실패 · 30분 뒤 다시"; return }
        learning = true
        val hs = apiHeaders
        if (hs != null) {
            Thread {
                val n = Names.streamerFromUrl(b.url)
                val id = askId("$apiBase/api/front/users/user-ids/" + java.net.URLEncoder.encode(n, "UTF-8"), hs)
                if (id != null) { learnId(ctx, b.url, id); learning = false; batchLearn(ctx) }
                else { apiHeaders = null; main.post { learnNow(ctx, b) } }
            }.start()
            return
        }
        main.post { learnNow(ctx, b) }
    }

    @Volatile private var apiHeaders: Map<String, String>? = null
    @Volatile private var apiBase = "https://ko.stripchat.com"

    /** 이름→번호 주소에 직접 물어보기. 응답에서 번호를 꺼냄 */
    private fun askId(url: String, hs: Map<String, String>): String? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        hs.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) } ?: ""
        Lab.add("스트리머 번호 알아내기", "직접 물음 ${url.substringAfterLast('/')} → $code " + body.take(300))
        if (code in 200..299) Regex(""":\s*"?(\d{4,})"?""").find(body)?.groupValues?.get(1) else null
    } catch (e: Exception) { null }

    /** 아직 번호 모르는 자동 녹화 스트리머를 한꺼번에 (페이지 없이, 같은 머리글로) */
    private val batching = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun batchLearn(ctx: Context) {
        if (!batching.compareAndSet(false, true)) return
        try { batchLearn0(ctx) } finally { batching.set(false) }
    }
    private fun batchLearn0(ctx: Context) {
        val hs = apiHeaders ?: return
        var ok = 0; var fail = 0
        for (b in watched(ctx)) {
            if (!b.url.contains("stripchat") || idOf(ctx, b.url) != null) continue
            val n = Names.streamerFromUrl(b.url)
            if (n.isEmpty()) continue
            val id = askId("$apiBase/api/front/users/user-ids/" + java.net.URLEncoder.encode(n, "UTF-8"), hs)
            if (id != null) { learnId(ctx, b.url, id); ok++ } else { fail++; if (fail >= 3 && ok == 0) break }
            Thread.sleep(200)
        }
        if (ok + fail > 0) RecordService.addLog("스트리머 번호 한꺼번에 찾기: ${ok}명 성공" + (if (fail > 0) ", ${fail}명 실패(페이지로 다시)" else ""))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun learnNow(ctx: Context, b: Bookmarks.Item) {
        val name = Names.streamerFromUrl(b.url)
        val w = WebView(ctx)
        var done = false
        fun finish(id: String?, how: String) {
            if (done) return
            done = true
            if (id != null) { learnId(ctx, b.url, id); Lab.add("스트리머 번호 알아내기", "$name → $id ($how)") }
            else { learnFailAt[key(b.url)] = System.currentTimeMillis(); RecordService.addLog("스트리머 번호를 못 알아냄: $name ($how)"); Lab.add("스트리머 번호 알아내기", "$name → 실패 ($how)") }
            main.post { try { w.stopLoading(); w.destroy() } catch (_: Exception) {}; learning = false }
        }
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.blockNetworkImage = true
        w.settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                Regex("""/hls/(\d+)/master/""").find(u)?.let { finish(it.groupValues[1], "방송 목록 주소") }
                // 페이지가 '이름 → 번호'를 묻는 순간: 같은 머리글로 앱이 직접 물어서 바로 끝냄 + 다른 사람 것도 한꺼번에
                if (u.contains("/api/front/users/user-ids/") && req.method.equals("GET", true)) {
                    val hs = HashMap(req.requestHeaders ?: emptyMap())
                    CookieManager.getInstance().getCookie(u)?.let { hs["Cookie"] = it }
                    apiHeaders = hs; apiBase = u.substringBefore("/api/front/")
                    val id = askId(u, hs)
                    Lab.add("스트리머 번호 알아내기", "이름→번호 주소 머리글: " + hs.keys.joinToString(","))
                    if (id != null) finish(id, "이름→번호 주소")
                    Thread { batchLearn(ctx) }.start()
                }
                return null
            }
            override fun onPageFinished(view: WebView, url: String) {
                // 꺼진 방송은 영상 요청이 없으니 페이지 안의 정보에서 찾음: "id":숫자 … "username":"이름"
                main.postDelayed({
                    if (done) return@postDelayed
                    val js = "(function(){var h=document.documentElement.innerHTML;var n=" + JSONObject.quote(name.lowercase()) + ";var c={};" +
                        "var re=/\"id\":(\\d{3,})[^{}]{0,400}?\"username\":\"([^\"]+)\"/g,m;while((m=re.exec(h))){if(m[2].toLowerCase()==n){c[m[1]]=(c[m[1]]||0)+2}}" +
                        "var re2=/\"username\":\"([^\"]+)\"[^{}]{0,400}?\"id\":(\\d{3,})/g;while((m=re2.exec(h))){if(m[1].toLowerCase()==n){c[m[2]]=(c[m[2]]||0)+1}}" +
                        "return JSON.stringify(c);})()"
                    view.evaluateJavascript(js) { raw ->
                        try {
                            val o = JSONObject(JSONObject("{\"v\":$raw}").getString("v"))
                            var best: String? = null; var bc = 0
                            o.keys().forEach { k -> val c = o.getInt(k); if (c > bc) { bc = c; best = k } }
                            Lab.add("스트리머 번호 알아내기", "$name 페이지 후보: $o")
                            finish(best, "페이지 정보")
                        } catch (e: Exception) { finish(null, "페이지 정보 없음") }
                    }
                }, 3000)
            }
        }
        main.postDelayed({ finish(null, "25초 안에 못 찾음") }, 25_000)
        w.loadUrl(b.url)
    }
}
