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
    private lateinit var scroll: ScrollView
    private lateinit var fast: FastScroller
    /** 날짜 줄 (스크롤바 말풍선에 날짜 보여 주기용) */
    private val dayHeaders = ArrayList<Pair<android.view.View, String>>()
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
            text = "H.264 녹화는 H.265로 변환 후 업로드 (기다림 없이 바로 변환 → 끝나면 바로 업로드. 꺼 두면 정리만 하고 업로드)"
            textSize = 13f
            isChecked = Tidy.autoH265(this@FilesActivity)
            setPadding(dp(4), 0, 0, 0)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("autoH265", b).apply() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        head = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(dp(14), dp(2), dp(14), dp(6)) }
        root.addView(head)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(listBox); isVerticalScrollBarEnabled = false }
        val frame = android.widget.FrameLayout(this)
        frame.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        fast = FastScroller(frame)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.fitSystemBars()

        ConvertService.onChange = { runOnUiThread { render() } }
        if (intent.getBooleanExtra("openFtp", false)) root.post { ftpSettings() }
        Tidy.onChange = { runOnUiThread { softRefresh() } }
        Uploader.onChange = { runOnUiThread { softRefresh() } }
    }

    private val refresher = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() { val y = scroll.scrollY; render(); scroll.post { scroll.scrollTo(0, y) }; refresher.postDelayed(this, 30_000) }
    }

    override fun onResume() {
        super.onResume()
        reload()
        Thread { try { if (!RecordService.running) Tidy.sweep(this) } catch (_: Exception) {} }.start()
        refresher.postDelayed(refreshTick, 10_000)
    }

    override fun onPause() {
        refresher.removeCallbacks(refreshTick)
        super.onPause()
    }

    override fun onDestroy() {
        ConvertService.onChange = null
        Tidy.onChange = null
        Uploader.onChange = null
        super.onDestroy()
    }

    private var lastFull = 0L
    /** 진행률이 바뀔 때마다 목록 전체를 다시 그리면 수천 줄이라 렉 → 15초에 한 번만 */
    private fun softRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastFull > 15_000) { lastFull = now; val y = scroll.scrollY; reload(); scroll.post { scroll.scrollTo(0, y) } }
    }

    private fun reload() {
        Thread {
            val l = try { Library.list(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { items = l; render() }
            // 코덱은 뒤에서 하나씩 확인
            var n = 0
            l.forEach { it2 ->
                val k = it2.uri.toString()
                if (!codecCache.containsKey(k)) {
                    codecCache[k] = Codec.probeUri(this, it2.uri) ?: "?"
                    if (++n % 25 == 0) runOnUiThread { render() }   // 하나 볼 때마다 다시 그리면 렉
                }
            }
            if (n > 0) runOnUiThread { render() }
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
            append(" · 휴지통 ${Uploader.trashFiles().size}개 (${Uploader.trashLabel(this@FilesActivity)} 뒤 자동 삭제)")
            if (cur != null) append("\n변환 중: ${cur.name} ${ConvertService.percent}%")
            else if (ConvertService.lastResult.isNotBlank()) append("\n최근: ${ConvertService.lastResult}")
        }
        listBox.removeAllViews()
        dayHeaders.clear()
        // 내 폰에 있는 파일 + 업로드까지 끝나 폰에서는 빠진 파일(업로드 기록)을 날짜순으로 함께
        val localNames = items.map { it.name }.toHashSet()
        val active = RecordService.fileStates()
        val entries = (items.map { Entry(it.date * 1000, it, null) } +
            Uploader.history(this).filter { it.name !in localNames }.sortedByDescending { it.time }.take(150).map { Entry(it.time, null, it) })
            .sortedWith(compareBy<Entry>({ if (it.local?.name in active) 0 else 1 }, { -it.time }))
        if (entries.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "아직 녹화한 파일이 없어요"; setPadding(dp(16), dp(24), dp(16), 0) })
            fast.update()
            return
        }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        val dayFmt = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
        val bubbleFmt = SimpleDateFormat("M월 d일", Locale.KOREA)
        var lastDay = ""
        entries.forEach { e ->
            val isActive = e.local?.name in active
            val day = if (isActive) "지금 녹화 중 · 이어받기 대기" else dayFmt.format(Date(e.time))
            if (day != lastDay) {
                lastDay = day
                val h = TextView(this).apply {
                    text = day; textSize = 13f; setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#37474F")); setBackgroundColor(Color.parseColor("#ECEFF1"))
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                }
                listBox.addView(h)
                if (isActive) { h.setTextColor(Color.WHITE); h.setBackgroundColor(Color.parseColor("#C62828")) }
                dayHeaders.add(h to (if (isActive) "녹화 중" else bubbleFmt.format(Date(e.time))))
            }
            val hist = e.hist
            if (hist != null) {
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(8), dp(10))
                    setOnLongClickListener {
                        AlertDialog.Builder(this@FilesActivity).setMessage("업로드 기록에서 지울까요?\n(NAS 파일은 그대로예요)\n\n${hist.name}")
                            .setPositiveButton("기록 지우기") { _, _ -> Uploader.removeHistory(this@FilesActivity, hist.name, hist.time); render() }
                            .setNegativeButton("취소", null).show()
                        true
                    }
                }
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(this).apply { text = hist.name; textSize = 14f; setTextColor(Color.parseColor("#555555")) })
                texts.addView(TextView(this).apply {
                    text = "${fmt.format(Date(hist.time))} 업로드 · ${hist.size / 1024 / 1024}MB · NAS ${hist.dir}"
                    textSize = 12f; setTextColor(Color.parseColor("#888888"))
                })
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply {
                    text = "업로드 완료"; textSize = 13f; setTextColor(Color.parseColor("#2E7D32")); setPadding(dp(8), 0, dp(8), 0)
                })
                listBox.addView(row)
                listBox.addView(android.view.View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, LinearLayout.LayoutParams(-1, 1))
                return@forEach
            }
            val item = e.local!!
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
            val actState = active[item.name]
            val recording = actState != null
            val tidyState = Tidy.statusOf(item.name) ?: Uploader.statusOf(item.name)
            // 모든 파일에 지금 상태를 글로 보여 줌
            val isMp4 = item.name.lowercase().endsWith(".mp4")
            val stateText = when {
                actState != null -> actState
                tidyState != null -> tidyState
                !isMp4 -> null
                Tidy.isTidied(this, item.name) -> if (FtpConfig.ready(this)) "정리 완료 · 업로드 안 됨" else "정리 완료 (업로드 꺼짐)"
                else -> Tidy.pendingSweep(item.date * 1000)
            }
            if (stateText != null) texts.addView(TextView(this).apply {
                text = stateText; textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(when {
                    actState?.startsWith("녹화 중") == true -> "#D32F2F"
                    actState != null -> "#EF6C00"
                    stateText.startsWith("변환 실패") || stateText.startsWith("업로드 실패") -> "#B71C1C"
                    stateText.startsWith("정리 완료") -> "#2E7D32"
                    else -> "#1565C0"
                }))
            })
            if (recording || tidyState != null) {
                if (tidyState?.startsWith("충전 대기") == true) {
                    row.addView(Button(this).apply {
                        text = "지금 변환"; isAllCaps = false; textSize = 13f
                        setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#D32F2F"))
                        setOnClickListener { Tidy.forceNow(this@FilesActivity, item.name); render() }
                    })
                }
                if (tidyState?.startsWith("변환 실패") == true) {
                    row.addView(Button(this).apply {
                        text = "다시 시도"; isAllCaps = false; textSize = 13f
                        setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#1565C0"))
                        setOnClickListener { Tidy.retry(this@FilesActivity, item.name); render() }
                    })
                    if (FtpConfig.ready(this)) row.addView(Button(this).apply {
                        text = "그대로 업로드"; isAllCaps = false; textSize = 13f
                        setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#D32F2F"))
                        setOnClickListener {
                            android.app.AlertDialog.Builder(this@FilesActivity)
                                .setMessage("H.265로 바꾸지 않고 H.264 그대로 올릴까요?\n(용량이 큽니다)")
                                .setPositiveButton("올리기") { _, _ -> Tidy.uploadAsIs(this@FilesActivity, item.name); render() }
                                .setNegativeButton("취소", null).show()
                        }
                    })
                }
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
        fast.update()
    }

    private class Entry(val time: Long, val local: Library.Item?, val hist: Uploader.Hist?)

    /** 지금 스크롤 위치(y)의 날짜 */
    private fun dayAt(y: Int): String {
        var label = dayHeaders.firstOrNull()?.second ?: ""
        for ((v, l) in dayHeaders) { if (v.top <= y + dp(4)) label = l else break }
        return label
    }

    /**
     * 오른쪽 스크롤바: 손가락으로 잡고 끌면 빠르게 이동하고, 옆에 반투명 말풍선으로 날짜(몇월 며칠)를 보여 줌
     */
    private inner class FastScroller(val frame: android.widget.FrameLayout) {
        private val thumbH = dp(48)
        private val touchW = dp(32)
        private val thumb = android.view.View(this@FilesActivity).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#90A4AE")); cornerRadius = dp(3).toFloat() }
        }
        private val bubble = TextView(this@FilesActivity).apply {
            textSize = 16f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#B3263238")); cornerRadius = dp(18).toFloat() }
            visibility = android.view.View.GONE
        }
        private val track = android.view.View(this@FilesActivity)
        private var dragging = false
        private val hide = Runnable { bubble.visibility = android.view.View.GONE }

        init {
            frame.addView(thumb, android.widget.FrameLayout.LayoutParams(dp(6), thumbH, Gravity.END or Gravity.TOP).apply { rightMargin = dp(3) })
            frame.addView(bubble, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = dp(40) })
            frame.addView(track, android.widget.FrameLayout.LayoutParams(touchW, -1, Gravity.END))
            scroll.viewTreeObserver.addOnScrollChangedListener { place() }
            track.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                        if (!canScroll()) return@setOnTouchListener false
                        dragging = true
                        val range = (frame.height - thumbH).coerceAtLeast(1)
                        val f = ((ev.y - thumbH / 2f) / range).coerceIn(0f, 1f)
                        scroll.scrollTo(0, (f * maxScroll()).toInt())
                        frame.removeCallbacks(hide)
                        bubble.visibility = android.view.View.VISIBLE
                        place()
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        frame.postDelayed(hide, 700)
                        true
                    }
                    else -> false
                }
            }
        }

        private fun maxScroll() = (listBox.height - scroll.height).coerceAtLeast(0)
        private fun canScroll() = maxScroll() > 0

        fun update() { frame.post { place() } }

        private fun place() {
            val show = canScroll()
            thumb.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            track.visibility = thumb.visibility
            if (!show) { bubble.visibility = android.view.View.GONE; return }
            val f = scroll.scrollY.toFloat() / maxScroll()
            val y = f * (frame.height - thumbH)
            thumb.translationY = y
            thumb.alpha = if (dragging) 1f else 0.7f
            if (bubble.visibility == android.view.View.VISIBLE) {
                bubble.text = dayAt(scroll.scrollY)
                bubble.translationY = (y + thumbH / 2f - bubble.height / 2f).coerceIn(0f, (frame.height - bubble.height).toFloat().coerceAtLeast(0f))
            }
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
        box.addView(Button(this).apply {
            text = "폴더 찾아보기"; isAllCaps = false; textSize = 13f
            setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#1565C0"))
            setOnClickListener {
                // 지금 입력한 서버 정보로 접속해서 폴더를 고른다
                FtpConfig.save(this@FilesActivity, on.isChecked, host.text.toString(), port.text.toString().toIntOrNull() ?: 21,
                    user.text.toString(), pw.text.toString(), folder.text.toString().ifBlank { "/녹화" })
                browseFolder("/") { picked -> folder.setText(picked) }
            }
        })
        // 폰끼리 동기화 역할
        box.addView(TextView(this).apply { text = "폰끼리 동기화 (같은 NAS 설정을 쓰는 폰끼리)"; textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(0, dp(10), 0, 0) })
        val roles = android.widget.RadioGroup(this)
        val rSolo = android.widget.RadioButton(this).apply { id = 101; text = "사용 안 함"; textSize = 13f }
        val rRec = android.widget.RadioButton(this).apply { id = 102; text = "녹화 담당 폰 (실제로 녹화·업로드)"; textSize = 13f }
        val rRem = android.widget.RadioButton(this).apply { id = 103; text = "리모컨 폰 (담당 폰에 녹화 명령·현황 보기)"; textSize = 13f }
        roles.addView(rSolo); roles.addView(rRec); roles.addView(rRem)
        roles.check(when (Sync.role(this)) { Sync.RECORDER -> 102; Sync.REMOTE -> 103; else -> 101 })
        box.addView(roles)
        // 글자 인코딩 (한글 이름이 깨질 때)
        box.addView(TextView(this).apply { text = "글자 인코딩 (NAS의 한글 폴더·파일 이름이 깨질 때 바꿔 보세요)"; textSize = 12f; setTextColor(Color.parseColor("#666666")); setPadding(0, dp(10), 0, 0) })
        val encs = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        listOf(201 to "자동", 202 to "UTF-8", 203 to "EUC-KR").forEach { (rid, t) ->
            encs.addView(android.widget.RadioButton(this).apply { id = rid; text = t; textSize = 13f })
        }
        encs.check(when (FtpConfig.encoding(this)) { "UTF-8" -> 202; "EUC-KR" -> 203; else -> 201 })
        encs.setOnCheckedChangeListener { _, id -> FtpConfig.setEncoding(this, when (id) { 202 -> "UTF-8"; 203 -> "EUC-KR"; else -> "auto" }) }
        box.addView(encs)
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

    /** NAS 폴더 찾아보기: 눌러서 들어가고, '이 폴더에 저장'으로 고름 */
    private fun browseFolder(path: String, onPick: (String) -> Unit) {
        val loading = Toast.makeText(this, "NAS 폴더 불러오는 중…", Toast.LENGTH_SHORT).apply { show() }
        Thread {
            val r = try { Result.success(Uploader.listDirs(this, path)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                loading.cancel()
                r.onFailure {
                    AlertDialog.Builder(this).setTitle("NAS에 접속하지 못했어요")
                        .setMessage("${it.message ?: it.javaClass.simpleName}\n서버 주소·포트·아이디·비밀번호를 확인해 주세요.")
                        .setPositiveButton("확인", null).show()
                }
                r.onSuccess { dirs ->
                    val items = ArrayList<String>()
                    if (path != "/") items.add("‹ 위 폴더로")
                    items.addAll(dirs.map { "› $it" })
                    AlertDialog.Builder(this)
                        .setTitle("지금 위치: $path")
                        .setItems(items.toTypedArray()) { _, i ->
                            val label = items[i]
                            if (label.startsWith("‹")) {
                                val up = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
                                browseFolder(up, onPick)
                            } else {
                                val name = label.removePrefix("› ")
                                browseFolder(path.trimEnd('/') + "/" + name, onPick)
                            }
                        }
                        .setPositiveButton("이 폴더에 저장") { _, _ ->
                            onPick(path)
                            Toast.makeText(this, "저장 폴더: $path", Toast.LENGTH_SHORT).show()
                        }
                        .setNeutralButton("새 폴더") { _, _ ->
                            val input = android.widget.EditText(this).apply { hint = "새 폴더 이름"; isSingleLine = true }
                            AlertDialog.Builder(this).setTitle("$path 안에 새 폴더")
                                .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
                                .setPositiveButton("만들기") { _, _ ->
                                    val n = Names.clean(input.text.toString())
                                    if (n.isEmpty()) { browseFolder(path, onPick); return@setPositiveButton }
                                    val np = path.trimEnd('/') + "/" + n
                                    Thread {
                                        val ok = try { Uploader.makeDir(this, np) } catch (e: Exception) { false }
                                        runOnUiThread {
                                            if (!ok) Toast.makeText(this, "폴더를 만들지 못했어요 (권한 확인)", Toast.LENGTH_LONG).show()
                                            browseFolder(if (ok) np else path, onPick)
                                        }
                                    }.start()
                                }
                                .setNegativeButton("취소") { _, _ -> browseFolder(path, onPick) }
                                .show()
                        }
                        .setNegativeButton("닫기", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showTrash() {
        val files = Uploader.trashFiles()
        if (files.isEmpty()) { Toast.makeText(this, "휴지통이 비어 있어요", Toast.LENGTH_SHORT).show(); return }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        val names = files.map {
            val left = (Uploader.trashHours(this) - ((System.currentTimeMillis() - it.lastModified()) / 3600_000L).toInt()).coerceAtLeast(0)
            "${it.name}\n${it.length() / 1024 / 1024}MB · ${fmt.format(Date(it.lastModified()))} 업로드 · ${Uploader.hoursLabel(left.coerceAtLeast(1))} 안에 삭제"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("휴지통 ${files.size}개 · ${files.sumOf { it.length() } / 1024 / 1024}MB")
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
            .setNeutralButton("전부 비우기") { _, _ ->
                AlertDialog.Builder(this).setMessage("휴지통 ${files.size}개를 전부 지울까요? 되살릴 수 없어요.")
                    .setPositiveButton("전부 지우기") { _, _ -> val (n, mb) = Uploader.emptyTrash(); Toast.makeText(this, "${n}개 · ${mb}MB 지웠어요", Toast.LENGTH_SHORT).show(); reload() }
                    .setNegativeButton("취소", null).show()
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
