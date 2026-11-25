package com.kakario.liverecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var info: TextView
    private lateinit var recLabel: TextView
    private lateinit var loading: ProgressBar
    private val prefs by lazy { getSharedPreferences("main", MODE_PRIVATE) }
    private val updater by lazy { Updater(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 아이콘 + 아래 작은 이름 */
    private fun iconBtn(icon: Int, label: String, tint: Int = Color.parseColor("#444444"), onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(4))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
            contentDescription = label
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(icon)
                setColorFilter(tint)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 10.5f; setTextColor(tint); gravity = Gravity.CENTER
                isSingleLine = true
                // 설정 › 아이콘 이름 표시
                visibility = if (prefs.getBoolean("showLabels", true)) View.VISIBLE else View.GONE
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            if (!prefs.getBoolean("showLabels", true)) setPadding(0, dp(9), 0, dp(9))
        }

    /** 아이콘만 (주소줄용) */
    private fun iconOnly(icon: Int, desc: String, onClick: () -> Unit) =
        android.widget.ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Color.parseColor("#444444"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = desc
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun btn(text: String, color: Int = Color.parseColor("#333333"), onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            setTextColor(color)
            minHeight = dp(40); minimumHeight = dp(40)
            minWidth = 0; minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // 위: 주소창
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(4), dp(4)) }
        urlBox = EditText(this).apply {
            hint = "방송 주소 입력"
            textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(); true } else false
            }
        }
        top.addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconOnly(R.drawable.ic_go, "이동") { go() })
        top.addView(iconOnly(R.drawable.ic_refresh, "새로고침") { web.reload() })
        root.addView(top)

        // 둘째 줄: 즐겨찾기 · 설정 · 파일 · 녹화
        val menu = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        menu.addView(iconBtn(R.drawable.ic_star, "즐겨찾기") { showBookmarks() }, LinearLayout.LayoutParams(0, -2, 1f))
        menu.addView(iconBtn(R.drawable.ic_settings, "설정") { showSettingsMenu() }, LinearLayout.LayoutParams(0, -2, 1f))
        menu.addView(iconBtn(R.drawable.ic_folder, "파일") { innerNav = true; startActivity(Intent(this, FilesActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
        val rec = iconBtn(R.drawable.ic_rec, "녹화", Color.parseColor("#D32F2F")) { onRecord() }
        recLabel = rec.getChildAt(1) as TextView
        recLabel.setTypeface(null, Typeface.BOLD)
        menu.addView(rec, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(menu)
        // 뷰어 모드: 화면 전체를 연한 하늘색으로 + 위에 띠 (녹화폰을 보고 있다는 표시)
        if (CloudBm.viewer(this)) {
            val sky = Color.parseColor("#E3F2FD")
            root.setBackgroundColor(sky); top.setBackgroundColor(sky); menu.setBackgroundColor(sky)
            root.addView(TextView(this).apply {
                text = "뷰어 모드 · 녹화폰을 보고 있어요 (여기서 하는 건 녹화폰에 적용)"; textSize = 12f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1E88E5")); setPadding(dp(14), dp(5), dp(14), dp(5))
                setOnClickListener { showCloudBm() }
            }, 0)
        }

        // 셋째 줄: 받는 중 현황 한 줄 (누르면 상세)
        info = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#F3F3F3"))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { onStatusTap() }
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2))

        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(loading, LinearLayout.LayoutParams(-1, dp(3)))

        webFrame = FrameLayout(this)
        root.addView(webFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        // 맨 아래: 브라우저 버튼 (뒤로 · 앞으로 · 홈 · 맨 위 · 공유)
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(2), 0, dp(2), 0)
        }
        nav.addView(iconBtn(R.drawable.ic_back, "뒤로") { if (web.canGoBack()) web.goBack() }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(iconBtn(R.drawable.ic_forward, "앞으로") { if (web.canGoForward()) web.goForward() }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(iconBtn(R.drawable.ic_home, "홈") { goHome() }.apply {
            setOnLongClickListener {
                val u = web.url ?: return@setOnLongClickListener true
                prefs.edit().putString("home", u).apply()
                Toast.makeText(this@MainActivity, "이 페이지를 홈으로 정했어요", Toast.LENGTH_SHORT).show()
                true
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(iconBtn(R.drawable.ic_top, "맨 위") { web.evaluateJavascript("window.scrollTo(0,0)", null); web.scrollTo(0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val tabV = iconBtn(R.drawable.ic_tabs, "탭 1") { showTabs() }
        tabLabel = tabV.getChildAt(1) as TextView
        nav.addView(tabV, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(iconBtn(R.drawable.ic_share, "공유") {
            val u = web.url ?: return@iconBtn
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "주소 공유"))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(nav)

        setContentView(root)
        root.fitSystemBars()

        Sniff.listener = { runOnUiThread { refresh() } }
        RecordService.onChange = { runOnUiThread { refresh() } }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val shared = sharedUrl(intent)
        restoreTabs(shared ?: if (prefs.getString("tabs", null) == null) prefs.getString("last", null) else null)
        refresh()
        updater.check(silent = true)
        askResume()
        RecordService.startWatch(this)
        RecordService.startUploads(this)
        // 폰끼리 동기화: 켤 때 즐겨찾기·설정 맞추기, 담당 폰이면 명령 대기 시작
        Sync.pushSoon(this)
        Sync.startRecorderService(this)
    }

    private var batteryHelpShown = false
    override fun onResume() {
        super.onResume()
        updater.onResume()
        CloudBm.startLoop(this)
        if (CloudBm.on(this)) Thread { CloudBm.sync(applicationContext) }.start()
        // 화면 꺼지면 변환이 멈췄던 폰인데 아직 배터리 최적화 제외가 안 돼 있으면 다시 안내
        if (ScreenOff.stalled(this) && !ScreenOff.exempt(this) && !batteryHelpShown) { batteryHelpShown = true; batteryHelp() }
        // 검은 화면 켜 두기가 켜져 있고 할 일이 있으면 다시 띄움
        else if (KeepOnActivity.on(this) && (Tidy.busy() || Uploader.busy())) startActivity(Intent(this, KeepOnActivity::class.java))
        if (RecordService.running) RecordService.addLog("앱 화면 열림 (탭 ${tabs.size}개" + (web.url?.let { ", 지금 탭: " + Names.siteName(it) } ?: "") + ")")
    }

    override fun onStop() {
        super.onStop()
        saveTabs()
        // 앱을 나갈 때 받아 둔 업데이트 설치 (녹화·변환 중이면 안 함)
        if (!isChangingConfigurations && !innerNav) updater.installOnLeave()
        innerNav = false
    }

    /** 앱 안 다른 화면(파일 목록)으로 갈 때는 '앱 나감'으로 치지 않음 */
    private var innerNav = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrl(intent)?.let { newTab(it) }
        intent.getStringExtra("openUrl")?.let { u -> urlBox.setText(u); Sniff.clear(); web.loadUrl(u) }
        if (intent.getBooleanExtra("batteryHelp", false)) batteryHelp()
    }

    /** 화면 꺼지면 변환이 멈추는 폰: 할 일 안내 + 설정 화면 바로 열기 */
    @SuppressLint("BatteryLife")
    private fun batteryHelp() {
        val ex = ScreenOff.exempt(this)
        val msg = (if (!ex) "1) 아래 '배터리 최적화 제외'를 눌러 '허용'을 눌러 주세요.\n\n" else "배터리 최적화 제외는 이미 돼 있어요.\n\n") +
            "2) '앱 설정 열기' → 배터리 → '제한 없음'(또는 '절전 예외', '사용하지 않는 앱을 절전 상태로 전환' 끄기)으로 바꿔 주세요.\n\n" +
            "그래도 화면이 꺼지면 느려지거나 멈추면 '검은 화면 켜 두기'를 쓰세요. 화면을 밝기 최저의 검은 화면으로 켜 둔 채 변환해서, 폰이 앱을 재우거나 끄지 못해요 (충전기에 꽂아 두세요)."
        AlertDialog.Builder(this).setTitle("화면이 꺼지면 변환이 멈춰요")
            .setMessage(msg)
            .setPositiveButton(if (!ex) "배터리 최적화 제외" else "확인") { _, _ ->
                if (!ex) try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
            .setNeutralButton("앱 설정 열기") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
            .setNegativeButton("검은 화면 켜 두기") { _, _ -> startActivity(Intent(this, KeepOnActivity::class.java)) }
            .show()
    }

    private fun sharedUrl(i: Intent?): String? {
        if (i?.action != Intent.ACTION_SEND) return null
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""https?://\S+""").find(t)?.value
    }

    private lateinit var webFrame: FrameLayout
    private var popup: View? = null
    private var popupWeb: WebView? = null

    /**
     * 구글은 앱 속 브라우저(웹뷰) 로그인을 막는다. 브라우저 정보에서 웹뷰 표시('; wv')를 빼고
     * 앱 이름이 담기는 X-Requested-With 머리말도 끈다.
     */
    private val chromeUa: String by lazy {
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv)", ")")
            .replace(Regex("""Version/\d+(\.\d+)* """), "")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = chromeUa
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet())
        }
    }

    private fun sniff(req: WebResourceRequest) {
        val url = req.url.toString()
        val kind = Sniff.classify(url)
        if (kind != null) Sniff.add(Captured(url, kind, req.requestHeaders ?: emptyMap(), currentPage))
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            loading.progress = p
            loading.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }

        // 사이트가 새 창(구글 로그인 등)을 열면 화면 위에 띄운다
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            closePopup()
            val pw = WebView(this@MainActivity)
            configure(pw)
            pw.webChromeClient = this
            pw.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? { sniff(req); return null }
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    val sc = req.url.scheme ?: return true
                    return sc != "http" && sc != "https"
                }
                override fun onPageFinished(v: WebView, url: String) { CookieManager.getInstance().flush() }
            }
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                isClickable = true
            }
            val bar = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#F2F2F2"))
                setPadding(dp(12), 0, dp(4), 0)
            }
            bar.addView(TextView(this@MainActivity).apply { text = "로그인 창"; textSize = 14f; setTextColor(Color.parseColor("#333333")) },
                LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(btn("닫기") { closePopup() })
            box.addView(bar)
            box.addView(pw, LinearLayout.LayoutParams(-1, 0, 1f))
            webFrame.addView(box, FrameLayout.LayoutParams(-1, -1))
            popup = box
            popupWeb = pw
            (resultMsg.obj as WebView.WebViewTransport).webView = pw
            resultMsg.sendToTarget()
            return true
        }

        // 로그인이 끝나면 사이트가 창을 스스로 닫는다
        override fun onCloseWindow(window: WebView) {
            if (window == popupWeb) closePopup()
        }
    }

    private fun closePopup() {
        val p = popup ?: return
        webFrame.removeView(p)
        popupWeb?.destroy()
        popup = null
        popupWeb = null
        CookieManager.getInstance().flush()
    }

    private fun setupWeb(w: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        configure(w)
        ChatHub.hook(this, w)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                sniff(req)
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: return true
                return s != "http" && s != "https"
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // 보는 화면만 죽은 경우: 앱(녹화)은 그대로 두고 화면만 다시 만든다
                runOnUiThread { recreate() }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view !== web) return
                currentPage = url
                urlBox.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("http")) Quality.remember(this@MainActivity, Names.siteName(url))
                CookieManager.getInstance().flush()
                if (view !== web) return
                currentPage = url
                prefs.edit().putString("last", url).apply()
            }
        }
        w.webChromeClient = chrome
    }

    // ---------- 탭 ----------

    private val tabs = ArrayList<WebView>()
    private lateinit var tabLabel: TextView

    private fun newTab(url: String?, switchTo: Boolean = true): WebView {
        val w = WebView(this)
        setupWeb(w)
        tabs.add(w)
        webFrame.addView(w, 0, FrameLayout.LayoutParams(-1, -1))
        if (switchTo || tabs.size == 1) switchTab(w) else w.visibility = View.GONE
        if (url != null) w.loadUrl(url)
        updateTabBtn()
        return w
    }

    private fun switchTab(w: WebView) {
        tabs.forEach { it.visibility = if (it === w) View.VISIBLE else View.GONE }
        web = w
        currentPage = w.url ?: ""
        urlBox.setText(w.url ?: "")
        Sniff.clear()
        updateTabBtn()
    }

    private fun closeTab(w: WebView) {
        val i = tabs.indexOf(w)
        if (i < 0) return
        tabs.removeAt(i)
        webFrame.removeView(w)
        w.destroy()
        if (tabs.isEmpty()) newTab(prefs.getString("home", null))
        else if (w === web) switchTab(tabs[minOf(i, tabs.size - 1)])
        updateTabBtn()
    }

    private fun updateTabBtn() { if (::tabLabel.isInitialized) tabLabel.text = "탭 ${tabs.size}" }

    private fun saveTabs() {
        val a = org.json.JSONArray()
        tabs.forEach { a.put(it.url ?: "") }
        prefs.edit().putString("tabs", a.toString()).putInt("tabIndex", tabs.indexOf(web).coerceAtLeast(0)).apply()
    }

    /** 앱을 켤 때: 지난번에 열어 둔 탭들 복원 */
    private fun restoreTabs(start: String?) {
        val saved = try { org.json.JSONArray(prefs.getString("tabs", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        val urls = (0 until saved.length()).map { saved.optString(it) }.filter { it.startsWith("http") }
        if (urls.isEmpty()) { newTab(start); return }
        val idx = prefs.getInt("tabIndex", 0).coerceIn(0, urls.size - 1)
        urls.forEachIndexed { i, u -> newTab(u, switchTo = i == idx) }
        if (start != null && start != urls.getOrNull(idx)) newTab(start)
    }

    /** 탭 목록: 누르면 이동, ✕로 닫기, '+ 새 탭' */
    private fun showTabs() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4)) }
        lateinit var dlg: AlertDialog
        tabs.forEach { w ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), 0, dp(6))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { dlg.dismiss(); switchTab(w) }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = (if (w === web) "● " else "") + (w.title?.takeIf { it.isNotBlank() } ?: "새 탭")
                textSize = 14f; setTextColor(Color.parseColor(if (w === web) "#D32F2F" else "#222222"))
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this).apply {
                text = w.url ?: ""; textSize = 11f; setTextColor(Color.parseColor("#888888"))
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(iconOnly(R.drawable.ic_close, "탭 닫기") { dlg.dismiss(); closeTab(w); showTabs() })
            box.addView(row)
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("열린 탭 (${tabs.size})")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("+ 새 탭") { _, _ ->
                newTab(prefs.getString("home", null))
                urlBox.requestFocus()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    @Volatile private var currentPage = ""

    private fun go() {
        var u = urlBox.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        Sniff.clear()
        web.loadUrl(u)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBox.windowToken, 0)
        web.requestFocus()
    }

    private fun refresh() {
        val n = Sniff.hlsCount()
        val others = Sniff.list().size - n
        info.text = when {
            Sync.isRemote(this) -> "리모컨 폰 · 누르면 담당 폰 현황"
            RecordService.running -> {
                val l = RecordService.list()
                val rec = l.count { it.state == "녹화 중" }
                val wait = l.count { it.state == "방송 대기" || it.state == "순서 대기" }
                val paused = l.count { it.state == "일시정지" }
                val mb = l.sumOf { it.bytes } / 1024 / 1024
                "받는 중 ${l.count { it.isDownloading }}개 · ${RecordService.rateText()} · 대기 $wait" +
                    (if (paused > 0) " · 일시정지 $paused" else "") + " · ${RecordService.totalRate() / 1024}KB/s · ${mb}MB  ›"
            }
            n > 0 || Sniff.latestSegAny() != null -> "영상 찾음 — 녹화를 누르세요"
            others > 0 -> "다른 형식 영상 주소 ${others}개 (진단 저장 후 보내 주세요)"
            else -> "방송을 재생하면 영상 주소를 자동으로 찾아요"
        }
        recLabel.text = when {
            Sync.isRemote(this) -> "담당 폰"
            RecordService.running -> "녹화 ${RecordService.list().size}"
            else -> "녹화"
        }
    }

    private fun onRecord() {
        when {
            CloudBm.viewer(this) -> startActivityForResult(Intent(this, RemoteStatusActivity::class.java), StatusActivity.REQ)   // 뷰어: 녹화폰 녹화 목록
            Sync.isRemote(this) -> showRemoteStatus()
            RecordService.running -> showSlots()
            else -> addRecording()
        }
    }

    /** 녹화 요청: 이 폰이 리모컨이면 담당 폰으로 명령, 아니면 이 폰에서 녹화 */
    private fun requestRecord(url: String, ua: String, name: String) {
        if (CloudBm.viewer(this)) {
            CloudBm.requestRecord(this, url, name)
            Toast.makeText(this, "뷰어 모드: 녹화폰에 '${name.ifEmpty { "이 방송" }}' 녹화를 부탁했어요", Toast.LENGTH_LONG).show()
            return
        }
        if (Sync.isRemote(this)) {
            Sync.sendCommand(this, "add", url, name, ua) { r -> runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() } }
            return
        }
        askBattery()
        RecordService.add(this, url, ua, name)
        Toast.makeText(this, "${name.ifEmpty { hostOf(url) }} 녹화를 추가했어요. 꺼져 있으면 켜질 때까지 기다리고, 720p가 잡히면 시작해요. 화면을 꺼도 계속돼요.", Toast.LENGTH_LONG).show()
    }

    /** 리모컨 폰: 담당 폰의 녹화 현황 보기·중지 */
    private fun showRemoteStatus() {
        Toast.makeText(this, "담당 폰 현황 불러오는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val st = Sync.fetchStatus(this)
            runOnUiThread {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
                lateinit var dlg: AlertDialog
                if (st == null) {
                    box.addView(TextView(this).apply { text = "현황을 불러오지 못했어요.\n담당 폰에서 앱을 한 번 열었는지, NAS 설정이 같은지 확인해 주세요." })
                } else {
                    val age = if (st.ageSec < 90) "${st.ageSec}초 전" else "${st.ageSec / 60}분 전"
                    box.addView(TextView(this).apply {
                        text = "${st.device} · $age 기준 · 업로드 대기 ${st.uploads}개" + if (st.ageSec > 120) "\n(담당 폰 응답이 늦어요 — 꺼졌거나 인터넷이 끊겼을 수 있어요)" else ""
                        textSize = 12f; setTextColor(Color.parseColor("#666666"))
                    })
                    if (st.slots.isEmpty()) box.addView(TextView(this).apply { text = "\n녹화 중인 방송이 없어요"; textSize = 14f })
                    st.slots.forEach { sl ->
                        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                        row.addView(TextView(this).apply {
                            text = "${sl.name}\n${sl.summary}"; textSize = 13f; setTextColor(Color.parseColor("#222222"))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(btn("중지", Color.parseColor("#D32F2F")) {
                            AlertDialog.Builder(this).setMessage("${sl.name} 녹화를 멈출까요?")
                                .setPositiveButton("중지") { _, _ ->
                                    dlg.dismiss()
                                    Sync.sendCommand(this, "stop", sl.url) { r -> runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() } }
                                }.setNegativeButton("취소", null).show()
                        })
                        box.addView(row)
                    }
                }
                dlg = AlertDialog.Builder(this)
                    .setTitle("녹화 담당 폰 현황")
                    .setView(ScrollView(this).apply { addView(box) })
                    .setPositiveButton("이 방송 녹화 요청") { _, _ -> addRecording() }
                    .setNeutralButton("새로고침") { _, _ -> showRemoteStatus() }
                    .setNegativeButton("닫기", null)
                    .show()
            }
        }.start()
    }

    /** 녹화 현황: 칸마다 상태와 중지 버튼, 아래에 '이 방송 추가' */
    private fun showSlots() {
        // 뷰어 모드: 이 폰은 녹화를 안 하니 '녹화 현황'은 녹화폰 현황으로
        if (CloudBm.viewer(this)) { startActivityForResult(Intent(this, RemoteStatusActivity::class.java), StatusActivity.REQ); return }
        startActivityForResult(Intent(this, StatusActivity::class.java), StatusActivity.REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (data.getStringExtra("action")) {
            "add" -> if (requestCode == BookmarksActivity.REQ) addBookmark(data.getStringExtra(BookmarksActivity.EXTRA_CAT)) else addRecording()
            "open" -> data.getStringArrayExtra(BookmarksActivity.EXTRA_URLS)?.firstOrNull()?.let { u -> urlBox.setText(u); Sniff.clear(); web.loadUrl(u) }
            "record" -> {
                val urls = data.getStringArrayExtra(BookmarksActivity.EXTRA_URLS) ?: return
                val names = data.getStringArrayExtra(BookmarksActivity.EXTRA_NAMES) ?: return
                if (urls.size == 1) requestRecord(urls[0], web.settings.userAgentString, Names.clean(names[0]))
                else {
                    if (CloudBm.viewer(this)) urls.indices.forEach { i -> CloudBm.requestRecord(this, urls[i], Names.clean(names[i])) }
                    else if (Sync.isRemote(this)) urls.indices.forEach { i -> Sync.sendCommand(this, "add", urls[i], Names.clean(names[i]), web.settings.userAgentString) {} }
                    else { askBattery(); urls.indices.forEach { i -> RecordService.add(this, urls[i], web.settings.userAgentString, Names.clean(names[i])) } }
                    Toast.makeText(this, "${urls.size}개를 녹화에 걸었어요 (방송 중이 아니면 방송 대기)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSlotsOld() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val head = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(0, 0, 0, dp(6)) }
        lateinit var dlg: AlertDialog
        // 칸마다 글자칸을 기억해 두고 1초마다 내용만 바꿈 (실시간 현황)
        val rows = HashMap<Int, TextView>()
        fun build() {
            box.removeAllViews(); rows.clear()
            box.addView(head)
            RecordService.list().forEach { sl ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                val tv = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#222222")) }
                rows[sl.id] = tv
                row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(btn("다시 시도", Color.parseColor("#1565C0")) {
                    val go = {
                        sl.retryNow()
                        Toast.makeText(this, "${sl.id}번 다시 여는 중 (같은 파일에 이어서 녹화)", Toast.LENGTH_SHORT).show()
                    }
                    if (sl.state == "녹화 중") AlertDialog.Builder(this).setMessage("지금 잘 받는 중이에요. 그래도 다시 열까요? (몇 초 빠질 수 있어요)")
                        .setPositiveButton("다시 열기") { _, _ -> go() }.setNegativeButton("취소", null).show()
                    else go()
                })
                row.addView(btn("중지", Color.parseColor("#D32F2F")) {
                    AlertDialog.Builder(this).setMessage("${sl.id}번 녹화를 멈출까요? 지금까지 녹화한 건 저장돼요.")
                        .setPositiveButton("중지") { _, _ -> RecordService.stopOne(this, sl.id) }
                        .setNegativeButton("취소", null).show()
                })
                box.addView(row)
            }
            if (rows.isEmpty()) box.addView(TextView(this).apply { text = "녹화 중인 방송이 없어요"; textSize = 14f })
        }
        fun update() {
            val l = RecordService.list()
            if (l.map { it.id }.toSet() != rows.keys) build()
            head.text = "받는 중 ${l.count { it.isDownloading }}개 · ${RecordService.rateText()} · 등록 ${l.size} · 합계 ${l.sumOf { it.bytes } / 1024 / 1024}MB"
            l.forEach { sl -> rows[sl.id]?.text = "${sl.id}. ${sl.streamer.ifEmpty { sl.host }}\n${sl.summary()}" }
        }
        build(); update()
        val ticker = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() { if (dlg.isShowing) { update(); ticker.postDelayed(this, 1000) } }
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("녹화 현황 (실시간)")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("이 방송 추가") { _, _ -> addRecording() }
            .setNeutralButton("모두 중지") { _, _ ->
                AlertDialog.Builder(this).setMessage("모든 녹화를 멈출까요?")
                    .setPositiveButton("모두 중지") { _, _ -> RecordService.stopAll(this) }
                    .setNegativeButton("취소", null).show()
            }
            .setNegativeButton("닫기", null)
            .show()
        dlg.setOnDismissListener { ticker.removeCallbacksAndMessages(null) }
        ticker.postDelayed(tick, 1000)
    }

    /** 지금 보고 있는 방송 페이지를 숨은 재생기로 녹화 추가 */
    private fun addRecording() {
        val page = web.url ?: currentPage
        if (page.isBlank() || !page.startsWith("http")) {
            Toast.makeText(this, "먼저 방송 페이지를 열어 주세요", Toast.LENGTH_LONG).show(); return
        }
        if (Sync.isRemote(this)) { confirmAdd(page); return }
        askBattery()
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("askedOverlay2", false)) {
            prefs.edit().putBoolean("askedOverlay2", true).apply()
            AlertDialog.Builder(this)
                .setTitle("화면을 꺼도 녹화되게 하려면")
                .setMessage("'다른 앱 위에 표시'를 허용해 주세요.\n눈에 안 보이는 창으로 재생을 유지해서, 앱을 내리거나 화면을 꺼도 녹화가 이어져요.\n허용하고 돌아와서 녹화를 다시 눌러 주세요.")
                .setPositiveButton("허용하러 가기") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
                .setNegativeButton("그냥 녹화") { _, _ -> confirmAdd(page) }
                .show()
            return
        }
        confirmAdd(page)
    }

    private fun confirmAdd(page: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val img = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
        box.addView(img, LinearLayout.LayoutParams(-1, dp(160)))
        val tv = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            text = "${hostOf(page)}\n화면 불러오는 중…"
        }
        box.addView(tv)

        // 파일 이름: 사이트명 + 스트리머 이름 + 날짜시간 (넣을 부분 고르기, 이름은 고칠 수 있음)
        val guess = Names.streamerFromUrl(page).ifEmpty { Names.streamerFromTitle(web.title ?: "") }
        box.addView(TextView(this).apply {
            text = "파일 이름"; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#222222")); setPadding(0, dp(12), 0, 0)
        })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val cSite = android.widget.CheckBox(this).apply { text = "사이트명"; textSize = 13f; isChecked = Names.useSite(this@MainActivity) }
        val cName = android.widget.CheckBox(this).apply { text = "스트리머"; textSize = 13f; isChecked = Names.useStreamer(this@MainActivity) }
        val cTime = android.widget.CheckBox(this).apply { text = "날짜시간"; textSize = 13f; isChecked = Names.useTime(this@MainActivity) }
        row.addView(cSite); row.addView(cName); row.addView(cTime)
        box.addView(row)
        val nameBox = EditText(this).apply {
            hint = "스트리머 이름"
            textSize = 14f
            isSingleLine = true
            setText(guess)
        }
        box.addView(nameBox)
        val preview = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#666666")) }
        box.addView(preview)
        fun updatePreview() {
            Names.userSetParts(this, cSite.isChecked, cName.isChecked, cTime.isChecked)
            preview.text = "예: " + Names.build(this, page, nameBox.text.toString()) + ".mp4"
        }
        listOf(cSite, cName, cTime).forEach { it.setOnCheckedChangeListener { _, _ -> updatePreview() } }
        nameBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { updatePreview() }
        })
        updatePreview()

        val ua = web.settings.userAgentString
        AlertDialog.Builder(this)
            .setTitle("이 방송을 녹화할까요?")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (Sync.isRemote(this)) "담당 폰에서 녹화" else "녹화 시작") { _, _ ->
                requestRecord(page, ua, Names.clean(nameBox.text.toString()))
            }
            .setNegativeButton("취소", null)
            .show()
        val seg = Sniff.latestSegAny()
        if (seg == null) { tv.text = "${hostOf(page)}\n(방송을 재생하면 미리보기가 나와요 — 녹화는 그냥 시작해도 돼요)"; return }
        Thread {
            val r = Preview.fromPlayer(this, Sniff.streamId(seg.url), ua)
            runOnUiThread {
                tv.text = "${hostOf(page)}\n${r.text}"
                r.bitmap?.let { img.setImageBitmap(it) }
            }
        }.start()
    }

    /** 홈: 길게 눌러 정한 페이지, 없으면 마지막으로 연 방송 사이트 첫 화면 */
    private fun goHome() {
        val h = prefs.getString("home", null)
            ?: (web.url ?: prefs.getString("last", null))?.let { u -> "https://" + hostOf(u) }
        if (h == null) { Toast.makeText(this, "홈이 없어요. 원하는 페이지에서 '홈'을 길게 누르세요.", Toast.LENGTH_LONG).show(); return }
        urlBox.setText(h); Sniff.clear(); web.loadUrl(h)
    }

    /** 현황 줄을 눌렀을 때: 녹화 현황 상세 (리모컨 폰이면 담당 폰 현황) */
    private fun onStatusTap() {
        when {
            Sync.isRemote(this) -> showRemoteStatus()
            RecordService.running -> showSlots()
            else -> AlertDialog.Builder(this)
                .setTitle("녹화 현황")
                .setMessage("지금 녹화 중인 방송이 없어요.\n방송 페이지를 열고 ● 녹화를 누르거나, 즐겨찾기에서 '녹화'를 누르세요." +
                    (if (RecordService.status.isNotBlank() && RecordService.status != "녹화 없음") "\n\n최근: ${RecordService.status}" else ""))
                .setPositiveButton("확인", null)
                .setNeutralButton("기록 보기") { _, _ -> showLog() }
                .show()
        }
    }

    /** 설정: 구역마다 제목과 구분선, 켜고 끄는 건 토글 */
    private fun showSettingsMenu() {
        val ctx = this
        var dlg: AlertDialog? = null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        fun section(title: String) {
            box.addView(TextView(ctx).apply {
                text = title; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#1565C0")); setPadding(dp(22), dp(16), dp(22), dp(6))
            })
        }
        fun line() = box.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#E6E6E6")) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(22), 0, dp(22), 0) })
        fun row(title: String, sub: String = "", onClick: () -> Unit) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(12))
                isClickable = true; background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
                setOnClickListener { dlg?.dismiss(); onClick() }
            }
            r.addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(Color.parseColor("#222222")) })
            if (sub.isNotEmpty()) r.addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(Color.parseColor("#777777")) })
            box.addView(r)
        }
        fun toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
            val r = LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(8), dp(14), dp(8)) }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(Color.parseColor("#222222")) })
            if (sub.isNotEmpty()) texts.addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(Color.parseColor("#777777")) })
            r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = android.widget.Switch(ctx).apply { isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) } }
            r.addView(sw)
            r.setOnClickListener { sw.toggle() }
            box.addView(r)
        }
        val role = if (CloudBm.recordHere(this)) "녹화폰" else if (CloudBm.viewer(this)) "원격폰 · 뷰어 모드" else "원격폰 · 다운로드 모드"

        section("녹화")
        row("다운 화질", "사이트별 최대 화질") { showQualitySettings() }
        line()
        toggle("채팅은 한국어 방송만 저장", "처음 채팅 15줄쯤 보고 한국어가 있을 때만", prefs.getBoolean(CloudBm.chatKey(this), true)) { v ->
            prefs.edit().putBoolean(CloudBm.chatKey(ctx), v).apply(); CloudBm.settingsChanged(ctx)
        }

        line()
        toggle("변환 기록 남기기", "파일마다 크기·걸린 시간·속도를 기록 (아래에서 보기·복사)", prefs.getBoolean("convLog", false)) { v ->
            prefs.edit().putBoolean("convLog", v).apply()
        }
        line()
        toggle("검은 화면 켜 두기", "화면이 꺼지면 변환이 느려지는 폰용 · 할 일이 있으면 밝기 최저 검은 화면으로 켜 둔 채 변환 (충전 중 권장)", KeepOnActivity.on(ctx)) { v ->
            KeepOnActivity.set(ctx, v)
            if (v) startActivity(Intent(ctx, KeepOnActivity::class.java))
        }
        line()
        row("변환 기록 보기", "기록 남기기를 켠 뒤 변환된 파일들") {
            val t = try { Tidy.convLogFile(ctx).readText() } catch (e: Exception) { "" }
            val body = t.lines().filter { it.isNotBlank() }.takeLast(200).reversed().joinToString("\n").ifEmpty { "아직 기록이 없어요" }
            AlertDialog.Builder(ctx).setTitle("변환 기록 (최근 것부터)")
                .setMessage(body)
                .setPositiveButton("복사") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("변환 기록", body))
                    Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("닫기", null).show()
        }

        section("폰 연결")
        row("폰 역할 · 즐겨찾기 맞추기", "지금: $role") { showCloudBm() }
        line()
        if (!CloudBm.recordHere(this)) { row("녹화폰 현황 보기", "녹화폰에서 받는 중인 방송") { startActivity(Intent(ctx, RemoteStatusActivity::class.java)) }; line() }
        row("휴지통 보관", "업로드 끝난 원본을 지우기 전까지 · 지금: " + (if (CloudBm.viewer(this)) "녹화폰에 적용" else Uploader.trashLabel(this))) {
            val ch = Uploader.TRASH_CHOICES
            AlertDialog.Builder(ctx).setTitle(if (CloudBm.viewer(ctx)) "녹화폰 휴지통 보관 시간" else "휴지통 보관 시간")
                .setSingleChoiceItems(ch.map { Uploader.hoursLabel(it) }.toTypedArray(), ch.indexOf(Uploader.trashHours(ctx))) { d, i ->
                    Uploader.setTrashHours(ctx, ch[i])
                    if (CloudBm.viewer(ctx)) CloudBm.sendCommand(ctx, "trashh", ch[i].toString())
                    Toast.makeText(ctx, "휴지통 보관: ${Uploader.hoursLabel(ch[i])}", Toast.LENGTH_SHORT).show(); d.dismiss()
                }.setNegativeButton("닫기", null).show()
        }
        line()
        row("NAS 업로드", "정리된 파일 자동 업로드 설정") { innerNav = true; startActivity(Intent(ctx, FilesActivity::class.java).putExtra("openFtp", true)) }

        section("화면")
        toggle("아이콘 아래 이름 표시", "", prefs.getBoolean("showLabels", true)) { v ->
            prefs.edit().putBoolean("showLabels", v).apply(); dlg?.dismiss(); saveTabs(); recreate()
        }

        section("기록 · 도움")
        row("녹화 기록 보기", "최근 6시간 기록 복사") { showLog() }
        line()
        row("진단", "문제가 생겼을 때") { diagnose() }
        line()
        row("업데이트 확인", "") { updater.check(silent = false) }
        if (Lab.on) { line(); row("실험실", "가볍게 받기 시험") { showLab() } }

        dlg = AlertDialog.Builder(this)
            .setTitle("설정")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 실험판: 숨은 재생기 없이 가볍게 받기 시험 */
    private fun showLab() {
        val items = arrayOf(
            "★ 한 번에 시험 (2분): 방송 확인 + 재생기 없이 녹화 + 채팅 직접",
            "티켓방 조사 (지금 보고 있는 방송, 약 40초)",
            "② 직접 받기 시험 (녹화 중인 방송, 90초)",
            "③ 결과 복사 (붙여넣어 보내 주세요)",
            "진행 상황 보기",
            "결과 지우고 처음부터")
        AlertDialog.Builder(this).setTitle("실험실")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        Lab.allInOne(this, 120) {}
                        showLabProgress()
                    }
                    1 -> {
                        val u = web.url ?: ""
                        if (!u.contains("stripchat")) { Toast.makeText(this, "먼저 티켓방 방송 페이지를 열어 주세요", Toast.LENGTH_LONG).show(); return@setItems }
                        Lab.ticketProbe(this, u) {}
                        showLabProgress()
                    }
                    99 -> {
                        val names = LinkedHashSet<String>()
                        var host = "ko.stripchat.com"
                        RecordService.list().forEach { sl -> Names.streamerFromUrl(sl.pageUrl).takeIf { it.isNotEmpty() }?.let { names.add(it); host = sl.host } }
                        Bookmarks.list(this).forEach { b -> if (b.url.contains("stripchat")) Names.streamerFromUrl(b.url).takeIf { it.isNotEmpty() }?.let { names.add(it) } }
                        Toast.makeText(this, "${names.size}명 확인 시작… 끝나면 알려 드려요", Toast.LENGTH_SHORT).show()
                        // 사이트 정보 주소는 막힘(418)이 확인돼서, 방송 서버 목록 주소로 확인. 꺼진 방송 비교용 번호 2개 포함
                        Lab.masterCheckTest(this, listOf("1000001", "262938061")) {}
                        showLabProgress()
                    }
                    2 -> {
                        val ids = Lab.probes.values.filter { it.playlists.isNotEmpty() }.map { it.slotId }.take(3)
                        if (ids.isEmpty()) { Toast.makeText(this, "녹화 중인 방송이 1분 이상 받아진 뒤 다시 눌러 주세요", Toast.LENGTH_LONG).show(); return@setItems }
                        var left = ids.size
                        Toast.makeText(this, "${ids.size}개 방송 직접 받기 90초 시험 시작", Toast.LENGTH_SHORT).show()
                        ids.forEach { id -> Lab.directTest(this, id, 90) { left-- } }
                        showLabProgress()
                    }
                    3 -> {
                        val path = Lab.export(this)
                        Toast.makeText(this, "복사했어요. 채팅에 붙여넣어 주세요\n(파일: $path)", Toast.LENGTH_LONG).show()
                    }
                    4 -> showLabProgress()
                    5 -> { Lab.clear(); Toast.makeText(this, "지웠어요", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("닫기", null).show()
    }

    /** 실험 진행 상황: 1초마다 새로 고침 (진행 중 / 끝, 최근 기록) */
    private fun showLabProgress() {
        val tv = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#222222")); setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true) }
        val sv = ScrollView(this).apply { addView(tv) }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var dlg: AlertDialog? = null
        val tick = object : Runnable {
            override fun run() {
                val head = if (Lab.progress.isEmpty()) "아직 시작한 시험이 없어요" else Lab.progress.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                val state = if (Lab.running > 0) "진행 중이에요… (이 창은 닫아도 계속 돼요)" else if (Lab.progress.isNotEmpty()) "모두 끝났어요. 아래 '결과 복사'를 눌러 주세요" else ""
                val atBottom = !sv.canScrollVertically(1)
                tv.text = "$state\n\n$head\n\n— 최근 기록 —\n" + Lab.recent(40) + "\n\n" + Lab.memText(this@MainActivity)
                if (atBottom) sv.post { sv.fullScroll(android.view.View.FOCUS_DOWN) }
                dlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = Lab.running == 0
                if (dlg?.isShowing == true) h.postDelayed(this, 1000)
            }
        }
        dlg = AlertDialog.Builder(this).setTitle("실험 진행 상황").setView(sv)
            .setPositiveButton("결과 복사") { _, _ ->
                val path = Lab.export(this)
                Toast.makeText(this, "복사했어요. 채팅에 붙여넣어 주세요\n(파일: $path)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("닫기", null).show()
        tick.run()
    }

    /** 설정 › 다운 화질: 사이트마다 최대 화질(1080p / 720p / 480p) 고르기 */
    private fun showQualitySettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(12), dp(8)) }
        box.addView(TextView(this).apply {
            text = "사이트마다 녹화할 최대 화질을 골라요. 그 화질이 없으면 바로 아래 화질로 받아요.\n(새로 시작하는 녹화부터 적용)"
            textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(0, 0, 0, dp(8))
        })
        fun row(label: String, current: Int, onPick: (Int) -> Unit) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.parseColor("#222222")) },
                LinearLayout.LayoutParams(0, -2, 1f))
            val buttons = ArrayList<Button>()
            Quality.CHOICES.forEach { h ->
                val b = btn("${h}p") {}
                buttons.add(b)
                r.addView(b)
            }
            fun paint(sel: Int) = buttons.forEachIndexed { i, b ->
                val on = Quality.CHOICES[i] == sel
                b.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#555555"))
                b.setBackgroundColor(Color.parseColor(if (on) "#D32F2F" else "#00000000"))
            }
            buttons.forEachIndexed { i, b -> b.setOnClickListener { onPick(Quality.CHOICES[i]); paint(Quality.CHOICES[i]) } }
            paint(current)
            box.addView(r)
        }
        row("기본값 (그 밖의 사이트)", Quality.default(this)) { Quality.setDefault(this, it) }
        val sites = Quality.sites(this)
        if (sites.isEmpty()) box.addView(TextView(this).apply { text = "\n아직 방문한 사이트가 없어요. 방송 사이트를 한 번 열면 여기에 나와요."; textSize = 12f })
        sites.forEach { site -> row(site, Quality.forSite(this, site)) { Quality.setSite(this, site, it) } }
        AlertDialog.Builder(this)
            .setTitle("다운 화질 설정")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("닫기", null)
            .show()
    }

    /** 지금 페이지를 즐겨찾기 — 스트리머 이름을 이름으로 추천, 고친 뒤 저장 */
    private fun addBookmark(cat: String? = null) {
        val page = web.url ?: currentPage
        if (!page.startsWith("http")) { Toast.makeText(this, "먼저 방송 페이지를 열어 주세요", Toast.LENGTH_SHORT).show(); return }
        val guess = Names.streamerFromUrl(page).ifEmpty { Names.streamerFromTitle(web.title ?: "") }.ifEmpty { Names.siteName(page) }
        val input = EditText(this).apply {
            setText(guess)
            setSelectAllOnFocus(true)
            isSingleLine = true
            hint = "즐겨찾기 이름"
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(input)
        box.addView(TextView(this).apply { text = page.take(120); textSize = 11f; setTextColor(Color.parseColor("#888888")) })
        AlertDialog.Builder(this)
            .setTitle(if (Bookmarks.has(this, page)) "즐겨찾기 이름 바꾸기" else "즐겨찾기 추가")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val n = input.text.toString().trim().ifEmpty { guess }
                Bookmarks.add(this, n, page, cat?.takeIf { it.isNotEmpty() })
                Toast.makeText(this, "즐겨찾기에 저장했어요: $n", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
        input.requestFocus()
    }

    /** 즐겨찾기 목록: 누르면 열기, '녹화'로 바로 녹화(방송 대기) 추가, 길게 누르면 이름 바꾸기·삭제 */
    private fun showBookmarks() {
        startActivityForResult(Intent(this, BookmarksActivity::class.java), BookmarksActivity.REQ)
    }

    private fun showBookmarksOld() {
        val list = Bookmarks.list(this)
        if (list.isEmpty()) {
            AlertDialog.Builder(this).setTitle("즐겨찾기")
                .setMessage("아직 즐겨찾기가 없어요. 지금 보고 있는 페이지를 추가할까요?")
                .setPositiveButton("☆ 지금 페이지 추가") { _, _ -> addBookmark() }
                .setNegativeButton("닫기", null).show()
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(8), dp(4)) }
        lateinit var dlg: AlertDialog
        val recUrls = RecordService.list().map { it.pageUrl }.toSet()
        list.forEach { b ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), 0, dp(6))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = b.name; textSize = 15f; setTextColor(Color.parseColor("#222222")) })
            texts.addView(TextView(this).apply {
                text = (if (b.url in recUrls) "● 녹화 중 · " else "") + Names.siteName(b.url)
                textSize = 11f
                setTextColor(Color.parseColor(if (b.url in recUrls) "#D32F2F" else "#888888"))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (b.url !in recUrls) {
                row.addView(btn("녹화", Color.parseColor("#D32F2F")) {
                    dlg.dismiss()
                    requestRecord(b.url, web.settings.userAgentString, Names.clean(b.name))
                })
            }
            row.setOnClickListener { dlg.dismiss(); urlBox.setText(b.url); Sniff.clear(); web.loadUrl(b.url) }
            row.setOnLongClickListener {
                val input = EditText(this).apply { setText(b.name); isSingleLine = true }
                AlertDialog.Builder(this)
                    .setTitle("즐겨찾기 편집")
                    .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
                    .setPositiveButton("이름 저장") { _, _ -> Bookmarks.rename(this, b.url, input.text.toString().trim().ifEmpty { b.name }); dlg.dismiss(); showBookmarks() }
                    .setNeutralButton("삭제") { _, _ -> Bookmarks.remove(this, b.url); dlg.dismiss(); showBookmarks() }
                    .setNegativeButton("취소", null)
                    .show()
                true
            }
            box.addView(row)
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("즐겨찾기 (${list.size})")
            .setMessage("누르면 열기 · 길게 누르면 이름 바꾸기·삭제")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("☆ 지금 페이지 추가") { _, _ -> addBookmark() }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 앱을 켰는데 진행 중이던 녹화가 꺼져 있으면(폰 재부팅 등) 확인 후 재개 */
    private fun askResume() {
        if (RecordService.running) return
        val jobs = RecordService.savedJobs(this)
        if (jobs.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("녹화 중이던 방송 ${jobs.size}개가 있어요")
            .setMessage(jobs.joinToString("\n") { "- " + it.name.ifEmpty { hostOf(it.url) } } + "\n\n이어서 녹화할까요? (새 파일로 저장돼요)")
            .setPositiveButton("재개") { _, _ -> RecordService.resume(this) }
            .setNegativeButton("안 함") { _, _ -> RecordService.clearSaved(this) }
            .show()
    }

    /** 삼성 절전 기능이 녹화를 끊지 않도록 한 번 요청 */
    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun diagnose() {
        Toast.makeText(this, "진단 중… (몇 초 걸려요)", Toast.LENGTH_SHORT).show()
        val ua = web.settings.userAgentString
        val page = currentPage
        val js = "(function(){var v=document.querySelectorAll('video');var a=[];for(var i=0;i<v.length;i++){a.push((v[i].currentSrc||v[i].src||'(src 없음)').slice(0,120)+(v[i].paused?' [멈춤]':' [재생 중]'))}" +
            "return a.length+'개 '+a.join(' | ')+' / iframe '+document.querySelectorAll('iframe').length+'개';})()"
        web.evaluateJavascript(js) { raw ->
            val videoInfo = (raw ?: "").trim('"').replace("\\\"", "\"")
            Thread {
                val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
                val text = try { Diag.build(version, page, ua, videoInfo) } catch (e: Exception) { "진단 오류: $e" }
                val name = "진단_" + SimpleDateFormat("MMdd_HHmmss", Locale.KOREA).format(Date()) + ".txt"
                var saved = "${Saver.FOLDER}/$name"
                try { Saver.writeText(this, name, text) } catch (e: Exception) { saved = "파일 저장 실패: ${e.message}" }
                runOnUiThread { showDiag(text, saved) }
            }.start()
        }
    }

    /** 원격폰: 녹화폰 현황 (화면을 보고 있는 동안만 1분마다 갱신, 바뀐 게 없으면 녹화폰은 한 글자만 답함) */
    private fun askRecorderStatus() {
        if (CloudBm.recordHere(this)) { Toast.makeText(this, "이 폰이 녹화폰이에요. 설정 '폰 역할'에서 원격폰으로 바꾼 폰에서 써 주세요.", Toast.LENGTH_LONG).show(); return }
        val p = getSharedPreferences("cloudBm", MODE_PRIVATE)
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
        fun body(): String {
            val t = p.getString("lastStatus", null) ?: return "녹화폰에 물어보는 중… (보통 2~5초)"
            val at = p.getLong("lastStatusAt", 0); val chk = p.getLong("lastStatusCheck", 0)
            return t + "\n\n내용 갱신 " + fmt.format(java.util.Date(at)) + " · 마지막 확인 " + fmt.format(java.util.Date(chk)) + "\n(이 창을 보는 동안 1분마다 확인)"
        }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        // 제목줄: '녹화폰 현황' + 작은 남은 초 + 회전 화살표(누르면 바로 갱신)
        val bar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(18), dp(10), dp(4)) }
        bar.addView(TextView(this).apply { text = "녹화폰 현황"; textSize = 19f; setTextColor(Color.BLACK); setTypeface(null, android.graphics.Typeface.BOLD) })
        val secs = TextView(this).apply { textSize = 12f; setTextColor(Color.GRAY); setPadding(dp(10), 0, 0, 0) }
        bar.addView(secs, LinearLayout.LayoutParams(0, -2, 1f))
        val spin = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync); setBackgroundColor(Color.TRANSPARENT); contentDescription = "지금 갱신"
        }
        bar.addView(spin, LinearLayout.LayoutParams(dp(44), dp(44)))
        val dlg = AlertDialog.Builder(this).setCustomTitle(bar).setMessage(body())
            .setNegativeButton("닫기", null).create()
        var nextAt = 0L
        val tick = object : Runnable {
            override fun run() { if (!dlg.isShowing) return; CloudBm.askStatus(this@MainActivity); nextAt = System.currentTimeMillis() + 60_000; h.postDelayed(this, 60_000) }
        }
        // 제목 옆 작은 숫자: 다음 확인까지 남은 초
        val count = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                val left = ((nextAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                secs.text = "${left}초 뒤 갱신"
                h.postDelayed(this, 1000)
            }
        }
        Push.onStatus = { _ -> runOnUiThread { if (dlg.isShowing) dlg.setMessage(body()) } }
        dlg.setOnDismissListener { Push.onStatus = null; h.removeCallbacks(tick); h.removeCallbacks(count) }
        dlg.show()
        spin.setOnClickListener {
            h.removeCallbacks(tick); h.post(tick)   // 지금 묻고 1분 카운트 다시
            spin.animate().rotationBy(360f).setDuration(600).start()
        }
        h.post(tick); h.post(count)
    }

    /** 폰 역할 · 즐겨찾기 맞추기 (메인 계정 드라이브) · 녹화폰 알림 */
    private fun showCloudBm() {
        val ctx = this@MainActivity
        val h0 = android.os.Handler(android.os.Looper.getMainLooper())
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(6)) }
        fun section(t: String) = box.addView(TextView(ctx).apply { text = t; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#1565C0")); setPadding(dp(22), dp(14), dp(22), dp(4)) })
        fun note(t: String) = box.addView(TextView(ctx).apply { text = t; textSize = 12f; setTextColor(Color.parseColor("#777777")); setPadding(dp(22), 0, dp(22), dp(6)) })
        fun line() = box.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#E6E6E6")) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(22), dp(4), dp(22), dp(4)) })
        fun toggle(title: String, sub: String, on: Boolean, enabled: Boolean = true): android.widget.Switch {
            val r = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(8), dp(14), dp(8)) }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(Color.parseColor(if (enabled) "#222222" else "#AAAAAA")) })
            if (sub.isNotEmpty()) texts.addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(Color.parseColor("#888888")) })
            r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = android.widget.Switch(ctx).apply { isChecked = on; isEnabled = enabled }
            r.addView(sw); r.setOnClickListener { if (sw.isEnabled) sw.toggle() }
            box.addView(r); return sw
        }
        val other = CloudBm.lockedByOther(ctx); val mine = CloudBm.confirmed(ctx)

        section("이 폰 역할")
        val recSw = toggle("녹화폰으로 확정", when {
            mine -> "이 폰은 ${CloudBm.myNo(ctx)}번 녹화폰이에요 (녹화폰 ${CloudBm.recCount(ctx)}대). 방송 대기는 모두 같이 하고, 녹화는 폰끼리 자동으로 나눠요."
            else -> "여러 대를 녹화폰으로 확정할 수 있어요 (1·2·3번…). 방송 대기는 모두 같이, 녹화는 자동으로 나눠 받아요."
        }, mine || (CloudBm.recordHere(ctx) && !other), enabled = !other)
        line()
        val vwSw = toggle("원격폰 뷰어 모드", "켜면 녹화폰 화면을 그대로 보고, 여기서 하는 건 녹화폰에 적용돼요. 끄면 다운로드 모드(이 폰 화면, 이 폰에서 직접 녹화 · 생방 대기는 없음).",
            ctx.getSharedPreferences("cloudBm", MODE_PRIVATE).getBoolean("viewer", true), enabled = !recSw.isChecked)
        recSw.setOnCheckedChangeListener { _, v -> vwSw.isEnabled = !v }

        section("알림")
        val ntSw = toggle("녹화폰 → 원격폰 알림", "녹화 완료 · 업로드 완료를 원격폰 알림창과 업로드 기록에", CloudBm.notifyOn(ctx))

        section("상태")
        note("즐겨찾기는 메인 구글 계정 드라이브에 저장돼요.\n" + CloudBm.lastResult.ifEmpty { "맞추기 준비 중" } + (if (Push.ready) " · 푸시 연결됨" else " · 푸시 연결 전"))

        AlertDialog.Builder(this)
            .setTitle("폰 역할 · 즐겨찾기 맞추기")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                val rec = recSw.isChecked && !other
                if (rec != mine) CloudBm.setConfirm(ctx, rec)
                ctx.getSharedPreferences("cloudBm", MODE_PRIVATE).edit().putBoolean("recordHere", rec).apply()
                CloudBm.setNotifyOn(ctx, ntSw.isChecked)
                CloudBm.setViewer(ctx, vwSw.isChecked)
                if (Watcher.any(ctx)) RecordService.startWatch(ctx)
                Toast.makeText(ctx, if (rec) "이 폰을 녹화폰으로 확정해요" else if (vwSw.isChecked) "원격폰 · 뷰어 모드" else "원격폰 · 다운로드 모드", Toast.LENGTH_SHORT).show()
                Thread {
                    val r = CloudBm.sync(applicationContext)
                    runOnUiThread { if (r.contains("다른 폰")) Toast.makeText(ctx, "다른 폰이 이미 녹화폰으로 확정돼 있어서 이 폰은 뷰어예요", Toast.LENGTH_LONG).show(); saveTabs(); recreate() }
                }.start()
            }
            .setNeutralButton("지금 맞추기") { _, _ ->
                Thread { val r = CloudBm.sync(applicationContext); runOnUiThread { Toast.makeText(ctx, r, Toast.LENGTH_LONG).show() } }.start()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun copyAll(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun showDiag(text: String, saved: String) {
        copyAll("진단", text)
        Toast.makeText(this, "진단 내용 전체를 복사했어요. 채팅에 붙여 넣기만 하세요.", Toast.LENGTH_LONG).show()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("진단 결과")
            .setMessage("전체 내용이 자동으로 복사됐어요. 채팅에 붙여 넣기만 하세요.\n저장: $saved")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("다시 복사") { _, _ ->
                copyAll("진단", text)
                Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("공유") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "진단 보내기"))
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showLog() {
        val tv = TextView(this).apply {
            text = RecordService.logText().ifBlank { "아직 녹화 기록이 없어요" }
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        RecordService.logFile = java.io.File(filesDir, "record_log.txt")
        val recent = RecordService.recentLog(6)
        val text = (buildLogReport() + "\n\n=== 최근 6시간 기록 (앱이 꺼져도 남는 기록) ===\n" + recent.ifBlank { "(아직 없음)" }).let {
            if (it.length > 180_000) it.take(20_000) + "\n…(중간 생략)…\n" + it.takeLast(158_000) else it
        }
        copyAll("녹화 기록", text)
        Toast.makeText(this, "최근 6시간 기록까지 복사했어요. 채팅에 붙여 넣기만 하세요.", Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this)
            .setTitle("녹화 기록")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("다시 복사") { _, _ ->
                copyAll("녹화 기록", text)
                Toast.makeText(this, "복사했어요. 채팅에 붙여 넣어 주세요.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("공유") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "기록 보내기"))
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun memText(): String {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val pss = try { android.os.Debug.getPss() / 1024 } catch (e: Exception) { -1 }
        return "앱 ${pss}MB (자바 ${heap}/${rt.maxMemory() / 1048576}MB) · 폰 여유 ${mi.availMem / 1048576}/${mi.totalMem / 1048576}MB" + (if (mi.lowMemory) " · 메모리 부족 상태" else "")
    }

    /** 복사용: 앱 버전·기기 + 지금 상태 + 녹화 기록 */
    private fun buildLogReport(): String {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        return buildString {
            appendLine("=== 라이브 녹화 기록 ===")
            appendLine("앱 $version / ${android.os.Build.MODEL} / 안드로이드 ${android.os.Build.VERSION.RELEASE}")
            appendLine("상태: ${RecordService.status.ifBlank { "-" }}")
            appendLine("메모리: " + memText())
            appendLine("잡힌 영상 주소: " + Sniff.list().joinToString(" | ") { "[${it.kind}] ${maskUrl(it.url).take(150)}" }.ifBlank { "없음" })
            appendLine()
            append(RecordService.logText().ifBlank { "(녹화 기록 없음)" })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val pw = popupWeb
        if (pw != null) { if (pw.canGoBack()) pw.goBack() else closePopup(); return }
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        Sniff.listener = null
        RecordService.onChange = null
        tabs.forEach { it.destroy() }
        super.onDestroy()
    }
}
