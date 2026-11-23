package com.kakario.liverecorder

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap

/**
 * 채팅 서버에 앱이 연결 하나만 열고, 직접 받기 중인 방송들의 채팅 채널을 모두 구독.
 * 입장권(connect 한 줄)은 아무 웹 화면에서나 한 번 엿들어 저장해 두고 계속 씀.
 */
object ChatHub {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val main = Handler(Looper.getMainLooper())
    private val subs = ConcurrentHashMap<String, Slot>()   // 모델 번호 → 칸
    private val slotIds = ConcurrentHashMap<Slot, String>()
    @Volatile private var ws: okhttp3.WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var connecting = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var learning = false
    private var nextId = 2
    private var fails = 0
    private val client by lazy { okhttp3.OkHttpClient.Builder().build() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chatHub", Context.MODE_PRIVATE)
    fun token(ctx: Context): String? = prefs(ctx).getString("connect", null)
    private fun url(ctx: Context) = prefs(ctx).getString("url", null) ?: "wss://websocket-sp-v6.stripchat.com/connection/websocket"

    /** 웹 화면에서 채팅 서버로 보낸 입장권을 엿들어 저장 */
    private val lastUrl = ConcurrentHashMap<Int, String>()
    fun onPageWs(ctx: Context, tag: Int, kind: String, text: String) {
        if (kind == "연결") { lastUrl[tag] = text.substringBefore(" | "); return }
        if (kind != "보냄" || lastUrl[tag]?.contains("websocket-sp") != true) return
        val line = text.lines().firstOrNull { it.contains("\"connect\"") && it.contains("token") } ?: return
        val old = token(ctx)
        prefs(ctx).edit().putString("connect", line).putString("url", lastUrl[tag]).apply()
        if (old == null) {
            RecordService.addLog("채팅 입장권 확보 → 앱이 채팅을 직접 받아요")
            if (subs.isNotEmpty()) connect(ctx)
        }
    }

    /** 웹 화면에 채팅 엿듣기 코드 심기 (브라우저 탭·번호 알아내기 화면 등) */
    @SuppressLint("JavascriptInterface")
    fun hook(ctx: Context, w: WebView) {
        val app = ctx.applicationContext
        val tag = System.identityHashCode(w)
        w.addJavascriptInterface(object {
            @JavascriptInterface fun raw(kind: String, text: String) { onPageWs(app, tag, kind, text) }
            @JavascriptInterface fun ws(text: String) {}
            @JavascriptInterface fun msg(text: String) {}
        }, "LRChat")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { WebViewCompat.addDocumentStartJavaScript(w, Slot.WS_JS, setOf("*")) } catch (_: Exception) {}
        }
    }

    /** 입장권이 없으면 숨은 화면으로 방송 페이지를 잠깐 열어 받아 옴 (최대 40초) */
    @SuppressLint("SetJavaScriptEnabled")
    private fun learnToken(ctx: Context, pageUrl: String) {
        if (learning || token(ctx) != null) return
        learning = true
        main.post {
            val w = try { WebView(ctx.applicationContext) } catch (e: Exception) { learning = false; return@post }
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.blockNetworkImage = true
            w.settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.webViewClient = WebViewClient()
            hook(ctx, w)
            var left = 40
            val tick = object : Runnable {
                override fun run() {
                    left--
                    if (token(ctx) != null || left <= 0) {
                        if (token(ctx) == null) RecordService.addLog("채팅 입장권을 못 받음 (나중에 다시 시도)")
                        try { w.stopLoading(); w.destroy() } catch (_: Exception) {}
                        learning = false
                        return
                    }
                    main.postDelayed(this, 1000)
                }
            }
            w.loadUrl(pageUrl)
            main.postDelayed(tick, 1000)
        }
    }

    fun join(ctx: Context, modelId: String, slot: Slot) {
        appCtx = ctx.applicationContext
        slotIds[slot]?.let { if (it != modelId) subs.remove(it) }
        subs[modelId] = slot; slotIds[slot] = modelId
        if (token(ctx) == null) { learnToken(ctx, slot.pageUrl); return }
        if (ws == null) connect(ctx) else if (ready) sub(modelId)
    }

    fun leave(slot: Slot) {
        val id = slotIds.remove(slot) ?: return
        if (subs[id] === slot) subs.remove(id)
        if (ready) synchronized(this) { ws?.send("{\"unsubscribe\":{\"channel\":\"newChatMessage@$id\"},\"id\":${nextId++}}") }
        if (subs.isEmpty()) main.postDelayed({ if (subs.isEmpty()) { ws?.close(1000, "끝"); ws = null; ready = false } }, 60_000)
    }

    private fun sub(id: String) = synchronized(this) {
        ws?.send("{\"subscribe\":{\"channel\":\"newChatMessage@$id\"},\"id\":${nextId++}}")
    }

    @Synchronized private fun connect(ctx: Context) {
        if (connecting || ws != null) return
        val conn = token(ctx) ?: return
        connecting = true; ready = false
        val req = okhttp3.Request.Builder().url(url(ctx)).header("Origin", "https://ko.stripchat.com").header("User-Agent", UA).build()
        ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { webSocket.send(conn) }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                if (text == "{}" || text.isBlank()) { webSocket.send("{}"); return }
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    val o = try { org.json.JSONObject(line) } catch (e: Exception) { continue }
                    if (!ready && o.has("connect")) {
                        ready = true; connecting = false; fails = 0
                        subs.keys.forEach { sub(it) }
                        continue
                    }
                    if (!ready && o.has("error")) {
                        // 입장권이 더는 안 통함 → 버리고 새로 받기
                        prefs(ctx).edit().remove("connect").apply()
                        RecordService.addLog("채팅 입장권이 만료됨 → 새로 받는 중")
                        webSocket.close(1000, "다시"); return
                    }
                    val push = o.optJSONObject("push") ?: continue
                    val ch = push.optString("channel")
                    if (!ch.startsWith("newChatMessage@")) continue
                    val slot = subs[ch.substringAfter('@')] ?: continue
                    val data = push.optJSONObject("pub")?.optJSONObject("data") ?: continue
                    Lab.pickChatPublic(data)?.let { slot.chatIn(it) }
                }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = retry(ctx)
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = retry(ctx)
        })
    }

    private fun retry(ctx: Context) {
        ws = null; ready = false; connecting = false
        if (subs.isEmpty()) return
        fails++
        val wait = (5_000L * fails).coerceAtMost(60_000L)
        main.postDelayed({
            if (subs.isEmpty() || ws != null) return@postDelayed
            if (token(ctx) == null) subs.values.firstOrNull()?.let { learnToken(ctx, it.pageUrl) } else connect(ctx)
        }, wait)
    }
}
