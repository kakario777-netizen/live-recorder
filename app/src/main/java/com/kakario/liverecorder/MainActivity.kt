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
    private lateinit var recBtn: Button
    private lateinit var loading: ProgressBar
    private val prefs by lazy { getSharedPreferences("main", MODE_PRIVATE) }
    private val updater by lazy { Updater(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        top.addView(btn("이동") { go() })
        top.addView(btn("↻") { web.reload() })
        root.addView(top)

        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(loading, LinearLayout.LayoutParams(-1, dp(3)))

        web = WebView(this)
        webFrame = FrameLayout(this)
        webFrame.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(webFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        // 아래: 상태 + 버튼
        info = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        root.addView(info)
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(4)) }
        bottom.addView(btn("진단") { diagnose() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(btn("기록") { showLog() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(btn("업데이트") { updater.check(silent = false) }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(btn("파일") { innerNav = true; startActivity(Intent(this, FilesActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
        recBtn = btn("● 녹화", Color.parseColor("#D32F2F")) { onRecord() }.apply { setTypeface(null, Typeface.BOLD) }
        bottom.addView(recBtn, LinearLayout.LayoutParams(0, -2, 1.3f))
        root.addView(bottom)

        setContentView(root)
        root.fitSystemBars()
        setupWeb()

        Sniff.listener = { runOnUiThread { refresh() } }
        RecordService.onChange = { runOnUiThread { refresh() } }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val shared = sharedUrl(intent)
        val start = shared ?: prefs.getString("last", null)
        if (start != null) { urlBox.setText(start); web.loadUrl(start) }
        refresh()
        updater.check(silent = true)
        askResume()
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    override fun onStop() {
        super.onStop()
        // 앱을 나갈 때 받아 둔 업데이트 설치 (녹화·변환 중이면 안 함)
        if (!isChangingConfigurations && !innerNav) updater.installOnLeave()
        innerNav = false
    }

    /** 앱 안 다른 화면(파일 목록)으로 갈 때는 '앱 나감'으로 치지 않음 */
    private var innerNav = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrl(intent)?.let { urlBox.setText(it); go() }
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

    private fun setupWeb() {
        CookieManager.getInstance().setAcceptCookie(true)
        configure(web)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                sniff(req)
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: return true
                return s != "http" && s != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentPage = url
                urlBox.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPage = url
                prefs.edit().putString("last", url).apply()
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = chrome
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
            RecordService.running -> RecordService.status.ifBlank { "녹화 준비 중…" }
            n > 0 || Sniff.latestSegAny() != null -> "영상 찾음 — 녹화를 누르세요"
            others > 0 -> "다른 형식 영상 주소 ${others}개 (진단 저장 후 보내 주세요)"
            else -> "방송을 재생하면 영상 주소를 자동으로 찾아요"
        }
        recBtn.text = if (RecordService.running) "녹화 ${RecordService.list().size}개" else "● 녹화"
    }

    private fun onRecord() {
        if (RecordService.running) showSlots() else addRecording()
    }

    /** 녹화 현황: 칸마다 상태와 중지 버튼, 아래에 '이 방송 추가' */
    private fun showSlots() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var dlg: AlertDialog
        RecordService.list().forEach { sl ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(TextView(this).apply {
                text = "${sl.id}. ${sl.host}\n${sl.summary()}"
                textSize = 13f
                setTextColor(Color.parseColor("#222222"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(btn("중지", Color.parseColor("#D32F2F")) {
                AlertDialog.Builder(this).setMessage("${sl.id}번 녹화를 멈출까요? 지금까지 녹화한 건 저장돼요.")
                    .setPositiveButton("중지") { _, _ -> RecordService.stopOne(this, sl.id); dlg.dismiss() }
                    .setNegativeButton("취소", null).show()
            })
            box.addView(row)
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("녹화 현황 (${RecordService.list().size}/${RecordService.MAX_SLOTS})")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("이 방송 추가") { _, _ -> addRecording() }
            .setNeutralButton("모두 중지") { _, _ ->
                AlertDialog.Builder(this).setMessage("모든 녹화를 멈출까요?")
                    .setPositiveButton("모두 중지") { _, _ -> RecordService.stopAll(this) }
                    .setNegativeButton("취소", null).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 지금 보고 있는 방송 페이지를 숨은 재생기로 녹화 추가 */
    private fun addRecording() {
        val page = web.url ?: currentPage
        if (page.isBlank() || !page.startsWith("http")) {
            Toast.makeText(this, "먼저 방송 페이지를 열어 주세요", Toast.LENGTH_LONG).show(); return
        }
        if (RecordService.list().size >= RecordService.MAX_SLOTS) {
            Toast.makeText(this, "동시 녹화는 최대 ${RecordService.MAX_SLOTS}개예요", Toast.LENGTH_LONG).show(); return
        }
        askBattery()
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("askedOverlay", false)) {
            prefs.edit().putBoolean("askedOverlay", true).apply()
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
            Names.setParts(this, cSite.isChecked, cName.isChecked, cTime.isChecked)
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
            .setPositiveButton("녹화 시작") { _, _ ->
                RecordService.add(this, page, ua, Names.clean(nameBox.text.toString()))
                Toast.makeText(this, "녹화를 추가했어요. 방송이 꺼져 있으면 켜질 때까지 기다리고, 720p가 잡히면 시작해요. 화면을 꺼도 계속돼요.", Toast.LENGTH_LONG).show()
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
        val text = buildLogReport()
        copyAll("녹화 기록", text)
        Toast.makeText(this, "기록 전체를 복사했어요. 채팅에 붙여 넣기만 하세요.", Toast.LENGTH_LONG).show()
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

    /** 복사용: 앱 버전·기기 + 지금 상태 + 녹화 기록 */
    private fun buildLogReport(): String {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        return buildString {
            appendLine("=== 라이브 녹화 기록 ===")
            appendLine("앱 $version / ${android.os.Build.MODEL} / 안드로이드 ${android.os.Build.VERSION.RELEASE}")
            appendLine("상태: ${RecordService.status.ifBlank { "-" }}")
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
        web.destroy()
        super.onDestroy()
    }
}
