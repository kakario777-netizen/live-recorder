package com.kakario.liverecorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 녹화 칸 하나 = 보이지 않는 재생기(소리 끔) + 조각 따라 저장기.
 * 재생기는 서비스 안에서 돌기 때문에 앱을 내리거나 화면을 꺼도 계속 재생·녹화된다.
 *
 * 상태: 방송 대기 → 720 대기중 → 녹화 중 ⇄ 일시정지(티켓방·방송 꺼짐) — 직접 중지할 때까지 포기하지 않음
 */
class Slot(
    private val ctx: Context,
    val id: Int,
    val pageUrl: String,
    val ua: String,
    val fixedName: String,
    /** 앱이 꺼지기 전에 쓰던 파일 — 최근 것이면 같은 파일에 이어서 녹화 */
    private val resumePath: String? = null,
    /** 자동 녹화로 만든 칸: 방송이 끝나면 스스로 빠짐 */
    val watch: Boolean = false,
    private val onUpdate: () -> Unit,
) {
    companion object {
        /** 채팅 목록 찾기: 같은 부모 아래에 줄이 계속 붙는 곳(이름에 chat·message 등) → 그 안의 새 줄만 넘김 */
        val CHAT_JS = """
            (function(){ if (window.__lrChat) return; window.__lrChat = 1;
              var counts = new Map(), box = null, seen = new WeakSet();
              function send(el){ if (seen.has(el)) return; seen.add(el);
                var t = (el.innerText || '').trim(); if (!t || t.length > 400) return;
                try { LRChat.msg(t); } catch(e){} }
              function hint(p){ var s = ''; for (var i = 0; p && i < 4; i++, p = p.parentElement) s += ' ' + (p.className || '') + ' ' + (p.id || '');
                return /chat|message|msg|comment|채팅/i.test(s); }
              new MutationObserver(function(ms){ ms.forEach(function(m){ m.addedNodes.forEach(function(n){
                if (n.nodeType !== 1) return; var p = m.target;
                if (box) { if (n.parentElement === box) send(n); return; }
                var c = (counts.get(p) || 0) + 1; counts.set(p, c);
                if ((c >= 5 && hint(p)) || c >= 40) { box = p; }
              }); }); }).observe(document.documentElement, {childList: true, subtree: true});
            })();
        """.trimIndent()

        /** 웹소켓 엿듣기: 받은 글(JSON) 안에서 '닉네임 + 내용' 짝을 찾아 넘긴다 */
        /**
         * 숨은 재생기 보호: 같은 앱 안 브라우저 탭에서 같은 사이트를 열면, 사이트가 '다른 탭에서 재생 중'으로 보고
         * 숨은 재생기들을 한꺼번에 멈추는 걸 막는다. (탭끼리 신호 주고받기 끊기 + 항상 '보이는 중'으로)
         */
        val KEEP_JS = """
            (function(){ if (window.__lrKeepStart) return; window.__lrKeepStart = 1;
              try { Object.defineProperty(document, 'hidden', { get: function(){ return false; } }); } catch(e){}
              try { Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; } }); } catch(e){}
              try { document.hasFocus = function(){ return true; }; } catch(e){}
              var stop = function(e){ e.stopImmediatePropagation(); };
              ['visibilitychange','webkitvisibilitychange','pagehide','blur','freeze'].forEach(function(n){
                document.addEventListener(n, stop, true); window.addEventListener(n, stop, true); });
              window.addEventListener('storage', stop, true);
              try {
                var Dummy = function(){ this.onmessage = null; this.onmessageerror = null; };
                Dummy.prototype.postMessage = function(){}; Dummy.prototype.close = function(){};
                Dummy.prototype.addEventListener = function(){}; Dummy.prototype.removeEventListener = function(){};
                Dummy.prototype.dispatchEvent = function(){ return true; };
                window.BroadcastChannel = Dummy;
              } catch(e){}
            })();
        """.trimIndent()

        val WS_JS = """
            (function(){ if (window.__lrWS) return; window.__lrWS = 1;
              var NW = window.WebSocket; if (!NW) return;
              var seenIds = {};
              function str(v){ return typeof v === 'string' && v.length > 0 && v.length < 400; }
              function pick(o, d){
                if (!o || typeof o !== 'object' || d > 7) return;
                var u = o.user || o.author || o.sender || o.userData || null;
                var nick = (u && (u.username || u.userName || u.login || u.nickname || u.name)) ||
                           o.username || o.userName || o.nickname || o.nick || o.login || o.senderName;
                var text = (str(o.text) && o.text) || (str(o.message) && o.message) || (str(o.body) && o.body) ||
                           (o.details && str(o.details.body) && o.details.body) || (str(o.content) && o.content);
                if (str(nick) && text) {
                  var id = o.id || o.messageId || (nick + '|' + text + '|' + (o.createdAt || ''));
                  if (!seenIds[id]) { seenIds[id] = 1; try { LRChat.ws(nick + '\n' + text); } catch(e){} }
                  return;
                }
                for (var k in o) { try { pick(o[k], d + 1); } catch(e){} }
              }
              function onData(s){
                if (typeof s !== 'string') return;
                s.split('\n').forEach(function(line){
                  if (!line) return; var j = null;
                  try { j = JSON.parse(line); } catch(e) {
                    var i = line.search(/[\[{]/); if (i >= 0) { try { j = JSON.parse(line.slice(i)); } catch(e2){} }
                  }
                  if (j) pick(j, 0);
                });
              }
              function W(u, p){
                var ws = (p !== undefined) ? new NW(u, p) : new NW(u);
                try { LRChat.raw('연결', String(u) + (p !== undefined ? ' | 프로토콜 ' + String(p) : '')); } catch(e){}
                try { var s0 = ws.send.bind(ws); ws.send = function(d){ try { LRChat.raw('보냄', typeof d === 'string' ? d : '(이진 ' + (d.byteLength || d.size || 0) + '바이트)'); } catch(e){} return s0(d); }; } catch(e){}
                ws.addEventListener('message', function(ev){ try { LRChat.raw('받음', typeof ev.data === 'string' ? ev.data : '(이진)'); } catch(e){} try { onData(ev.data); } catch(e){} });
                return ws;
              }
              W.prototype = NW.prototype; W.CONNECTING = 0; W.OPEN = 1; W.CLOSING = 2; W.CLOSED = 3;
              window.WebSocket = W;
            })();
        """.trimIndent()

        const val WAIT_HQ_MS = 15_000L      // 720p를 기다리는 최대 시간(15초) — 넘으면 그때 화질로 시작
        const val STALL_MS = 20_000L            // 이만큼 새 조각이 없으면 '끊김'으로 보고 확인 시작
        const val PAUSE_CHECK_MS = 30_000L      // 녹화 중 끊김(티켓방·방송 꺼짐): 30초마다 계속 확인, 포기 안 함
        const val START_WAIT_CHECK_MS = 120_000L // 방송 시작 전 대기: 2분마다 확인
        const val LIGHT_CHECK_MS = 60_000L      // 가벼운 방송 확인이 되면 1분마다 (페이지를 안 열어서 부담 적음)
        const val RESUME_CHECK_MS = 15_000L     // 업데이트 직후 이어 받을 파일이 있을 때: 15초마다 확인
        const val NEW_FILE_AFTER_MS = 30 * 60_000L // 30분 넘게 끊겼다 돌아오면 새 방송으로 보고 새 파일
        const val TARGET_HEIGHT = 720
    }

    private val main = Handler(Looper.getMainLooper())
    private val q = LinkedBlockingQueue<Captured>()
    private var web: WebView? = null
    private var attached = false
    @Volatile private var stopFlag = false
    @Volatile var userStopped = false; private set

    @Volatile var state = "준비 중"; private set
    @Volatile var bytes = 0L; private set
    @Volatile var segs = 0; private set
    @Volatile var quality = ""; private set
    @Volatile var codec = ""; private set
    @Volatile var fileName = ""; private set
    @Volatile var lastSegAt = System.currentTimeMillis(); private set
    val startedAt = System.currentTimeMillis()
    var recordStartAt = 0L; private set
    val host: String = hostOf(pageUrl).removePrefix("www.")
    /** 이 사이트의 최대 다운 화질 (설정 › 다운 화질) */
    val targetH: Int = Quality.forUrl(ctx, pageUrl)

    /** 파일 이름에 쓸 스트리머 이름: 사용자가 정한 이름 → 주소의 이름 부분 → 페이지 제목 */
    @Volatile var streamer: String = fixedName.ifEmpty { Names.streamerFromUrl(pageUrl) }; private set

    private var out: OutputStream? = null
    private var path: String? = null
    /** 지금 쓰는 파일 경로 (이어 녹화용으로 저장) */
    val currentPath: String? get() = path
    private var mediaUri: android.net.Uri? = null
    private var streamId: String? = null
    private val heightCache = HashMap<String, Int>()
    private val hasAudio = HashMap<String, Boolean>()
    @Volatile var noAudio = false; private set
    private val lastInit = HashMap<String, Captured>()

    private fun log(s: String) = RecordService.addLog("[$id $host] $s")
    private fun setState(s: String) { if (state != s) { state = s; log("상태: $s") }; onUpdate() }

    fun start(delayMs: Long = 0) {
        main.postDelayed({
            if (stopFlag || web != null) return@postDelayed
            // 이어 쓸 파일이 없으면 먼저 가볍게 방송 중인지 물어보고, 꺼져 있으면 페이지를 열지 않음
            Thread {
                val live = if (canAppend()) null else liveCheck()
                if (live == false) log("방송 꺼져 있음 → 페이지 열지 않고 1분마다 가볍게 확인")
                else wantOpen = true
            }.start()
        }, delayMs)
        Thread({ loop() }, "slot-$id").start()
    }

    /** 사용자가 직접 멈춤 */
    fun isEnded() = state.startsWith("끝")
    fun stop() { userStopped = true; stopFlag = true; Loading.leave(id) }

    /** 앱 종료 등으로 멈춤 (나중에 이어서 녹화할 대상) */
    fun halt() { stopFlag = true; Loading.leave(id) }

    // ---------- 숨은 재생기 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayer() {
        val w = WebView(ctx)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        // 숨은 재생기는 '안 보이는 화면'이라 폰이 엔진 우선순위를 낮춰 먼저 끄는 걸 막음 (화면에 보이는 것처럼 중요하게)
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            blockNetworkImage = true        // 숨은 재생기: 다른 방송 썸네일 등 그림은 안 받음 (메모리·인터넷 절약)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = ua
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet())
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                val kind = Sniff.classify(url)
                if (kind == "hls") playlistAt = System.currentTimeMillis()
                if (Lab.on && (url.contains("/api/") || url.contains("websocket") || url.contains("token") || url.contains("chat"))) {
                    val n = rawCount.merge("api", 1, Int::plus) ?: 0
                    if (n <= 60) Lab.add("페이지가 부른 주소 ${id}번 ${streamer.ifEmpty { host }}", req.method + " " + url.take(300))
                }
                if (kind == "hls" && req.method.equals("GET", true) && !url.contains("_HLS_msn")) {
                    pinBestQuality(url, req)?.let { return it }
                    if (Lab.wantPlaylist(id)) labPlaylist(url, req)?.let { return it }
                    return null
                }
                if (kind == "hls" && req.method.equals("GET", true) && Lab.wantPlaylist(id)) labPlaylist(url, req)?.let { return it }
                if (kind != "seg") return null
                val c = Captured(url, "seg", req.requestHeaders ?: emptyMap(), pageUrl)
                if (Lab.on) Lab.onSegment(id, streamer.ifEmpty { host }, url, req.requestHeaders ?: emptyMap())
                // 한 번만 받기: 앱이 대신 받아서 저장용으로 보관하고, 같은 데이터를 재생기에도 넘겨준다
                if (req.method.equals("GET", true) && !req.requestHeaders.keys.any { it.equals("Range", true) }) {
                    try {
                        val r = Net.get(url, c, ua, timeoutMs = 20000)
                        if (r.code in 200..299) {
                            cache[url.substringBefore('?')] = r.body
                            trimCache()
                            q.offer(c)
                            countRate(r.body.size)
                            val hs = HashMap<String, String>()
                            r.headers.forEach { (k, v) ->
                                val lk = k.lowercase()
                                if (lk.startsWith("access-control") || lk == "cache-control" || lk == "timing-allow-origin") hs[k] = v
                            }
                            if (hs.keys.none { it.equals("Access-Control-Allow-Origin", true) }) {
                                req.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.let {
                                    hs["Access-Control-Allow-Origin"] = it.value
                                    hs["Access-Control-Allow-Credentials"] = "true"
                                }
                            }
                            val mime = (r.type ?: "video/mp4").substringBefore(';').trim()
                            return WebResourceResponse(mime, null, 200, "OK", hs, java.io.ByteArrayInputStream(r.body))
                        }
                    } catch (_: Exception) {}
                }
                // 대신 받기에 실패하면 재생기가 원래대로 직접 받게 둔다 (저장은 따로 받아서)
                q.offer(c)
                return null
            }

            // 재생기(웹 화면)만 죽은 경우: 앱 전체가 꺼지지 않게 재생기만 새로 만든다
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                log("재생기가 멈춤(메모리 부족 등) → 재생기만 새로 만들어 이어서 녹화")
                if (web === view) {
                    if (attached) try { ctx.getSystemService(WindowManager::class.java).removeView(view) } catch (_: Exception) {}
                    try { view.destroy() } catch (_: Exception) {}
                    web = null
                    // 엔진이 죽으면 모든 재생기가 같이 죽음 → 한꺼번에 다시 열면 또 버거우니 차례로 (녹화하던 것 먼저)
                    Loading.leave(id)
                    wantOpen = true
                }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: return true
                return s != "http" && s != "https"
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url == "about:blank") return
                // 채팅 감시: 채팅 목록에 새 줄이 붙을 때마다 앱으로 넘긴다
                view.evaluateJavascript(CHAT_JS, null)
                if (streamer.isEmpty()) {
                    view.evaluateJavascript("document.title") { raw ->
                        val n = Names.streamerFromTitle((raw ?: "").trim('"'))
                        if (n.isNotEmpty() && streamer.isEmpty()) { streamer = n; log("스트리머 이름: $n") }
                    }
                }
                // 소리 끄고, 멈춰 있으면 계속 재생 (3초마다)
                view.evaluateJavascript("""
                    (function(){ if (window.__lrKeep) return; window.__lrKeep = 1;
                      setInterval(function(){
                        document.querySelectorAll('video,audio').forEach(function(v){
                          v.muted = true; v.volume = 0;
                          if (v.paused) { try { v.play(); } catch(e){} }
                        });
                      }, 3000);
                    })();
                """.trimIndent(), null)
            }
        }
        w.addJavascriptInterface(ChatBridge(), "LRChat")
        // 채팅 연결(웹소켓)을 페이지가 만들기 전에 엿듣는 코드를 심어 둔다 — 화면에 채팅이 안 보여도 받음
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, KEEP_JS, setOf("*")) } catch (_: Exception) {}
            try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, WS_JS, setOf("*")) } catch (_: Exception) {}
        }
        web = w
        // 재생기가 높은 화질을 고르도록 큰 화면(1280x720)으로 둔다.
        // '다른 앱 위에 표시'가 허용돼 있으면 투명·터치 불가 창에 붙여 화면이 꺼져도 재생이 유지되게 한다.
        if (Settings.canDrawOverlays(ctx)) {
            try {
                val lp = WindowManager.LayoutParams(
                    1280, 720,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply { alpha = 0f; x = -5000; y = -5000 }
                ctx.getSystemService(WindowManager::class.java).addView(w, lp)
                attached = true
            } catch (e: Exception) {
                log("숨은 창 붙이기 실패: ${e.message}")
            }
        }
        if (!attached) {
            w.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
            )
            w.layout(0, 0, 1280, 720)
            log("'다른 앱 위에 표시' 권한이 없어 기본 방식으로 재생 (화면을 끄면 멈출 수 있어요)")
        }
        w.loadUrl(pageUrl)
    }

    /** 일시정지 이유: "ticket"(티켓방·비공개) / "ended"(방송 종료) / "" (모름) */
    @Volatile var pauseKind = ""; private set

    private val endedWords = listOf("offline", "오프라인", "방송 종료", "방송이 종료", "방송 중이 아닙", "is not live", "not streaming", "ended the stream", "will be back", "currently away")
    private val ticketWords = listOf("ticket", "티켓", "private", "프라이빗", "비공개", "group show", "그룹 쇼", "show in progress", "spy", "pay to watch", "유료")

    /** 다시 열기 요청 — 실제로 여는 건 loop가 '동시에 여는 개수' 안에서 차례로 */
    private fun reloadPlayer() { wantOpen = true }

    @Volatile private var wantOpen = false
    /** 재생기를 (다시) 연 시각 / 재생기가 마지막으로 방송 목록(m3u8)을 받아 간 시각 */
    @Volatile private var openedAt = 0L
    @Volatile private var playlistAt = 0L

    /** 재생기가 아직 뜨는 중이거나 방송 목록을 계속 받아 가는 중이면 새로고침하지 않음 (끊으면 처음부터 다시라 더 늦어짐) */
    private fun playerBusy(now: Long): Boolean =
        web != null && (now - openedAt < 90_000 || (now - playlistAt < 20_000 && now - openedAt < 240_000))

    /** 우선: 쓰던 파일이 있는 방송(녹화하던 것) */
    private fun priority() = out != null || canAppend()

    private fun tryOpen(now: Long) {
        if (!wantOpen || stopFlag) return
        if (!Loading.tryEnter(id, now, priority())) {
            if (!openWaitLogged) { openWaitLogged = true; log("다른 방송을 여는 중이라 차례 기다림 (폰 부담 줄이기)") }
            return
        }
        openWaitLogged = false
        wantOpen = false
        openedAt = now
        main.post { if (!stopFlag) { if (web == null) createPlayer() else web?.loadUrl(pageUrl) } }
        // 페이지가 뜬 뒤 화면 글자를 읽어 티켓방인지 방송 종료인지 구분
        main.postDelayed({ checkPauseKind() }, 8000)
    }
    private var openWaitLogged = false

    private fun checkPauseKind() {
        val w = web ?: return
        w.evaluateJavascript("(document.body && document.body.innerText || '').slice(0, 20000)") { raw ->
            val t = (raw ?: "").lowercase()
            val kind = when {
                ticketWords.any { t.contains(it) } && !endedWords.any { t.contains(it) } -> "ticket"
                endedWords.any { t.contains(it) } -> "ended"
                else -> ""
            }
            if (kind != pauseKind && kind.isNotEmpty() && System.currentTimeMillis() - lastSegAt > STALL_MS) {
                pauseKind = kind
                log("일시정지 이유 추정: " + if (kind == "ticket") "티켓방·비공개" else "방송 종료")
                onUpdate()
            }
        }
    }

    private fun destroyPlayer() {
        main.post {
            val w = web ?: return@post
            if (attached) try { ctx.getSystemService(WindowManager::class.java).removeView(w) } catch (_: Exception) {}
            w.stopLoading()
            w.destroy()
            web = null
        }
    }

    /** 망이 바뀌었을 때(와이파이 ↔ 데이터): 잠깐이라도 멈춰 있으면 바로 다시 연결 */
    fun onNetworkChanged() {
        if (stopFlag) return
        if (System.currentTimeMillis() - lastSegAt > 4000) {
            log("인터넷 연결이 바뀜 → 재생기 다시 연결")
            reloadPlayer()
        }
    }

    private fun corsHeaders(r: HttpResult, req: WebResourceRequest): HashMap<String, String> {
        val hs = HashMap<String, String>()
        r.headers.forEach { (k, v) ->
            val lk = k.lowercase()
            if (lk.startsWith("access-control") || lk == "cache-control" || lk == "timing-allow-origin") hs[k] = v
        }
        if (hs.keys.none { it.equals("Access-Control-Allow-Origin", true) }) {
            req.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.let {
                hs["Access-Control-Allow-Origin"] = it.value
                hs["Access-Control-Allow-Credentials"] = "true"
            }
        }
        return hs
    }

    @Volatile private var pinnedLogged = false

    /**
     * 화질 고정: 재생기가 받는 '화질 목록'에서 가장 좋은 화질 하나만 남겨서 넘긴다.
     * 재생기가 고를 게 없으니 240p·480p로 왔다 갔다 하지 않는다. (흐린 화면 목록은 건드리지 않음)
     */
    /** 실험판: 방송 목록(m3u8) 원본을 앱이 대신 받아 남기고 재생기에 그대로 넘겨줌 */
    private fun labPlaylist(url: String, req: WebResourceRequest): WebResourceResponse? = try {
        val c = Captured(url, "hls", req.requestHeaders ?: emptyMap(), pageUrl)
        val r = Net.get(url, c, ua, timeoutMs = 15000, maxBytes = 2 * 1024 * 1024)
        if (r.code !in 200..299) null else {
            val text = String(r.body)
            if (text.contains("#EXT-X-STREAM-INF")) Lab.onMaster(id, streamer.ifEmpty { host }, url, text)
            else Lab.onPlaylist(id, streamer.ifEmpty { host }, url, req.requestHeaders ?: emptyMap(), text)
            val hs = HashMap<String, String>()
            r.headers.forEach { (k, v) -> val lk = k.lowercase(); if (lk.startsWith("access-control") || lk == "cache-control") hs[k] = v }
            if (hs.keys.none { it.equals("Access-Control-Allow-Origin", true) }) req.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.let {
                hs["Access-Control-Allow-Origin"] = it.value; hs["Access-Control-Allow-Credentials"] = "true"
            }
            WebResourceResponse("application/vnd.apple.mpegurl", null, 200, "OK", hs, java.io.ByteArrayInputStream(r.body))
        }
    } catch (_: Exception) { null }

    private fun pinBestQuality(url: String, req: WebResourceRequest): WebResourceResponse? {
        return try {
            val c = Captured(url, "hls", req.requestHeaders ?: emptyMap(), pageUrl)
            val r = Net.get(url, c, ua, timeoutMs = 15000, maxBytes = 2 * 1024 * 1024)
            if (r.code !in 200..299) return null
            var text = String(r.body)
            if (Lab.on && text.contains("#EXT-X-STREAM-INF")) Lab.onMaster(id, streamer.ifEmpty { host }, url, text)
            Regex("""/hls/(\d+)/master/""").find(url)?.let { Watcher.learnId(ctx, pageUrl, it.groupValues[1]) }
            if (text.contains("#EXT-X-STREAM-INF")) {
                val lines = text.lines()
                data class V(val inf: String, val uri: String, val bw: Long, val blurred: Boolean)
                val vs = ArrayList<V>()
                val keep = ArrayList<String>()
                var i = 0
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.startsWith("#EXT-X-STREAM-INF")) {
                        val uri = lines.getOrNull(i + 1)?.trim() ?: ""
                        val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        vs.add(V(l, uri, bw, (l + uri).contains("blurred", true)))
                        i += 2; continue
                    }
                    keep.add(l); i++
                }
                val pool = vs.filter { !it.blurred }.ifEmpty { vs }
                // 720p 기준: 세로 720 이하 중 가장 좋은 것 → 없으면 720보다 큰 것 중 가장 작은 것
                fun hOf(v: V) = Regex("""RESOLUTION=\d+x(\d+)""").find(v.inf)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val upTo = pool.filter { hOf(it) in 1..targetH }
                val best = if (upTo.isNotEmpty()) upTo.maxByOrNull { it.bw }
                    else pool.filter { hOf(it) > targetH }.minByOrNull { hOf(it) } ?: pool.maxByOrNull { it.bw }
                if (best != null && vs.size > 1) {
                    text = (keep.filter { it.isNotBlank() } + best.inf + best.uri).joinToString("\n") + "\n"
                    if (!pinnedLogged) {
                        pinnedLogged = true
                        val res = Regex("""RESOLUTION=(\d+x\d+)""").find(best.inf)?.groupValues?.get(1) ?: "?"
                        log("화질 고정: $res (재생기에는 이 화질만 보여 줌, 후보 ${vs.size}개)")
                    }
                }
            }
            WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", 200, "OK", corsHeaders(r, req), java.io.ByteArrayInputStream(text.toByteArray()))
        } catch (e: Exception) { null }
    }

    /** 방송 대기 중엔 재생기를 아예 없애서 메모리를 돌려준다 (확인할 때 다시 만듦) */
    private fun sleepPlayer() { destroyPlayer(); Loading.leave(id) }

    /**
     * 가벼운 방송 확인: 페이지를 열지 않고 사이트 정보 주소만 물어봄 (스트립챗).
     * true = 방송 중, false = 꺼짐, null = 모름(예전처럼 페이지를 열어 확인)
     */
    @Volatile private var apiWarned = false
    @Volatile private var liveApiOk = false
    @Volatile private var lastLive = false
    @Volatile private var retryRequested = false

    /** 사용자가 누른 '지금 다시 시도': 재생기를 새로 만들어 바로 다시 연다 (같은 파일에 이어서) */
    fun retryNow() {
        log("직접 다시 시도 → 재생기 새로 열기")
        liveSeenAt = System.currentTimeMillis()   // 3분 동안은 30초마다 계속 다시 열어 봄
        retryRequested = true
        destroyPlayer()
        Loading.leave(id)
        wantOpen = true
    }
    /** 가벼운 확인에서 '방송 중'이 마지막으로 나온 시각 — 이 뒤 3분은 재생기를 없애지 않고 30초마다 다시 열어 봄 */
    @Volatile private var liveSeenAt = 0L
    private var lastFullCheck = System.currentTimeMillis()
    private fun liveCheck(): Boolean? {
        if (!host.contains("stripchat") || Loading.apiBlocked) return null
        val name = Names.streamerFromUrl(pageUrl).ifEmpty { return null }
        return try {
            val r = Net.get("https://$host/api/front/v2/models/username/" + java.net.URLEncoder.encode(name, "UTF-8") + "/cam", null, ua, timeoutMs = 10000, maxBytes = 2 * 1024 * 1024)
            if (r.code == 403 || r.code == 401) Loading.apiBlocked = true   // 사이트가 막음 → 이번 실행 동안은 다시 묻지 않음
            if (r.code !in 200..299) {
                if (!apiWarned) { apiWarned = true; log("가벼운 방송 확인 안 됨(${r.code}) → 페이지를 열어 확인") }
                return null
            }
            val t = String(r.body)
            // 방송 상태 글자(status)를 먼저 보고, 없을 때만 isLive를 봄
            val st = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(t)?.groupValues?.get(1)?.lowercase()
            val res: Boolean? = when {
                st != null -> st !in setOf("off", "idle", "offline")
                t.contains("\"isLive\":true") -> true
                t.contains("\"isLive\":false") -> false
                else -> null
            }
            if (res == true && !lastLive) log("방송 켜짐 확인(" + (st ?: "isLive") + ") → 재생기 열기")
            lastLive = res == true
            if (res == true) liveSeenAt = System.currentTimeMillis()
            if (res != null && !liveApiOk) { liveApiOk = true; log("가벼운 방송 확인 사용: " + (if (res) "방송 중" else "꺼짐")) }
            res
        } catch (e: Exception) {
            if (!apiWarned) { apiWarned = true; log("가벼운 방송 확인 실패 → 페이지를 열어 확인 (${e.message})") }
            null
        }
    }

    // ---------- 채팅 ----------

    @Volatile private var wsChat = false

    private val rawCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private inner class ChatBridge {
        /** 화면에서 읽은 채팅 (웹소켓에서 받고 있으면 무시) */
        @android.webkit.JavascriptInterface
        fun msg(text: String) { if (!wsChat) onChat(text) }

        /** 실험판: 채팅 연결 원본(주소·보낸 것·받은 것)을 보고서에 남김 */
        @android.webkit.JavascriptInterface
        fun raw(kind: String, text: String) {
            if (!Lab.on) return
            val n = rawCount.merge(kind, 1, Int::plus) ?: 0
            val limit = when (kind) { "연결" -> 10; "보냄" -> 25; else -> 40 }
            if (n <= limit) Lab.add("채팅 연결 원본 ${id}번 ${streamer.ifEmpty { host }}", "$kind: " + text.take(2000))
        }

        /** 채팅 연결(웹소켓)에서 받은 채팅 "닉네임\n내용" */
        @android.webkit.JavascriptInterface
        fun ws(text: String) {
            if (!wsChat) { wsChat = true; log("채팅을 채팅 연결(웹소켓)에서 받는 중") }
            onChat(text)
        }
    }

    @Volatile private var chat: ChatWriter? = null
    /** 녹화된 영상 기준 시간(밀리초) — 일시정지 구간은 빼고 셈 */
    @Volatile private var mediaMs = 0L
    @Volatile private var lastWriteAt = 0L

    private fun mediaNow(): Long {
        val gap = System.currentTimeMillis() - lastWriteAt
        return mediaMs + if (lastWriteAt > 0 && gap < 8000) gap else 0
    }

    // 한국어 방송만 채팅 저장: 처음 15줄 정도를 보고 한글이 있으면 저장, 외국어만이면 이 방송은 채팅 저장 안 함
    @Volatile private var chatDecided: Boolean? = null
    private val pendingChat = ArrayList<Pair<String, Long>>()
    private var pendingSince = 0L
    private var chatAppend = false
    private val hangul = Regex("[\uAC00-\uD7A3\u3131-\u318E]")

    private fun onChat(text: String) {
        val cw = chat ?: return
        val koreanOnly = ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getBoolean("chatKoreanOnly", true)
        if (!koreanOnly || chatDecided == true) { try { cw.add(text, mediaNow()) } catch (_: Exception) {}; return }
        if (chatDecided == false) return
        synchronized(pendingChat) {
            if (pendingChat.isEmpty()) pendingSince = System.currentTimeMillis()
            pendingChat.add(text to mediaNow())
            val n = pendingChat.size
            if (n < 15 && !(n >= 5 && System.currentTimeMillis() - pendingSince > 5 * 60_000L)) return
            val kor = pendingChat.count { hangul.containsMatchIn(it.first) }
            if (kor >= 2 || (kor >= 1 && n < 8)) {
                chatDecided = true
                log("채팅 ${n}줄 중 한국어 ${kor}줄 → 이 방송 채팅 저장")
                pendingChat.forEach { (t, ms) -> try { cw.add(t, ms) } catch (_: Exception) {} }
            } else {
                chatDecided = false
                log("채팅 ${n}줄이 모두 외국어 → 이 방송은 채팅 저장 안 함")
                chat = null
                try { cw.close(mediaNow()) } catch (_: Exception) {}
                if (!chatAppend) { cw.assFile.delete(); cw.txtFile.delete() }
            }
            pendingChat.clear()
        }
    }

    private fun openChat(videoPath: String?, append: Boolean) {
        val p = videoPath ?: return
        synchronized(pendingChat) { pendingChat.clear() }
        chatDecided = null; chatAppend = append
        try {
            chat = ChatWriter(p, append)
            if (append) mediaMs = ChatWriter.lastTimeMs(p)
        } catch (e: Exception) { log("채팅 파일 만들기 실패: ${e.message}") }
    }

    private fun closeChat() {
        val cw = chat ?: return
        chat = null
        // 아직 판단 전인데 방송이 끝남: 모인 것 중 한글이 있으면 저장, 없으면 버림
        if (chatDecided == null) synchronized(pendingChat) {
            val kor = pendingChat.count { hangul.containsMatchIn(it.first) }
            if (kor >= 1) pendingChat.forEach { (t, ms) -> try { cw.add(t, ms) } catch (_: Exception) {} }
            else if (ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getBoolean("chatKoreanOnly", true)) {
                cw.close(mediaNow()); if (!chatAppend) { cw.assFile.delete(); cw.txtFile.delete() }; pendingChat.clear(); return
            }
            pendingChat.clear()
        }
        cw.close(mediaNow())
        if (cw.count > 0) log("채팅 ${cw.count}줄 저장")
    }

    // ---------- 한 번만 받기 보관함 · 속도 ----------

    private val cache = ConcurrentHashMap<String, ByteArray>()
    private fun trimCache() { if (cache.size > 40) cache.keys.take(cache.size - 30).forEach { cache.remove(it) } }

    private val rateLog = ArrayDeque<Pair<Long, Int>>()
    private fun countRate(n: Int) = synchronized(rateLog) {
        val now = System.currentTimeMillis()
        rateLog.addLast(now to n)
        while (rateLog.isNotEmpty() && now - rateLog.first().first > 20_000) rateLog.removeFirst()
    }

    /** 최근 20초 평균 받는 속도 (바이트/초) */
    fun rateBps(): Long = synchronized(rateLog) {
        val now = System.currentTimeMillis()
        while (rateLog.isNotEmpty() && now - rateLog.first().first > 20_000) rateLog.removeFirst()
        rateLog.sumOf { it.second.toLong() } / 20
    }

    /** 실제로 영상을 받는 중인지 (속도 한도 계산용) */
    val isDownloading: Boolean get() = state == "녹화 중" || state == "화질 대기중"

    // ---------- 조각 저장 ----------

    private fun fetch(c: Captured): ByteArray {
        cache.remove(c.url.substringBefore('?'))?.let { return it }
        var last: Exception? = null
        for (i in 0 until 4) {
            try {
                val r = Net.get(c.url, c, ua)
                if (r.code in 200..299) return r.body
                last = Exception("HTTP ${r.code}")
                if (r.code == 404 || r.code == 403) break
            } catch (e: Exception) { last = e }
            if (stopFlag) throw InterruptedException()
            Thread.sleep(500L * (i + 1))
        }
        throw last ?: Exception("받기 실패")
    }

    /** 화질(세로 크기): 시작 정보(init)를 열어 보고, 안 되면 이름의 '720p' 같은 표시로 */
    private fun heightOf(track: String, init: ByteArray?): Int {
        heightCache[track]?.let { return it }
        var h = 0
        if (init != null) {
            val f = File(ctx.cacheDir, "h_$id.mp4")
            try {
                f.writeBytes(init)
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(f.absolutePath)
                    for (i in 0 until ex.trackCount) {
                        val fm = ex.getTrackFormat(i)
                        if (fm.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) hasAudio[track] = true
                        if (fm.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                            h = fm.getInteger(MediaFormat.KEY_HEIGHT)
                            if (codec.isEmpty()) codec = Codec.label(fm.getString(MediaFormat.KEY_MIME))
                        }
                    }
                } finally { ex.release() }
            } catch (_: Exception) {} finally { f.delete() }
        }
        if (h == 0) h = Regex("""(\d{3,4})p""").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        heightCache[track] = h
        return h
    }

    private var appendUsed = false
    private val createdAt = System.currentTimeMillis()
    private var resumeReleased = false

    /** 파일 목록 표시용: 이 방송이 붙잡고 있는 파일들과 그 상태 */
    fun fileStates(): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        val who = "${id}번 ${streamer.ifEmpty { host }}"
        if (fileName.isNotEmpty() && out != null) {
            l += fileName to when (state) {
                "녹화 중" -> "녹화 중 · $who"
                "일시정지" -> "이어받기 대기 · $who (" + (when (pauseKind) { "ticket" -> "티켓방"; "ended" -> "방송 종료 확인 중"; else -> "끊김" }) + ", 방송이 다시 나오면 같은 파일에 이어 붙임)"
                else -> "녹화 중 · $who ($state)"
            }
        }
        if (blurOut != null && blurName.isNotEmpty()) l += blurName to "녹화 중 · $who (티켓방 흐린 화면)"
        val rp = resumePath
        if (out == null && !appendUsed && rp != null && canAppend()) {
            val f = java.io.File(rp)
            val left = ((10 * 60_000L - (System.currentTimeMillis() - maxOf(f.lastModified(), resumeAliveAt))) / 60_000L + 1).coerceAtLeast(1)
            l += f.name to "이어받기 대기 · $who (앱이 다시 켜짐, ${left}분 안에 방송이 나오면 이어 붙임)"
        }
        return l
    }

    /** 앱이 다시 켜진 뒤 이어 쓰지 못한 예전 파일 → 이제 정리로 보냄 */
    private fun releaseResume(why: String) {
        if (resumeReleased || appendUsed) return
        val rp = resumePath ?: return
        resumeReleased = true
        val f = java.io.File(rp)
        if (!f.exists() || f.length() == 0L) return
        log("이어 쓰지 못한 예전 파일은 완료로 보고 정리: ${f.name} ($why)")
        Tidy.enqueue(ctx, Tidy.Job(f.name, f.absolutePath, null, streamer))
    }
    /** 업데이트·오류 직후 이어 쓸 파일이 있는지 (먼저 시작시키는 용도) */
    fun hasResume(): Boolean = canAppend()
    /** 업데이트 뒤 티켓방(흐린 화면)이 이어지는 동안은 이어 쓰기 기한(10분)을 계속 늘려 줌 */
    private var resumeAliveAt = 0L

    /** 쓰던 파일이 있고 10분 안에 마지막으로 쓴 거면 이어 쓰기 */
    private fun canAppend(): Boolean {
        if (appendUsed) return false
        val p = resumePath ?: return false
        val f = java.io.File(p)
        return f.exists() && f.length() > 0 && System.currentTimeMillis() - maxOf(f.lastModified(), resumeAliveAt) < 10 * 60_000L
    }

    private fun openFile(ext: String): OutputStream {
        var base = Names.build(ctx, pageUrl, streamer)
        // 같은 이름이 이미 있으면 (2), (3)…
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), Saver.DIR)
        var n = 2
        val first = base
        while (java.io.File(dir, "$base.$ext").exists()) { base = "$first ($n)"; n++ }
        fileName = "$base.$ext"
        val (u, p, o) = Saver.createLive(ctx, fileName, if (ext == "mp4") "video/mp4" else "video/mp2t")
        mediaUri = u; path = p
        log("저장 파일: ${Saver.FOLDER}/$fileName")
        return o
    }

    // ---------- 티켓방 흐린 화면 따로 저장 ----------

    @Volatile private var blurAt = 0L
    private var blurOut: OutputStream? = null
    private var blurPath: String? = null
    private var blurUri: android.net.Uri? = null
    private var blurName = ""
    private var blurTrack: String? = null
    private var blurBytes = 0L

    private fun writeBlurred(c: Captured, track: String) {
        if (blurOut == null) {
            val base = if (fileName.isNotEmpty()) fileName.substringBeforeLast('.') else Names.build(ctx, pageUrl, streamer)
            blurName = "$base (티켓방 흐림).mp4"
            val (u, p, o) = Saver.createLive(ctx, blurName, "video/mp4")
            blurOut = o; blurPath = p; blurUri = u; blurTrack = null; blurBytes = 0
            log("흐린 화면 파일: $blurName")
        }
        val o = blurOut ?: return
        if (track != blurTrack) {
            val init = lastInit[track] ?: Sniff.initFor(track) ?: return
            val h = fetch(init); o.write(h); blurBytes += h.size
            blurTrack = track
        }
        val d = fetch(c); o.write(d); o.flush(); blurBytes += d.size
    }

    private fun closeBlurred() {
        val o = blurOut ?: return
        blurOut = null
        try { o.flush(); o.close() } catch (_: Exception) {}
        blurUri?.let { Saver.finish(ctx, it) }
        blurPath?.let { Saver.scan(ctx, it) }
        if (blurBytes > 0) {
            log("흐린 화면 ${blurBytes / 1024 / 1024}MB 따로 저장: $blurName")
            Tidy.enqueue(ctx, Tidy.Job(blurName, blurPath, blurUri, streamer))
        }
    }

    /** 조각 이름의 순번(같은 시간대 구분용): 화질 뒤 첫 숫자 덩어리 × 100 + part 번호 */
    private fun seqKey(url: String): Long? {
        val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        val toks = name.split('_')
        val trackLen = Sniff.trackOf(url).split('_').size
        val msn = toks.getOrNull(trackLen)?.toLongOrNull() ?: return null
        val part = Regex("""part(\d+)""").find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 99
        return msn * 100 + part
    }

    private fun loop() {
        val seen = HashSet<String>()
        val written = LinkedHashSet<Long>()   // 이미 저장한 시간대(순번) — 다른 화질로 또 와도 건너뜀
        var curTrack: String? = null
        var curTrackAt = 0L                    // 지금 녹화 화질 조각을 마지막으로 받은 시각
        var firstSegAt = 0L
        var lastReload = System.currentTimeMillis()
        var sleeping = false
        var queued = false
        var lastScan = 0L
        var fails = 0
        var chatWarned = false
        setState("방송 대기")
        try {
            while (!stopFlag) {
                val c = q.poll(1, TimeUnit.SECONDS)
                val now = System.currentTimeMillis()
                if (retryRequested) { retryRequested = false; lastReload = now; sleeping = false }
                if (!resumeReleased && !appendUsed && resumePath != null && out == null && !canAppend()) releaseResume("10분 안에 방송이 안 나옴")
                tryOpen(now)
                if (c == null) {
                    // 순서 대기: 자리가 나면 다시 열어서 시작
                    if (queued) {
                        if (RecordService.canStart(this)) {
                            queued = false; firstSegAt = 0; lastReload = now; sleeping = false
                            log("자리가 나서 녹화 준비")
                            reloadPlayer()
                        }
                        continue
                    }
                    val idle = now - lastSegAt
                    // 자동 녹화 칸인데 5분 넘게 영상이 안 나옴(비공개 방송 등) → 칸을 비우고 다시 지켜보기로
                    if (watch && recordStartAt == 0L && out == null && now - createdAt > 5 * 60_000L && !canAppend()) {
                        log("자동 녹화: 5분 동안 영상이 안 나옴 → 칸 비우고 다시 지켜보기")
                        stopFlag = true; continue
                    }
                    if (now - blurAt < 15_000) continue   // 흐린 화면이 계속 오는 중(티켓방) — 새로고침하지 않고 기다림
                    // 방송이 끝났으면 파일을 닫고 바로 정리·변환·업로드 → 다음 방송은 새 파일
                    // (방송 종료 확인 후 1분 / 이유 모르게 30분 넘게 끊김 / 티켓방은 제외)
                    if (out != null && pauseKind != "ticket" &&
                        ((pauseKind == "ended" && idle > STALL_MS + 60_000) || idle > NEW_FILE_AFTER_MS)) {
                        log((if (pauseKind == "ended") "방송 종료 확인" else "${idle / 60000}분 넘게 끊김") + " → 파일 마무리 후 정리·업로드, 다음 방송은 새 파일로")
                        closeBlurred()
                        closeChat()
                        try { out?.flush(); out?.close() } catch (_: Exception) {}
                        mediaUri?.let { Saver.finish(ctx, it) }
                        path?.let { Saver.scan(ctx, it) }
                        if (bytes > 0) Tidy.enqueue(ctx, Tidy.Job(fileName, path, mediaUri, streamer))
                        out = null; mediaUri = null; path = null; curTrack = null; fileName = ""
                        bytes = 0; segs = 0; recordStartAt = 0; firstSegAt = 0; mediaMs = 0; lastWriteAt = 0
                        written.clear(); pauseKind = ""
                        appendUsed = true            // 다음 방송은 예전 파일에 이어 붙이지 않음
                        RecordService.fileOpened()   // 이어 녹화용 경로 비우기
                        setState("방송 대기")
                        if (watch) { log("방송 끝 → 자동 녹화 칸 정리 (즐겨찾기가 계속 지켜봐요)"); stopFlag = true }
                        continue
                    }
                    if (idle > STALL_MS) {
                        val everRecorded = recordStartAt > 0
                        val waitingStart = !everRecorded && firstSegAt == 0L
                        setState(when {
                            everRecorded -> "일시정지"
                            waitingStart -> "방송 대기"
                            else -> "화질 대기중"
                        })
                        // 업데이트·오류 직후 쓰던 파일이 있으면: 15초마다 빨리 다시 확인 (페이지를 비우지 않음)
                        val resuming = out == null && canAppend()
                        val liveNow = now - liveSeenAt < 3 * 60_000L
                        val interval = if (everRecorded || liveNow) PAUSE_CHECK_MS else if (resuming) RESUME_CHECK_MS else START_WAIT_CHECK_MS
                        if (now - lastReload > (if (waitingStart && !resuming && !liveNow && liveApiOk) LIGHT_CHECK_MS else interval) && !playerBusy(now) && !wantOpen) {
                            lastReload = now
                            // 방송 대기: 먼저 가볍게 물어보고, 꺼져 있으면 페이지를 열지 않음
                            val live = if (waitingStart && !resuming) liveCheck() else null
                            // 혹시 몰라 10분마다 한 번은 실제 페이지로도 확인
                            if (live == false && now - lastFullCheck < 5 * 60_000L) continue
                            if (liveNow && live != false) log("방송 중인데 영상이 아직 안 나옴 → 다시 열기 (티켓방·로딩 지연일 수 있음)")
                            lastFullCheck = now
                            sleeping = false
                            reloadPlayer()
                        } else if (waitingStart && !resuming && !liveNow && !sleeping && !wantOpen && now - openedAt > 60_000 && now - playlistAt > 20_000 && now - lastReload > 40_000) {
                            // 방송 대기: 확인할 때만 잠깐 열고, 평소엔 비워 둠
                            sleeping = true
                            sleepPlayer()
                        }
                    }
                    if (!chatWarned && out != null && chat?.count == 0 && now - recordStartAt > 180_000) {
                        chatWarned = true
                        log("3분째 채팅을 못 찾았어요 (이 사이트 채팅 화면 구조가 달라서일 수 있어요)")
                    }
                    continue
                }
                if (queued) continue
                val key = c.url.substringBefore('?')
                if (!seen.add(key)) continue
                if (seen.size > 5000) seen.clear()
                // 처음 들어온 조각으로 이 칸이 따라갈 방송을 정한다 (광고·다른 영상 섞임 방지)
                val sid = Sniff.streamId(c.url)
                if (streamId == null && !Sniff.isInit(c.url)) { streamId = sid; log("방송 번호: $sid") }
                if (streamId != null && sid != streamId) continue
                val track = Sniff.trackOf(c.url)
                if (Sniff.isInit(c.url)) { lastInit[track] = c; continue }
                // 티켓방 흐린 화면(이름에 blurred): 원래 파일엔 넣지 않고 '(티켓방 흐림)' 파일에 따로 저장
                if (track.contains("blurred", true)) {
                    blurAt = now
                    if (out == null && canAppend()) resumeAliveAt = now   // 방송은 계속 중 → 티켓방 끝나면 쓰던 파일에 이어 쓰기
                    if (pauseKind != "ticket") { pauseKind = "ticket"; log("티켓방 시작(흐린 화면) → 흐린 구간은 따로 저장") }
                    if (out != null || recordStartAt > 0) setState("일시정지")
                    try { writeBlurred(c, track) } catch (e: Exception) { log("흐린 화면 저장 실패: ${e.message}") }
                    cache.remove(key)
                    continue
                }
                val gap = now - lastSegAt
                // 새 파일: 방송 종료가 확인됐거나, 이유를 모르는데 30분 넘게 끊겼을 때 / 티켓방이면 시간과 상관없이 같은 파일
                val newBroadcast = out != null && gap > STALL_MS &&
                    (pauseKind == "ended" || (pauseKind == "" && gap > NEW_FILE_AFTER_MS))
                if (newBroadcast) {
                    log("${gap / 60000}분 만에 방송이 다시 나옴 (" + (if (pauseKind == "ended") "방송 종료 후 재시작" else "오래 끊김") + ") → 새 파일에 녹화")
                    closeBlurred()
                    closeChat()
                    try { out?.flush(); out?.close() } catch (_: Exception) {}
                    mediaUri?.let { Saver.finish(ctx, it) }
                    path?.let { Saver.scan(ctx, it) }
                    if (bytes > 0) Tidy.enqueue(ctx, Tidy.Job(fileName, path, mediaUri, streamer))
                    out = null; mediaUri = null; path = null; curTrack = null
                    bytes = 0; segs = 0; recordStartAt = 0; firstSegAt = 0; mediaMs = 0; lastWriteAt = 0
                    written.clear()
                } else if (out != null && gap > STALL_MS) {
                    log("방송이 다시 나옴" + (if (pauseKind == "ticket") " (티켓방 끝남)" else "") + " → 같은 파일에 이어서 녹화")
                }
                if (blurOut != null) closeBlurred()
                if (gap > STALL_MS) pauseKind = ""
                lastSegAt = now
                if (firstSegAt == 0L) firstSegAt = now
                Loading.leave(id)   // 영상이 나오기 시작함 → 다음 방송이 열 차례
                val mp4 = key.endsWith(".mp4")
                try {
                    // 같은 시간대는 한 번만 저장
                    val sk = seqKey(c.url)
                    if (sk != null && sk in written) { cache.remove(key); continue }

                    // 녹화 중엔 한 화질로 고정: 다른 화질 조각은 지금 화질이 6초 넘게 끊겼을 때만 바꿈
                    if (out != null && curTrack != null && track != curTrack) {
                        if (now - curTrackAt < 6000) { cache.remove(key); continue }
                    }

                    var head: ByteArray? = null
                    if (mp4 && track != curTrack) {
                        val init = lastInit[track] ?: Sniff.initFor(track)
                        if (init == null) { log("화질($track) 시작 정보가 아직 없어 건너뜀"); cache.remove(key); continue }
                        head = fetch(init)
                    }
                    val h = if (!mp4) 0 else heightCache[track]
                        ?: heightOf(track, head ?: lastInit[track]?.let { runCatching { fetch(it) }.getOrNull() })

                    if (out == null && canAppend()) {
                        // 업데이트·강제 종료 직후: 쓰던 파일에 그대로 이어서 (720 대기 없이)
                        val f = java.io.File(resumePath!!)
                        out = java.io.FileOutputStream(f, true).buffered(256 * 1024)
                        path = f.absolutePath
                        fileName = f.name
                        bytes = f.length()
                        recordStartAt = now
                        appendUsed = true
                        log("앱이 다시 켜져서 같은 파일에 이어서 녹화: $fileName")
                        openChat(path, true)
                        RecordService.fileOpened()
                        if (head == null && mp4) head = lastInit[track]?.let { fetch(it) }
                        // 파일 앞의 시작 정보와 같으면 다시 쓰지 않음 (중간에 또 들어가면 동영상 앱이 거기서 멈춤)
                        val hd = head
                        if (mp4 && hd != null && Mp4.sameInit(f, hd)) {
                            head = null
                            curTrack = track
                            log("시작 정보가 같아서 그대로 이어 붙임")
                        }
                    }
                    if (out == null) {
                        // 시작 전: 720p 이상이 나올 때까지 기다림 (1분 넘으면 그때 화질로 시작)
                        val waited = now - firstSegAt
                        if (targetH < 9999 && h in 1 until targetH && waited < WAIT_HQ_MS) {
                            setState("화질 대기중")
                            cache.remove(key)
                            continue
                        }
                        // 속도 한도 확인
                        if (!RecordService.canStart(this)) {
                            queued = true
                            setState("순서 대기")
                            log("받기 속도 한도(초당 7.5MB)가 꽉 차서 순서 대기")
                            q.clear(); cache.clear()
                            sleepPlayer()
                            continue
                        }
                        if (targetH < 9999 && h in 1 until targetH) log("15초 동안 ${targetH}p가 안 잡혀 ${h}p로 녹화 시작")
                        out = openFile(if (mp4) "mp4" else "ts")
                        recordStartAt = now
                        openChat(path, false)
                        RecordService.fileOpened()
                        if (head == null && mp4) head = lastInit[track]?.let { fetch(it) }
                    }
                    val o = out ?: continue
                    if (head != null) {
                        o.write(head); bytes += head.size
                        if (curTrack != null) log("화질 바뀜: ${heightCache[curTrack] ?: "?"}p → ${h}p (같은 파일에 이어서 녹화)")
                        curTrack = track
                        // 소리 트랙 확인
                        if (mp4 && heightCache.containsKey(track) && hasAudio[track] != true) {
                            if (!noAudio) log("주의: 이 영상에 소리 트랙이 없어요")
                            noAudio = true
                        } else if (hasAudio[track] == true) noAudio = false
                    }
                    quality = if (h > 0) "${h}p" else ""
                    val data = fetch(c)
                    o.write(data); o.flush()
                    bytes += data.size
                    segs++
                    curTrackAt = now
                    if (sk != null) { written.add(sk); if (written.size > 3000) written.remove(written.first()) }
                    // 영상 기준 시간(채팅 맞추기용): 끊김 없이 이어질 때만 실제 흐른 시간만큼 더함
                    val wgap = now - lastWriteAt
                    mediaMs += if (lastWriteAt > 0 && wgap < 8000) wgap else 500
                    lastWriteAt = now
                    fails = 0
                    setState("녹화 중")
                    val p = path
                    if (p != null && now - lastScan > 60_000) { lastScan = now; Saver.scan(ctx, p) }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    fails++
                    if (fails <= 5 || fails % 20 == 0) log("조각 받기 실패($fails): ${e.message}")
                }
            }
        } finally {
            closeBlurred()
            closeChat()
            try { out?.flush(); out?.close() } catch (_: Exception) {}
            mediaUri?.let { Saver.finish(ctx, it) }
            path?.let { Saver.scan(ctx, it) }
            // 녹화가 끝난 파일은 별도 작업 공간에서 정리(목차 붙이기) 후 완성 처리
            // 앱이 꺼지는 중(업데이트·재시작)이면 정리하지 않음 → 다시 켜지면 같은 파일에 이어 씀
            if (userStopped) {
                if (bytes > 0 && out != null) Tidy.enqueue(ctx, Tidy.Job(fileName, path, mediaUri, streamer))
                if (out == null) releaseResume("직접 중지")
            }
            destroyPlayer()
            cache.clear()
            state = if (bytes > 0) "끝 · ${bytes / 1024 / 1024}MB 저장" else "끝 · 저장된 영상 없음"
            log(state + if (fileName.isNotEmpty()) " ($fileName)" else "")
            onUpdate()
            RecordService.slotEnded(this)
        }
    }

    /** 현황 화면용: 0 녹화 중, 1 일시정지(이어받기), 2 준비·순서 대기, 3 방송 대기, 4 끝 */
    fun group(): Int = when {
        state.startsWith("끝") -> 4
        state == "녹화 중" -> 0
        state == "일시정지" -> 1
        state == "방송 대기" -> 3
        else -> 2
    }

    /** 현황 화면용 짧은 설명 한 줄 */
    fun detail(): String {
        val now = System.currentTimeMillis()
        val min = if (recordStartAt > 0) (now - recordStartAt) / 60000 else 0
        fun t(m: Long) = if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
        return when (state) {
            "녹화 중" -> listOf(t(min), "${bytes / 1024 / 1024}MB", (quality + " " + codec).trim(), "${rateBps() / 1024}KB/s").filter { it.isNotBlank() }.joinToString("  ·  ") +
                (if (noAudio) "  ·  소리 없음!" else "")
            "일시정지" -> (when (pauseKind) { "ticket" -> if (blurOut != null) "티켓방 (흐린 화면 따로 저장 중)" else "티켓방"; "ended" -> "방송 종료 확인 중"; else -> "끊김" }) +
                "  ·  ${(now - lastSegAt) / 60000}분째  ·  ${bytes / 1024 / 1024}MB"
            "방송 대기" -> "켜지면 자동으로 녹화"
            "화질 대기중" -> "${Quality.label(targetH)} 기다리는 중"
            "순서 대기" -> "속도 한도가 꽉 차서 차례 기다림"
            else -> state
        }
    }

    fun summary(): String {
        val min = if (recordStartAt > 0) (System.currentTimeMillis() - recordStartAt) / 60000 else 0
        return when (state) {
            "일시정지" -> "일시정지 (" + (when (pauseKind) { "ticket" -> if (blurOut != null) "티켓방 · 흐린 화면 따로 저장 중" else "티켓방"; "ended" -> "방송 종료"; else -> "끊김" }) + " · ${(System.currentTimeMillis() - lastSegAt) / 60000}분째) · ${bytes / 1024 / 1024}MB"
            "방송 대기" -> "방송 대기 (켜지면 자동 녹화)"
            "화질 대기중" -> "${Quality.label(targetH)} 대기중"
            "녹화 중" -> "$state · ${min}분 · ${bytes / 1024 / 1024}MB" + (if (quality.isNotEmpty()) " · $quality" else "") +
                (if (codec.isNotEmpty()) " $codec" else "") + (if (noAudio) " · 소리 없음!" else "") +
                " · ${rateBps() / 1024}KB/s"
            "순서 대기" -> "순서 대기 (속도 한도 꽉 참 — 여유 생기면 시작)"
            else -> state
        }
    }
}


/** 동시에 '여는 중'인 재생기 수 제한 — 한꺼번에 여러 방송 페이지를 열면 폰이 버거워 다 같이 늦어짐 */
object Loading {
    private const val MAX = 3
    private const val TIMEOUT = 150_000L   // 이 시간 안에 영상이 안 나오면 자리를 비켜 줌
    private val opening = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val priorityWaiting = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    @Volatile var apiBlocked = false

    @Synchronized fun tryEnter(id: Int, now: Long, priority: Boolean): Boolean {
        opening.entries.removeIf { now - it.value > TIMEOUT }
        priorityWaiting.entries.removeIf { now - it.value > 5_000 }
        if (opening.containsKey(id)) { opening[id] = now; return true }
        // 녹화하던 방송이 기다리고 있으면 새 방송은 양보
        if (!priority && priorityWaiting.isNotEmpty()) return false
        if (opening.size >= MAX) { if (priority) priorityWaiting[id] = now; return false }
        opening[id] = now
        priorityWaiting.remove(id)
        return true
    }

    fun leave(id: Int) { opening.remove(id); priorityWaiting.remove(id) }
}
