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
            text = "NAS 업로드 설정"; isAllCaps = false; textSize = 13f
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#1565C0"))
            setOnClickListener { ftpSettings() }
        })
        bar.addView(Button(this).apply {
            text = "휴지통"; isAllCaps = false; textSize = 13f
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#555555"))
            setOnClickListener { showTrash() }
        })
        root.addView(bar)
        root.addView(Button(this).apply {
            text = "H.265 변환 필요한 것 모두"; isAllCaps = false; textSize = 13f
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { convertAll() }
        })

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
        Uploader.onChange = { runOnUiThread { if (Uploader.current == null) reload() else render() } }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        ConvertService.onChange = null
        Tidy.onChange = null
        Uploader.onChange = null
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
            if (FtpConfig.ready(this@FilesActivity)) {
                append("\nNAS 자동 업로드 켜짐 · 대기 ${Uploader.pendingCount(this@FilesActivity)}개")
                Uploader.current?.let { append(" · 업로드 중: ${it.name} ${Uploader.percent}%") }
                if (Uploader.lastResult.isNotBlank()) append("\n최근: ${Uploader.lastResult}")
            } else append("\nNAS 자동 업로드 꺼짐")
            append(" · 휴지통 ${Uploader.trashFiles().size}개 (${Uploader.TRASH_DAYS}일 뒤 자동 삭제)")
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
            val tidyState = Tidy.statusOf(item.name) ?: Uploader.statusOf(item.name)
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
            if (FtpConfig.ready(this) && (Tidy.isTidied(this, item.name) || !item.name.lowercase().endsWith(".mp4"))) {
                row.addView(Button(this).apply {
                    isAllCaps = false; textSize = 13f
                    setBackgroundColor(Color.TRANSPARENT)
                    text = "업로드"
                    setTextColor(Color.parseColor("#2E7D32"))
                    setOnClickListener {
                        val p = Tidy.pathFor(item.name)
                        if (p == null) Toast.makeText(this@FilesActivity, "이 파일은 직접 경로를 찾을 수 없어 올릴 수 없어요", Toast.LENGTH_SHORT).show()
                        else { RecordService.uploadOne(this@FilesActivity, item.name, p, guessStreamer(item.name)); render() }
                    }
                })
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

    /** 파일 이름 "사이트 스트리머 날짜 시간"에서 스트리머 추정 (직접 올릴 때 폴더용) */
    private fun guessStreamer(name: String): String {
        val parts = name.substringBeforeLast('.').split(' ').filter { it.isNotEmpty() && !it.all { c -> c.isDigit() } && !it.startsWith("(") }
        return when {
            parts.size >= 2 -> parts[1]
            parts.size == 1 -> parts[0]
            else -> ""
        }
    }

    private fun ftpSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(hint: String, value: String, type: Int = android.text.InputType.TYPE_CLASS_TEXT) = android.widget.EditText(this).apply {
            this.hint = hint; setText(value); isSingleLine = true; inputType = type; textSize = 14f
        }.also { box.addView(TextView(this).apply { text = hint; textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(0, dp(6), 0, 0) }); box.addView(it) }
        val on = CheckBox(this).apply { text = "녹화가 끝나면 NAS에 자동 업로드"; isChecked = FtpConfig.enabled(this@FilesActivity) }
        box.addView(on)
        val host = field("서버 주소 (예: 192.168.0.10 또는 내이름.synology.me)", FtpConfig.host(this))
        val port = field("포트", FtpConfig.port(this).toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        val user = field("아이디", FtpConfig.user(this))
        val pw = field("비밀번호", FtpConfig.password(this), android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val folder = field("올릴 폴더 (안에 스트리머별 폴더가 생겨요)", FtpConfig.folder(this))
        // 폰끼리 동기화 역할
        box.addView(TextView(this).apply { text = "폰끼리 동기화 (같은 NAS 설정을 쓰는 폰끼리)"; textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(0, dp(10), 0, 0) })
        val roles = android.widget.RadioGroup(this)
        val rSolo = android.widget.RadioButton(this).apply { id = 101; text = "사용 안 함"; textSize = 13f }
        val rRec = android.widget.RadioButton(this).apply { id = 102; text = "녹화 담당 폰 (실제로 녹화·업로드)"; textSize = 13f }
        val rRem = android.widget.RadioButton(this).apply { id = 103; text = "리모컨 폰 (담당 폰에 녹화 명령·현황 보기)"; textSize = 13f }
        roles.addView(rSolo); roles.addView(rRec); roles.addView(rRem)
        roles.check(when (Sync.role(this)) { Sync.RECORDER -> 102; Sync.REMOTE -> 103; else -> 101 })
        box.addView(roles)
        val result = TextView(this).apply { textSize = 12f; setPadding(0, dp(8), 0, dp(4)) }
        box.addView(result)
        fun save() {
            FtpConfig.save(this, on.isChecked, host.text.toString(), port.text.toString().toIntOrNull() ?: 21,
                user.text.toString(), pw.text.toString(), folder.text.toString().ifBlank { "/녹화" })
            Sync.setRole(this, when (roles.checkedRadioButtonId) { 102 -> Sync.RECORDER; 103 -> Sync.REMOTE; else -> Sync.SOLO })
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("NAS(FTP) 업로드 설정")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장", null)
            .setNeutralButton("연결 시험", null)
            .setNegativeButton("취소", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            save()
            result.text = "연결하는 중…"
            Thread {
                val r = Uploader.test(this)
                runOnUiThread { result.text = r; result.setTextColor(Color.parseColor(if (r.startsWith("연결 성공")) "#2E7D32" else "#C62828")) }
            }.start()
        }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            save()
            dlg.dismiss()
            Toast.makeText(this, if (on.isChecked) "저장했어요. 녹화가 끝난 파일은 NAS에 자동으로 올라가요." else "저장했어요 (자동 업로드 꺼짐)", Toast.LENGTH_LONG).show()
            RecordService.startUploads(this)
            Sync.pushSoon(this)
            Sync.startRecorderService(this)
            render()
        }
    }

    private fun showTrash() {
        val files = Uploader.trashFiles()
        if (files.isEmpty()) { Toast.makeText(this, "휴지통이 비어 있어요", Toast.LENGTH_SHORT).show(); return }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        val names = files.map {
            val left = Uploader.TRASH_DAYS - ((System.currentTimeMillis() - it.lastModified()) / (24 * 3600_000L)).toInt()
            "${it.name}\n${it.length() / 1024 / 1024}MB · ${fmt.format(Date(it.lastModified()))} 업로드 · ${left}일 뒤 삭제"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("휴지통 (NAS 업로드 확인 끝난 원본)")
            .setItems(names) { _, i ->
                val f = files[i]
                AlertDialog.Builder(this).setMessage(f.name)
                    .setPositiveButton("되살리기") { _, _ ->
                        val back = java.io.File(Uploader.recDir(), f.name)
                        if (f.renameTo(back)) { Saver.scan(this, back.absolutePath); Toast.makeText(this, "녹화 폴더로 되살렸어요", Toast.LENGTH_SHORT).show(); reload() }
                    }
                    .setNeutralButton("지금 삭제") { _, _ -> f.delete(); Toast.makeText(this, "삭제했어요", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("닫기", null).show()
            }
            .setNegativeButton("닫기", null)
            .show()
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
