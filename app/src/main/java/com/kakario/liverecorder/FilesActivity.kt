package com.kakario.liverecorder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 녹화한 파일 목록 — 코덱 표시, H.265 변환 */
class FilesActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var head: TextView
    private val codecCache = ConcurrentHashMap<String, String>()
    private var items: List<Library.Item> = emptyList()
    private val prefs by lazy { getSharedPreferences("main", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(6), dp(4)) }
        bar.addView(TextView(this).apply {
            text = "녹화 파일"; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply {
            text = "H.265 변환 필요한 것 모두"; isAllCaps = false; textSize = 13f
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#D32F2F"))
            setOnClickListener { convertAll() }
        })
        root.addView(bar)

        root.addView(CheckBox(this).apply {
            text = "변환 성공하면 원본 삭제"
            textSize = 13f
            isChecked = prefs.getBoolean("deleteOriginal", false)
            setPadding(dp(4), 0, 0, 0)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("deleteOriginal", b).apply() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        head = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(dp(14), dp(2), dp(14), dp(6)) }
        root.addView(head)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.fitSystemBars()

        ConvertService.onChange = { runOnUiThread { render() } }
        Tidy.onChange = { runOnUiThread { if (Tidy.current == null) reload() else render() } }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        ConvertService.onChange = null
        Tidy.onChange = null
        super.onDestroy()
    }

    private fun reload() {
        Thread {
            val l = try { Library.list(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { items = l; render() }
            // 코덱은 뒤에서 하나씩 확인
            l.forEach { it2 ->
                val k = it2.uri.toString()
                if (!codecCache.containsKey(k)) {
                    codecCache[k] = Codec.probeUri(this, it2.uri) ?: "?"
                    runOnUiThread { render() }
                }
            }
        }.start()
    }

    private fun codecOf(it: Library.Item): String? = codecCache[it.uri.toString()]

    private fun render() {
        val cur = ConvertService.current
        head.text = buildString {
            append("내 파일 > ${Saver.FOLDER} · ${items.size}개")
            if (!Codec.phoneHasHevcEncoder()) append("\n이 폰에는 H.265 인코더가 없어 변환할 수 없어요")
            Tidy.current?.let { append("\n정리 중: ${it.name} ${Tidy.percent}%") }
            if (cur != null) append("\n변환 중: ${cur.name} ${ConvertService.percent}%")
            else if (ConvertService.lastResult.isNotBlank()) append("\n최근: ${ConvertService.lastResult}")
        }
        listBox.removeAllViews()
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "아직 녹화한 파일이 없어요"; setPadding(dp(16), dp(24), dp(16), 0) })
            return
        }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(8), dp(10))
                setOnClickListener { play(item) }
                setOnLongClickListener { askDelete(item); true }
            }
            val mime = codecOf(item)
            val codecText = when (mime) {
                null -> "코덱 확인 중…"
                "?" -> "코덱 알 수 없음"
                else -> Codec.label(mime)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = item.name; textSize = 14f; setTextColor(Color.BLACK) })
            texts.addView(TextView(this).apply {
                text = "${fmt.format(Date(item.date * 1000))} · ${item.size / 1024 / 1024}MB · $codecText"
                textSize = 12f
                setTextColor(if (Codec.isEfficient(mime)) Color.parseColor("#2E7D32") else Color.parseColor("#777777"))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

            // 녹화 중 / 정리 중… N% / 정리 대기 / 정리 버튼
            val recording = item.name in RecordService.recordingNames()
            val tidyState = Tidy.statusOf(item.name)
            if (recording || tidyState != null) {
                row.addView(TextView(this).apply {
                    text = if (recording) "녹화 중" else tidyState
                    textSize = 13f
                    setTextColor(Color.parseColor(if (recording) "#D32F2F" else "#1565C0"))
                    setPadding(dp(8), 0, dp(8), 0)
                })
                listBox.addView(row)
                listBox.addView(android.view.View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, LinearLayout.LayoutParams(-1, 1))
                return@forEach
            }
            if (item.name.lowercase().endsWith(".mp4") && !Tidy.isTidied(this, item.name)) {
                row.addView(Button(this).apply {
                    isAllCaps = false; textSize = 13f
                    setBackgroundColor(Color.TRANSPARENT)
                    text = "정리"
                    setTextColor(Color.parseColor("#1565C0"))
                    setOnClickListener { RecordService.tidy(this@FilesActivity, item.name, item.uri); render() }
                })
            }

            val busy = ConvertService.isBusy(item.uri)
            if (busy || (mime != null && mime != "?" && !Codec.isEfficient(mime))) {
                row.addView(Button(this).apply {
                    isAllCaps = false; textSize = 13f
                    setBackgroundColor(Color.TRANSPARENT)
                    if (busy) {
                        text = if (cur?.uri == item.uri) "${ConvertService.percent}%" else "대기"
                        isEnabled = false
                    } else {
                        text = "H.265 변환"
                        setTextColor(Color.parseColor("#D32F2F"))
                        setOnClickListener { convert(listOf(item)) }
                    }
                })
            }
            listBox.addView(row)
            listBox.addView(android.view.View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, LinearLayout.LayoutParams(-1, 1))
        }
    }

    private fun convert(list: List<Library.Item>) {
        if (!Codec.phoneHasHevcEncoder()) {
            Toast.makeText(this, "이 폰에는 H.265 인코더가 없어요", Toast.LENGTH_LONG).show(); return
        }
        if (RecordService.running) {
            Toast.makeText(this, "녹화 중이라 폰이 무거울 수 있어요. 변환은 순서대로 진행돼요.", Toast.LENGTH_LONG).show()
        }
        ConvertService.enqueue(this, list.map { ConvertService.Job(it.uri, it.name, it.size) })
        Toast.makeText(this, "변환을 시작했어요. 화면을 꺼도 계속돼요.", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun convertAll() {
        val targets = items.filter { val m = codecOf(it); m != null && m != "?" && !Codec.isEfficient(m) && !ConvertService.isBusy(it.uri) }
        if (targets.isEmpty()) { Toast.makeText(this, "변환할 파일이 없어요", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setMessage("${targets.size}개를 차례대로 H.265로 변환할까요?")
            .setPositiveButton("변환") { _, _ -> convert(targets) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun play(item: Library.Item) {
        val mime = if (item.name.lowercase().endsWith(".mp4")) "video/mp4" else "video/mp2t"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            Toast.makeText(this, "열 수 있는 동영상 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askDelete(item: Library.Item) {
        AlertDialog.Builder(this)
            .setMessage("${item.name}\n이 파일을 삭제할까요? 되돌릴 수 없어요.")
            .setPositiveButton("삭제") { _, _ ->
                try { contentResolver.delete(item.uri, null, null) } catch (e: Exception) {
                    Toast.makeText(this, "삭제 못 했어요. 내 파일 앱에서 지워 주세요.", Toast.LENGTH_LONG).show()
                }
                reload()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
