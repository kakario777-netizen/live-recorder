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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 녹화 칸 하나 = 보이지 않는 재생기(소리 끔) + 조각 따라 저장기.
 * 재생기는 서비스 안에서 돌기 때문에 앱을 내리거나 화면을 꺼도 계속 재생·녹화된다.
 *
 * 상태: 준비 중 → 720 대기중 → 녹화 중 ⇄ 재연결 중 → 끝
 */
class Slot(
    private val ctx: Context,
    val id: Int,
    val pageUrl: String,
    val ua: String,
    private val onUpdate: () -> Unit,
) {
    companion object {
        const val WAIT_HQ_MS = 60_000L      // 720p를 기다리는 최대 시간 — 넘으면 그때 화질로 시작
        const val STALL_RELOAD_MS = 20_000L // 이만큼 새 조각이 없으면 페이지 다시 열기
        const val GIVE_UP_MS = 15 * 60_000L // 이만큼 계속 없으면 끝 (방송 종료로 판단)
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

    private var out: OutputStream? = null
    private var path: String? = null
    private var mediaUri: android.net.Uri? = null
    private var streamId: String? = null
    private val heightCache = HashMap<String, Int>()
    private val lastInit = HashMap<String, Captured>()

    private fun log(s: String) = RecordService.addLog("[$id $host] $s")
    private fun setState(s: String) { if (state != s) { state = s; log("상태: $s") }; onUpdate() }

    fun start() {
        main.post { createPlayer() }
        Thread({ loop() }, "slot-$id").start()
    }

    /** 사용자가 직접 멈춤 */
    fun stop() { userStopped = true; stopFlag = true }

    /** 앱 종료 등으로 멈춤 (나중에 이어서 녹화할 대상) */
    fun halt() { stopFlag = true }

    // ---------- 숨은 재생기 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayer() {
        val w = WebView(ctx)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
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
                if (Sniff.classify(url) == "seg") q.offer(Captured(url, "seg", req.requestHeaders ?: emptyMap(), pageUrl))
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: return true
                return s != "http" && s != "https"
            }
            override fun onPageFinished(view: WebView, url: String) {
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

    private fun reloadPlayer() {
        main.post { web?.loadUrl(pageUrl) }
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

    // ---------- 조각 저장 ----------

    private fun fetch(c: Captured): ByteArray {
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

    private fun openFile(ext: String): OutputStream {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        fileName = "${host.replace(Regex("[^A-Za-z0-9.-]"), "_")}_$time.$ext"
        val (u, p, o) = Saver.createLive(ctx, fileName, if (ext == "mp4") "video/mp4" else "video/mp2t")
        mediaUri = u; path = p
        log("저장 파일: ${Saver.FOLDER}/$fileName")
        return o
    }

    private fun loop() {
        val seen = HashSet<String>()
        var curTrack: String? = null
        var firstSegAt = 0L
        var lastReload = 0L
        var lastScan = 0L
        var fails = 0
        setState("720 대기중")
        try {
            while (!stopFlag) {
                val c = q.poll(1, TimeUnit.SECONDS)
                val now = System.currentTimeMillis()
                if (c == null) {
                    val idle = now - lastSegAt
                    if (idle > GIVE_UP_MS) { log("15분 동안 영상이 없어 종료 (방송 종료로 판단)"); break }
                    if (idle > STALL_RELOAD_MS && now - lastReload > 30_000) {
                        lastReload = now
                        setState(if (out == null) "720 대기중" else "재연결 중")
                        log("${idle / 1000}초째 새 영상 없음 → 재생기 다시 열기 (티켓방·끊김·방송 종료 확인 중)")
                        reloadPlayer()
                    }
                    continue
                }
                val key = c.url.substringBefore('?')
                if (!seen.add(key)) continue
                if (seen.size > 5000) seen.clear()
                // 처음 들어온 조각으로 이 칸이 따라갈 방송을 정한다 (광고·다른 영상 섞임 방지)
                val sid = Sniff.streamId(c.url)
                if (streamId == null && !Sniff.isInit(c.url)) { streamId = sid; log("방송 번호: $sid") }
                if (streamId != null && sid != streamId) continue
                val track = Sniff.trackOf(c.url)
                if (Sniff.isInit(c.url)) { lastInit[track] = c; continue }
                lastSegAt = now
                if (firstSegAt == 0L) firstSegAt = now
                val mp4 = key.endsWith(".mp4")
                try {
                    var head: ByteArray? = null
                    if (mp4 && track != curTrack) {
                        val init = lastInit[track] ?: Sniff.initFor(track)
                        if (init == null) { log("화질($track) 시작 정보가 아직 없어 건너뜀"); continue }
                        head = fetch(init)
                    }
                    val h = if (!mp4) 0 else heightCache[track]
                        ?: heightOf(track, head ?: lastInit[track]?.let { runCatching { fetch(it) }.getOrNull() })
                    // 시작 전: 720p 이상이 나올 때까지 기다림 (1분 넘으면 그때 화질로 시작)
                    if (out == null) {
                        val waited = now - firstSegAt
                        if (h in 1 until TARGET_HEIGHT && waited < WAIT_HQ_MS) {
                            setState("720 대기중")
                            continue
                        }
                        if (h in 1 until TARGET_HEIGHT) log("1분 동안 720p가 안 잡혀 ${h}p로 녹화 시작")
                        out = openFile(if (mp4) "mp4" else "ts")
                        recordStartAt = now
                        if (head == null && mp4) head = lastInit[track]?.let { fetch(it) }
                    }
                    val o = out ?: continue
                    if (head != null) {
                        o.write(head); bytes += head.size
                        if (curTrack != null) log("화질 바뀜: ${heightCache[curTrack] ?: "?"}p → ${h}p (같은 파일에 이어서 녹화)")
                        curTrack = track
                    }
                    quality = if (h > 0) "${h}p" else ""
                    val data = fetch(c)
                    o.write(data); o.flush()
                    bytes += data.size
                    segs++
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
            try { out?.flush(); out?.close() } catch (_: Exception) {}
            mediaUri?.let { Saver.finish(ctx, it) }
            path?.let { Saver.scan(ctx, it) }
            destroyPlayer()
            state = if (bytes > 0) "끝 · ${bytes / 1024 / 1024}MB 저장" else "끝 · 저장된 영상 없음"
            log(state + if (fileName.isNotEmpty()) " ($fileName)" else "")
            onUpdate()
            RecordService.slotEnded(this)
        }
    }

    fun summary(): String {
        val min = if (recordStartAt > 0) (System.currentTimeMillis() - recordStartAt) / 60000 else 0
        return when (state) {
            "녹화 중", "재연결 중" -> "$state · ${min}분 · ${bytes / 1024 / 1024}MB" + (if (quality.isNotEmpty()) " · $quality" else "") + (if (codec.isNotEmpty()) " $codec" else "")
            else -> state
        }
    }
}
