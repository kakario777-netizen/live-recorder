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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 녹화 현황 (전체 화면, 1초마다 새로 고침)
 *  - 맨 위: 받는 중 / 속도 / 합계 요약 칸
 *  - 묶음: 녹화 중(빨강 카드) → 이어받기 대기(주황) → 준비 중 → 방송 대기(회색 한 줄)
 *  - 방송마다 '다시 시도'·'중지'
 */
class StatusActivity : Activity() {
    companion object { const val REQ = 4702 }

    private lateinit var sumBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private var lastIds = ""
    private val lines = HashMap<Int, Pair<TextView, TextView>>()
    private val sums = ArrayList<TextView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6F8")) }
        root.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(6), dp(4)) }
        top.addView(TextView(this).apply { text = "녹화 현황"; textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#222222")) },
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(flat("닫기", "#555555") { finish() })
        root.addView(top)

        // 요약 칸 3개
        sumBox = LinearLayout(this).apply { setPadding(dp(10), dp(2), dp(10), dp(8)) }
        repeat(3) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
            }
            val big = TextView(this).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#222222")); gravity = Gravity.CENTER }
            val small = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#777777")); gravity = Gravity.CENTER }
            c.addView(big); c.addView(small)
            sums.add(big); sums.add(small)
            sumBox.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.addView(sumBox)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12)) }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(Color.WHITE) }
        bottom.addView(Button(this).apply {
            text = "지금 페이지 녹화 추가"; isAllCaps = false
            setOnClickListener { setResult(RESULT_OK, Intent().putExtra("action", "add")); finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Button(this).apply {
            text = "모두 중지"; isAllCaps = false; setTextColor(Color.parseColor("#D32F2F"))
            setOnClickListener {
                AlertDialog.Builder(this@StatusActivity).setMessage("모든 녹화와 방송 대기를 멈출까요?\n지금까지 녹화한 건 저장돼요.")
                    .setPositiveButton("모두 중지") { _, _ -> RecordService.stopAll(this@StatusActivity) }
                    .setNegativeButton("취소", null).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bottom)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); tick.run() }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }

    private val tick = object : Runnable {
        override fun run() { update(); h.postDelayed(this, 1000) }
    }

    private fun flat(t: String, color: String, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor(color))
        setOnClickListener { on() }
    }

    private fun update() {
        val l = RecordService.list().filter { it.group() < 4 }
        sums[0].text = "${l.count { it.isDownloading }}개"; sums[1].text = "받는 중"
        sums[2].text = RecordService.rateText().substringBefore('/') + "MB/s"; sums[3].text = "속도 (한도 ${RecordService.rateText().substringAfter('/')})"
        sums[4].text = "${l.sumOf { it.bytes } / 1024 / 1024}MB"; sums[5].text = "합계 · 등록 ${l.size}개"
        val ids = l.sortedWith(compareBy({ it.group() }, { it.id })).joinToString(",") { "${it.id}:${it.group()}" }
        if (ids != lastIds) { lastIds = ids; build(l) }
        l.forEach { sl ->
            lines[sl.id]?.let { (name, info) ->
                name.text = sl.streamer.ifEmpty { sl.host }
                info.text = sl.detail()
            }
        }
    }

    private fun build(all: List<Slot>) {
        listBox.removeAllViews(); lines.clear()
        if (all.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "녹화 중인 방송이 없어요.\n즐겨찾기에서 '녹화'를 누르거나 방송 페이지에서 녹화를 눌러 주세요."
                setTextColor(Color.parseColor("#777777")); setPadding(dp(6), dp(24), dp(6), 0)
            })
            return
        }
        val groups = listOf(
            Triple(0, "녹화 중", "#D32F2F"),
            Triple(1, "이어받기 대기", "#EF6C00"),
            Triple(2, "준비 중", "#1565C0"),
            Triple(3, "방송 대기", "#78909C"))
        for ((g, label, color) in groups) {
            val items = all.filter { it.group() == g }.sortedBy { it.id }
            if (items.isEmpty()) continue
            listBox.addView(TextView(this).apply {
                text = "$label  ${items.size}"; textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(color)); setPadding(dp(4), dp(14), 0, dp(6))
            })
            val compact = g == 3
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
            }
            items.forEachIndexed { i, sl ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(14), 0, 0, 0) })
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(if (compact) 6 else 10), dp(2), dp(if (compact) 6 else 10)) }
                // 왼쪽 색 점
                row.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)) }
                }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(0, 0, dp(10), 0) })
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val name = TextView(this).apply { textSize = if (compact) 14f else 16f; setTextColor(Color.parseColor("#222222")); setTypeface(null, if (compact) Typeface.NORMAL else Typeface.BOLD); isSingleLine = true }
                val info = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#666666")) }
                texts.addView(name)
                if (!compact) texts.addView(info)
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                if (!compact) row.addView(flat("다시 시도", "#1565C0") { retry(sl) })
                row.addView(flat("중지", "#D32F2F") {
                    AlertDialog.Builder(this).setMessage("'${sl.streamer.ifEmpty { sl.host }}' 녹화를 멈출까요?\n지금까지 녹화한 건 저장돼요.")
                        .setPositiveButton("중지") { _, _ -> RecordService.stopOne(this, sl.id) }
                        .setNegativeButton("취소", null).show()
                })
                if (compact) row.setOnLongClickListener { retry(sl); true }
                // 누르면 그 방송 페이지로 (즐겨찾기 추가용)
                row.setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("action", "open").putExtra(BookmarksActivity.EXTRA_URLS, arrayOf(sl.pageUrl))); finish()
                }
                lines[sl.id] = name to info
                card.addView(row)
            }
            listBox.addView(card)
            if (compact) listBox.addView(TextView(this).apply {
                text = "누르면 방송 페이지로 · 길게 누르면 지금 바로 다시 확인"; textSize = 11f; setTextColor(Color.parseColor("#999999")); setPadding(dp(6), dp(4), 0, 0)
            })
        }
    }

    private fun retry(sl: Slot) {
        val go = { sl.retryNow(); Toast.makeText(this, "${sl.streamer.ifEmpty { sl.host }} 다시 여는 중 (같은 파일에 이어서)", Toast.LENGTH_SHORT).show() }
        if (sl.state == "녹화 중") AlertDialog.Builder(this).setMessage("지금 잘 받는 중이에요. 그래도 다시 열까요? (몇 초 빠질 수 있어요)")
            .setPositiveButton("다시 열기") { _, _ -> go() }.setNegativeButton("취소", null).show()
        else go()
    }
}
