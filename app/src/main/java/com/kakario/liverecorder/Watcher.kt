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
        main.post { learnNow(ctx, b) }
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
                Regex("""/hls/(\d+)/master/""").find(req.url.toString())?.let { finish(it.groupValues[1], "방송 목록 주소") }
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
                }, 6000)
            }
        }
        main.postDelayed({ finish(null, "40초 안에 못 찾음") }, 40_000)
        w.loadUrl(b.url)
    }
}
