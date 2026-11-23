package com.kakario.liverecorder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 뷰어 모드: 녹화폰의 녹화 현황을 그대로 보여 주는 화면.
 * 화면을 보는 동안만 1분마다 녹화폰에 물어보고(바뀐 게 없으면 녹화폰은 한 글자만 답함),
 * 줄마다 중지·다시 시도를 녹화폰에 부탁할 수 있다.
 */
class RemoteStatusActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var listBox: LinearLayout
    private lateinit var secs: TextView
    private lateinit var info: TextView
    private val sums = ArrayList<TextView>()
    private var nextAt = 0L
    private var lastShown = ""
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val p by lazy { getSharedPreferences("cloudBm", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#E3F2FD")) }
        root.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(6), dp(4)) }
        top.addView(TextView(this).apply { text = "녹화폰 현황"; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#222222")) })
        secs = TextView(this).apply { textSize = 11f; setTextColor(Color.GRAY); setPadding(dp(8), 0, 0, 0) }
        top.addView(secs, LinearLayout.LayoutParams(0, -2, 1f))
        val spin = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync); setBackgroundColor(Color.TRANSPARENT); contentDescription = "지금 갱신"
        }
        spin.setOnClickListener { askNow(); spin.animate().rotationBy(360f).setDuration(600).start() }
        top.addView(spin, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(Button(this).apply {
            text = "닫기"; isAllCaps = false; textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#555555"))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "뷰어 모드 · 녹화폰 현황"; textSize = 12f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E88E5")); setPadding(dp(14), dp(5), dp(14), dp(5))
        }, 0)
        root.addView(top)

        val sumBox = LinearLayout(this).apply { setPadding(dp(10), dp(2), dp(10), dp(4)) }
        repeat(3) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
            }
            val big = TextView(this).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#222222")); gravity = Gravity.CENTER }
            val small = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#777777")); gravity = Gravity.CENTER }
            c.addView(big); c.addView(small); sums.add(big); sums.add(small)
            sumBox.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.addView(sumBox)
        info = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#888888")); setPadding(dp(18), 0, dp(18), dp(4)) }
        root.addView(info)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12)) }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(Color.WHITE) }
        bottom.addView(Button(this).apply {
            text = "지금 보는 방송 녹화 (녹화폰에서)"; isAllCaps = false
            setOnClickListener { setResult(RESULT_OK, Intent().putExtra("action", "add")); finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Push.onStatus = { _ -> runOnUiThread { render() } }
        render()
        h.post(ask)
        h.post(count)
    }

    override fun onPause() {
        Push.onStatus = null
        h.removeCallbacks(ask); h.removeCallbacks(count)
        super.onPause()
    }

    private val ask = object : Runnable {
        override fun run() { CloudBm.askStatus(this@RemoteStatusActivity); nextAt = System.currentTimeMillis() + 60_000; h.postDelayed(this, 60_000) }
    }
    private val count = object : Runnable {
        override fun run() {
            val left = ((nextAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            secs.text = "${left}초 뒤 갱신"
            h.postDelayed(this, 1000)
        }
    }
    private fun askNow() { h.removeCallbacks(ask); h.post(ask) }

    private fun send(cmd: String, name: String) {
        CloudBm.sendCommand(this, cmd, name)
        Toast.makeText(this, "녹화폰에 부탁했어요 ($name)", Toast.LENGTH_SHORT).show()
        h.postDelayed({ askNow() }, 4000)
    }

    private fun render() {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA)
        val raw = p.getString("lastStatus", null)
        val at = p.getLong("lastStatusAt", 0); val chk = p.getLong("lastStatusCheck", 0)
        info.text = if (raw == null) "녹화폰에 물어보는 중… (보통 2~5초)"
            else "내용 갱신 ${fmt.format(java.util.Date(at))} · 마지막 확인 ${fmt.format(java.util.Date(chk))}"
        if (raw == null || raw == lastShown) return
        lastShown = raw
        val o = try { JSONObject(raw) } catch (e: Exception) { null }
        listBox.removeAllViews()
        if (o == null) {   // 예전 녹화폰(글자 현황)
            listBox.addView(TextView(this).apply { text = raw; textSize = 13f; setTextColor(Color.parseColor("#333333")) })
            return
        }
        sums[0].text = "${o.optInt("n")}개"; sums[1].text = "받는 중"
        sums[2].text = o.optString("r").substringBefore('/') + "MB/s"; sums[3].text = "속도"
        sums[4].text = "${o.optLong("tot")}MB"; sums[5].text = "합계 · 등록 ${o.optInt("all")}개"
        val rows = o.optJSONArray("s")
        val groups = listOf(Triple(0, "녹화 중", "#D32F2F"), Triple(1, "이어받기 대기", "#EF6C00"), Triple(2, "준비 중", "#1565C0"), Triple(3, "방송 대기", "#78909C"))
        for ((g, label, color) in groups) {
            val items = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONArray(it) }.filter { it.optInt(1) == g }
            if (items.isEmpty()) continue
            listBox.addView(TextView(this).apply {
                text = "$label  ${items.size}"; textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(color)); setPadding(dp(4), dp(14), 0, dp(6))
            })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
            }
            items.forEachIndexed { i, a ->
                val name = a.optString(0); val detail = a.optString(2)
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(14), 0, 0, 0) })
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(if (g == 3) 6 else 10), dp(2), dp(if (g == 3) 6 else 10)) }
                row.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) } },
                    LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(0, 0, dp(10), 0) })
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(this).apply { text = name; textSize = if (g == 3) 14f else 16f; setTextColor(Color.parseColor("#222222")); if (g != 3) setTypeface(null, Typeface.BOLD); isSingleLine = true })
                if (g != 3 && detail.isNotEmpty()) texts.addView(TextView(this).apply { text = detail; textSize = 12f; setTextColor(Color.parseColor("#666666")) })
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (g != 3) row.addView(Button(this).apply {
                    text = "다시 시도"; isAllCaps = false; textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#1565C0"))
                    setOnClickListener { send("retry", name) }
                })
                row.addView(Button(this).apply {
                    text = "중지"; isAllCaps = false; textSize = 13f; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#D32F2F"))
                    setOnClickListener {
                        AlertDialog.Builder(this@RemoteStatusActivity).setMessage("녹화폰의 '$name' 녹화를 멈출까요?\n지금까지 녹화한 건 저장돼요.")
                            .setPositiveButton("중지") { _, _ -> send("stop", name) }.setNegativeButton("취소", null).show()
                    }
                })
                // 누르면 그 방송 페이지로
                row.setOnClickListener {
                    val url = a.optString(3).ifEmpty { "https://ko.stripchat.com/$name" }
                    startActivity(Intent(this, MainActivity::class.java).putExtra("openUrl", url).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                card.addView(row)
            }
            listBox.addView(card)
        }
        o.optString("x").takeIf { it.isNotEmpty() }?.let { listBox.addView(TextView(this).apply { text = it; textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(dp(6), dp(12), 0, 0) }) }
    }
}
