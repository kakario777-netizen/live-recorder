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
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // 아래: 상태 + 버튼
        info = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        root.addView(info)
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(4)) }
        bottom.addView(btn("진단 저장") { diagnose() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(btn("기록") { showLog() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(btn("파일") { startActivity(Intent(this, FilesActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
        recBtn = btn("● 녹화", Color.parseColor("#D32F2F")) { onRecord() }.apply { setTypeface(null, Typeface.BOLD) }
        bottom.addView(recBtn, LinearLayout.LayoutParams(0, -2, 1.3f))
        root.addView(bottom)

        setContentView(root)
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrl(intent)?.let { urlBox.setText(it); go() }
    }

    private fun sharedUrl(i: Intent?): String? {
        if (i?.action != Intent.ACTION_SEND) return null
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""https?://\S+""").find(t)?.value
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                val kind = Sniff.classify(url)
                if (kind != null) {
                    Sniff.add(Captured(url, kind, req.requestHeaders ?: emptyMap(), currentPage))
                }
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
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                loading.progress = p
                loading.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
        }
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
            RecordService.status.isNotBlank() -> RecordService.status
            n > 0 -> "영상 주소 ${n}개 찾음 — 녹화를 누르세요"
            others > 0 -> "다른 형식 영상 주소 ${others}개 (진단 저장 후 보내 주세요)"
            else -> "방송을 재생하면 영상 주소를 자동으로 찾아요"
        }
        recBtn.text = if (RecordService.running) "■ 중지" else "● 녹화"
    }

    private fun onRecord() {
        if (RecordService.running) {
            RecordService.stop(this)
            return
        }
        val list = Sniff.list().filter { it.kind == "hls" }
        if (list.isEmpty()) {
            Toast.makeText(this, "아직 영상 주소를 못 찾았어요. 방송을 먼저 재생해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        askBattery()
        if (list.size == 1) { startRec(list[0]); return }
        val names = list.mapIndexed { i, c ->
            val path = c.url.substringBefore('?').substringAfterLast('/')
            (if (i == 0) "(최근) " else "") + "${hostOf(c.url)} · $path"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("어떤 영상을 녹화할까요?")
            .setItems(names) { _, i -> startRec(list[i]) }
            .show()
    }

    private fun startRec(c: Captured) {
        RecordService.status = ""
        RecordService.start(this, c, web.settings.userAgentString)
        Toast.makeText(this, "녹화를 시작했어요. 화면을 꺼도 계속 저장돼요.", Toast.LENGTH_LONG).show()
        refresh()
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

    private fun showDiag(text: String, saved: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("진단 결과")
            .setMessage("저장: $saved\n'복사'를 눌러 채팅에 붙여 넣어 주세요.")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("복사") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("진단", text))
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
        AlertDialog.Builder(this)
            .setTitle("녹화 기록")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("닫기", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
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
