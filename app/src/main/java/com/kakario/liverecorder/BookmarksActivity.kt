package com.kakario.liverecorder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 즐겨찾기 화면 (전체 화면): 개수 제한 없음, 스크롤, 폴더(카테고리), 검색.
 * 고른 결과는 MainActivity로 돌려줌: open(열기) / record(녹화) / add(지금 페이지 추가)
 */
class BookmarksActivity : Activity() {
    companion object {
        const val REQ = 4701
        const val EXTRA_ACTION = "action"
        const val EXTRA_URLS = "urls"
        const val EXTRA_NAMES = "names"
        const val EXTRA_CAT = "cat"
        private const val ALL = "\u0000전체"
        private const val NONE = "\u0000없음"
    }

    private lateinit var chips: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var title: TextView
    private lateinit var search: EditText
    private var cur = ALL

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cur = getSharedPreferences("main", MODE_PRIVATE).getString("bmLastCat", ALL) ?: ALL
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }

        // 맨 위: 제목 + 폴더 만들기 + 닫기
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(6), dp(4)) }
        title = TextView(this).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#222222")) }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(smallBtn("+ 폴더", "#1565C0") { newFolder() })
        top.addView(smallBtn("닫기", "#555555") { finish() })
        root.addView(top)

        // 폴더 줄 (옆으로 넘김)
        chips = LinearLayout(this).apply { setPadding(dp(8), dp(2), dp(8), dp(6)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        // 검색
        search = EditText(this).apply {
            hint = "이름으로 찾기"; isSingleLine = true; textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F1F3F4")); cornerRadius = dp(18).toFloat() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { renderList() }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(6)) })

        // 목록 (스크롤, 개수 제한 없음)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // 아래: 지금 페이지 추가 / 이 폴더 모두 녹화
        val bottom = LinearLayout(this).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(Color.parseColor("#FAFAFA")) }
        bottom.addView(Button(this).apply {
            text = "☆ 지금 페이지 추가"; isAllCaps = false
            setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, "add").putExtra(EXTRA_CAT, if (cur == ALL || cur == NONE) "" else cur))
                finish()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Button(this).apply {
            text = "이 목록 모두 녹화"; isAllCaps = false; setTextColor(Color.parseColor("#D32F2F"))
            setOnClickListener { recordAll() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bottom)

        setContentView(root)
        render()
    }

    private fun smallBtn(t: String, color: String, on: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f
        setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor(color))
        setOnClickListener { on() }
    }

    private fun render() { renderChips(); renderList() }

    private fun renderChips() {
        chips.removeAllViews()
        val all = Bookmarks.list(this)
        val cats = Bookmarks.cats(this)
        if (cur != ALL && cur != NONE && cur !in cats) cur = ALL
        fun chip(label: String, key: String, count: Int) {
            val on = key == cur
            chips.addView(TextView(this).apply {
                text = "$label $count"; textSize = 14f
                setTextColor(if (on) Color.WHITE else Color.parseColor("#333333"))
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply { setColor(Color.parseColor(if (on) "#1565C0" else "#ECEFF1")); cornerRadius = dp(16).toFloat() }
                setOnClickListener { cur = key; getSharedPreferences("main", MODE_PRIVATE).edit().putString("bmLastCat", key).apply(); render() }
                if (key != ALL && key != NONE) setOnLongClickListener { folderMenu(key); true }
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        chip("전체", ALL, all.size)
        cats.forEach { c -> chip(c, c, all.count { it.cat == c }) }
        val none = all.count { it.cat.isEmpty() || it.cat !in cats }
        if (cats.isNotEmpty() && none > 0) chip("폴더 없음", NONE, none)
    }

    private fun current(): List<Bookmarks.Item> {
        val cats = Bookmarks.cats(this)
        val q = search.text.toString().trim().lowercase()
        return Bookmarks.list(this).filter {
            when (cur) { ALL -> true; NONE -> it.cat.isEmpty() || it.cat !in cats; else -> it.cat == cur }
        }.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.url.lowercase().contains(q) }
            .sortedBy { it.name.lowercase() }
    }

    private fun renderList() {
        listBox.removeAllViews()
        val items = current()
        val total = Bookmarks.list(this).size
        title.text = "즐겨찾기 ($total)"
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = if (total == 0) "아직 즐겨찾기가 없어요.\n방송 페이지를 연 뒤 아래 '지금 페이지 추가'를 눌러 주세요." else "이 폴더에는 아직 없어요"
                setPadding(dp(16), dp(24), dp(16), 0); setTextColor(Color.parseColor("#777777"))
            })
            return
        }
        val rec = RecordService.list().associateBy { it.pageUrl }
        items.forEach { b ->
            val sl = rec[b.url]
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(6), dp(8))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { pick("open", listOf(b)) }
                setOnLongClickListener { itemMenu(b); true }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = b.name; textSize = 15f; setTextColor(Color.parseColor("#222222")) })
            texts.addView(TextView(this).apply {
                val st = sl?.state
                text = listOfNotNull(
                    st?.let { "● $it" },
                    Names.siteName(b.url),
                    if (cur == ALL && b.cat.isNotEmpty()) "폴더: ${b.cat}" else null,
                    Quality.perUrl(this, b.url).takeIf { it > 0 }?.let { Quality.label(it) }
                ).joinToString(" · ")
                textSize = 11f
                setTextColor(Color.parseColor(if (st == "녹화 중") "#D32F2F" else if (st != null) "#EF6C00" else "#888888"))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (sl == null) row.addView(smallBtn("녹화", "#D32F2F") { pick("record", listOf(b)) })
            listBox.addView(row)
            listBox.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, LinearLayout.LayoutParams(-1, 1))
        }
    }

    private fun pick(action: String, l: List<Bookmarks.Item>) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_URLS, l.map { it.url }.toTypedArray())
            .putExtra(EXTRA_NAMES, l.map { it.name }.toTypedArray()))
        finish()
    }

    private fun recordAll() {
        val rec = RecordService.list().map { it.pageUrl }.toSet()
        val l = current().filter { it.url !in rec }
        if (l.isEmpty()) { Toast.makeText(this, "모두 이미 녹화(대기) 중이에요", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setMessage("지금 보이는 ${l.size}개를 모두 녹화에 걸까요?\n(방송 중이 아니면 방송 대기로 기다려요)")
            .setPositiveButton("모두 녹화") { _, _ -> pick("record", l) }
            .setNegativeButton("취소", null).show()
    }

    private fun input(title: String, value: String, ok: String, onOk: (String) -> Unit) {
        val e = EditText(this).apply { setText(value); isSingleLine = true; setSelectAllOnFocus(true) }
        AlertDialog.Builder(this).setTitle(title)
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton(ok) { _, _ -> onOk(e.text.toString().trim()) }
            .setNegativeButton("취소", null).show()
        e.requestFocus()
    }

    private fun newFolder() = input("새 폴더", "", "만들기") { n ->
        if (n.isEmpty()) return@input
        Bookmarks.addCat(this, n); cur = n; render()
    }

    private fun folderMenu(c: String) {
        AlertDialog.Builder(this).setTitle("폴더: $c")
            .setItems(arrayOf("이름 바꾸기", "폴더 지우기 (안의 즐겨찾기는 남음)")) { _, i ->
                when (i) {
                    0 -> input("폴더 이름 바꾸기", c, "저장") { n -> if (n.isNotEmpty()) { Bookmarks.renameCat(this, c, n); cur = n; render() } }
                    1 -> { Bookmarks.removeCat(this, c); cur = ALL; render() }
                }
            }.setNegativeButton("닫기", null).show()
    }

    private fun itemMenu(b: Bookmarks.Item) {
        AlertDialog.Builder(this).setTitle(b.name)
            .setItems(arrayOf("이름 바꾸기", "폴더 옮기기" + (if (b.cat.isNotEmpty()) " (지금: ${b.cat})" else ""),
                "화질 고르기 (지금: ${Quality.label(Quality.perUrl(this, b.url))})", "삭제")) { _, i ->
                when (i) {
                    0 -> input("이름 바꾸기", b.name, "저장") { n -> Bookmarks.rename(this, b.url, n.ifEmpty { b.name }); render() }
                    1 -> moveFolder(b)
                    2 -> pickQuality(b)
                    3 -> AlertDialog.Builder(this).setMessage("'${b.name}' 즐겨찾기를 지울까요?")
                        .setPositiveButton("삭제") { _, _ -> Bookmarks.remove(this, b.url); render() }
                        .setNegativeButton("취소", null).show()
                }
            }.setNegativeButton("닫기", null).show()
    }

    /** 이 스트리머만 따로 화질 정하기 */
    private fun pickQuality(b: Bookmarks.Item) {
        val opts = listOf(0, 9999, 1080, 720, 480)
        AlertDialog.Builder(this).setTitle("${b.name} 화질")
            .setItems(opts.map { Quality.label(it) + (if (it == 0) " (${Quality.forSite(this, Names.siteName(b.url))}p)" else "") }.toTypedArray()) { _, i ->
                Quality.setPerUrl(this, b.url, opts[i]); render()
                Toast.makeText(this, "다음 녹화부터 ${Quality.label(opts[i])}", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("취소", null).show()
    }

    private fun moveFolder(b: Bookmarks.Item) {
        val cats = Bookmarks.cats(this)
        val labels = (listOf("폴더 없음") + cats + listOf("+ 새 폴더 만들기")).toTypedArray()
        AlertDialog.Builder(this).setTitle("어느 폴더로?")
            .setItems(labels) { _, i ->
                when {
                    i == 0 -> { Bookmarks.setCat(this, b.url, ""); render() }
                    i == labels.size - 1 -> input("새 폴더", "", "만들고 옮기기") { n ->
                        if (n.isNotEmpty()) { Bookmarks.addCat(this, n); Bookmarks.setCat(this, b.url, n); render() }
                    }
                    else -> { Bookmarks.setCat(this, b.url, cats[i - 1]); render() }
                }
            }.setNegativeButton("취소", null).show()
    }
}
