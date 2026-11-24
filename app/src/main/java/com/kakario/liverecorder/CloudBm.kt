package com.kakario.liverecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 즐겨찾기 폰끼리 맞추기 — 구글 앱스 스크립트(내 구글 드라이브에 파일 하나)로.
 * - 이 폰에서 바뀌면 바로 올림
 * - 앱이 켜져 있는 동안(녹화 서비스 포함) 30초마다 '판 번호'만 물어보고, 바뀌었을 때만 내려받음
 */
object CloudBm {
    private val client by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloudBm", Context.MODE_PRIVATE)
    /** 기본 주소 (내 구글 스크립트) — 두 폰 모두 설정 없이 바로 맞춰지게 앱에 넣어 둠 */
    private fun dec(e: String): String = try {
        val k = "lrbm2026".toByteArray()
        val b = android.util.Base64.decode(e.reversed(), android.util.Base64.DEFAULT)
        String(ByteArray(b.size) { (b[it].toInt() xor k[it % k.size].toInt()).toByte() })
    } catch (e: Exception) { "" }
    /** 즐겨찾기 저장소: 메인 계정(777) 드라이브의 스크립트 */
    private val DEFAULT_URL: String by lazy { dec("RkgTX9RRfkSFe93YCgGOSoxW7ZVC79zUwwVBxNXWOUFEa1XUqVFJtIUWDM2WERSPe4TcaxVdawiSUw1XjRHBQgTJZd1QF0BEF4QVLZVes0UADVUXCFFDP0VAZFlHXFQBdMQUcQkQEAREfkRHKEUHWYAB") }
    /** 푸시 보내기 전용: 서버 계정(1004)의 스크립트 */
    fun url(ctx: Context) = DEFAULT_URL
    fun on(ctx: Context) = url(ctx).startsWith("https://script.google.com/")
    fun setUrl(ctx: Context, u: String) = prefs(ctx).edit().putString("url", u.trim()).putLong("ver", 0).putBoolean("dirty", true).apply()
    /** 녹화폰(true): 생방 대기 녹화 + 원격폰에 알림 보냄 / 원격폰(false): 즐겨찾기만 맞추고 알림을 받음 */
    fun recordHere(ctx: Context) = prefs(ctx).getBoolean("recordHere", true)
    fun setRecordHere(ctx: Context, on: Boolean) { if (on && lockedByOther(ctx)) return; if (!on && confirmed(ctx)) return; if (on != recordHere(ctx)) prefs(ctx).edit().putBoolean("recordHere", on).putLong("ver", 0).putBoolean("dirty", false).apply() }

    @Volatile var lastResult = ""; private set
    @Volatile var appCtxRef: Context? = null

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("dev", null) ?: java.util.UUID.randomUUID().toString().take(8).also { p.edit().putString("dev", it).apply() }
    }
    // ---------- 기기 등록: 등록·승인된 폰만 저장소·푸시를 쓸 수 있음 ----------
    /** 이 폰만의 비밀 열쇠 (앱 파일에는 없음, 폰 안에서 처음 한 번 만듦) */
    private fun devKey(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("devKey", null) ?: run {
            val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                .also { p.edit().putString("devKey", it).apply() }
        }
    }
    private fun auth(ctx: Context) = "d=" + java.net.URLEncoder.encode(deviceId(ctx), "UTF-8") + "&k=" + devKey(ctx)
    fun approved(ctx: Context) = prefs(ctx).getBoolean("devOk", false)
    @Volatile private var regAt = 0L
    /** 등록 (처음 폰은 바로 승인, 다음 폰부터는 등록된 폰에서 '승인'을 눌러야 함) */
    fun register(ctx: Context) {
        if (!on(ctx) || approved(ctx) || System.currentTimeMillis() - regAt < 60_000L) return
        regAt = System.currentTimeMillis()
        try {
            val n = java.net.URLEncoder.encode(android.os.Build.MODEL, "UTF-8")
            val r = post(url(ctx) + "?op=reg&n=$n&" + auth(ctx), "")
            val ok = r.optBoolean("ok")
            prefs(ctx).edit().putBoolean("devOk", ok).apply()
            RecordService.addLog(if (ok) "이 폰 기기 등록 완료" else "기기 등록 요청 보냄 → 등록된 폰에서 '승인'을 눌러 주세요")
        } catch (_: Exception) {}
    }
    /** 다른 폰 승인 (등록된 폰에서만) */
    fun approve(ctx: Context, target: String): Boolean = try {
        post(url(ctx) + "?op=approve&target=" + java.net.URLEncoder.encode(target, "UTF-8") + "&" + auth(ctx), "").optBoolean("ok")
    } catch (_: Exception) { false }
    /** 푸시 보내기는 저장소(777)를 거쳐서만 (저장소가 기기 확인 후 1004 서버에 전달) */
    private fun relay(ctx: Context, op: String, body: String = "", extra: String = "") =
        post(url(ctx) + "?op=$op$extra&" + auth(ctx), body)

    // ---------- 녹화폰 확정 (한 대만) ----------
    fun recId(ctx: Context) = prefs(ctx).getString("recId", "") ?: ""
    fun recName(ctx: Context) = prefs(ctx).getString("recName", "") ?: ""
    /** 이 폰이 녹화폰으로 확정됨 (여러 대 가능) */
    fun confirmed(ctx: Context) = prefs(ctx).getInt("myNo", 0) > 0
    fun myNo(ctx: Context) = prefs(ctx).getInt("myNo", 0)
    fun recCount(ctx: Context) = prefs(ctx).getInt("recCount", 0)
    /** 녹화폰 여러 대가 가능해져서 다른 폰 때문에 막히는 일은 없음 */
    fun lockedByOther(ctx: Context) = false
    /** 대표 녹화폰(1번): 뷰어 현황·녹화 요청·휴지통 명령을 받는 폰 */
    fun primary(ctx: Context) = recordHere(ctx) && (recId(ctx).isEmpty() || recId(ctx) == deviceId(ctx))

    // ---------- 녹화 나누기: 방송이 켜지면 녹화폰끼리 먼저 잡은 폰이 받음 ----------
    /** 이 방송을 내가 받아도 되나? (녹화폰이 한 대면 항상 OK, 서버에 못 닿으면 일단 받음) */
    fun claim(ctx: Context, key: String, active: Int): Boolean {
        if (!on(ctx) || !recordHere(ctx)) return true
        try { Thread.sleep(active * 3000L) } catch (_: Exception) {}   // 많이 받는 폰은 늦게 → 한가한 폰이 먼저
        return try {
            val r = post(url(ctx) + "?op=claim&s=" + java.net.URLEncoder.encode(key, "UTF-8") + "&n=$active&" + auth(ctx), "")
            if (r.has("ok")) r.optBoolean("ok") else true
        } catch (_: Exception) { true }
    }
    /**
     * 받는 중인 자동 녹화는 계속 내 것이라고 1분마다 알림 (3분 넘게 소식 없으면 다른 폰이 이어받음).
     * 서버가 '다른 폰 것'이라고 하면 이 폰은 그 방송을 멈춤 → 두 폰이 같이 받지 않게
     */
    @Volatile private var renewAt = 0L
    fun renewNow(ctx: Context) {
        if (!on(ctx) || !recordHere(ctx) || System.currentTimeMillis() - renewAt < 20_000L) return
        renewAt = System.currentTimeMillis()
        val mine = RecordService.list().filter { it.watch || Watcher.isWatched(ctx, it.pageUrl) }.associateBy { Watcher.key(it.pageUrl) }
        if (mine.isEmpty()) return
        try {
            val r = post(url(ctx) + "?op=renew&" + auth(ctx), mine.keys.joinToString("\n"))
            val lost = r.optJSONArray("lost") ?: return
            for (i in 0 until lost.length()) {
                val sl = mine[lost.optString(i)] ?: continue
                RecordService.addLog("다른 녹화폰이 이미 받는 방송 → 이 폰은 멈춤: ${sl.streamer.ifEmpty { sl.host }}")
                RecordService.stopOne(ctx, sl.id)
            }
        } catch (_: Exception) {}
    }
    /** 1분마다 갱신 (지켜보기 주기와 상관없이) */
    @Volatile private var renewLoop = false
    fun startRenewLoop(ctx: Context) {
        if (renewLoop) return
        renewLoop = true
        val app = ctx.applicationContext
        Thread({ while (true) { try { Thread.sleep(60_000L); renewNow(app) } catch (_: InterruptedException) { break } catch (_: Exception) {} } }, "claimRenew").apply { isDaemon = true }.start()
    }
    /** 확정 켜기/끄기 부탁 → 다음 맞추기 때 저장소에 반영 (다른 폰이 이미 확정이면 실패) */
    fun setConfirm(ctx: Context, on: Boolean) { prefs(ctx).edit().putInt("wantRec", if (on) 1 else 0).putBoolean("dirty", true).apply() }

    fun notifyOn(ctx: Context) = prefs(ctx).getBoolean("notify", true)
    /** 원격폰 뷰어 모드: 이 폰은 녹화 안 함 (녹화 버튼은 녹화폰에 부탁) */
    fun viewer(ctx: Context) = !recordHere(ctx) && prefs(ctx).getBoolean("viewer", true)
    fun setViewer(ctx: Context, on: Boolean) { if (on != prefs(ctx).getBoolean("viewer", true)) prefs(ctx).edit().putBoolean("viewer", on).putLong("ver", 0).putBoolean("dirty", false).apply() }
    /** 뷰어 모드는 녹화폰 거울 값을 따로 씀 (이 폰 값은 그대로 보관) */
    fun chatKey(ctx: Context) = if (viewer(ctx)) "v_chatKoreanOnly" else "chatKoreanOnly"
    private fun qtKey(ctx: Context) = if (viewer(ctx)) "v_qt" else "qt"
    private fun stKey(ctx: Context) = if (viewer(ctx)) "v_st" else "st"

    /** 원격폰(뷰어) → 녹화폰: 이 방송 녹화해 줘 */
    fun requestRecord(ctx: Context, url: String, name: String) {
        val app = ctx.applicationContext
        Thread {
            try {
                val js = JSONObject().put("title", "녹화 요청").put("body", "$url\n$name").put("from", deviceId(app)).toString()
                relay(app, "notify", js)
            } catch (_: Exception) {}
        }.start()
    }
    fun setNotifyOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("notify", on).apply()

    /** 원격폰: 녹화폰에 '현황 보내 줘' */
    @Volatile var statusAskedAt = 0L
    fun askStatus(ctx: Context) {
        statusAskedAt = System.currentTimeMillis()
        val app = ctx.applicationContext
        Thread { try { relay(app, "ping", extra = "&t=req") } catch (_: Exception) {} }.start()
    }

    /** 녹화폰: 지금 현황을 짧게 (푸시 한 장 4KB 안에 들어가게) */
    fun statusText(): String {
        val now = System.currentTimeMillis()
        val l = RecordService.list().sortedWith(compareBy({ it.group() }, { it.id }))
        val rec = l.filter { it.group() == 0 }
        val sb = StringBuilder()
        sb.append("받는 중 ${rec.size}개 · ${RecordService.rateText()} · 등록 ${l.size}개\n")
        for (s in l) {
            if (sb.length > 3000) { sb.append("… 외 ${l.size - l.indexOf(s)}개\n"); break }
            val min = if (s.recordStartAt > 0) " ${(now - s.recordStartAt) / 60000}분" else ""
            val mb = if (s.bytes > 0) " ${s.bytes / 1024 / 1024}MB" else ""
            sb.append("· ${s.streamer.ifEmpty { s.host }.take(20)} ${s.state}$min$mb\n")
        }
        Tidy.current?.let { sb.append("정리 중 ${Tidy.percent}% ${it.name.take(40)}\n") }
        Uploader.current?.let { sb.append("업로드 중 ${Uploader.percent}% ${it.name.take(40)}\n") }
        val w = Watcher.status.size
        if (w > 0) sb.append("생방 대기 확인 ${w}명")
        return sb.toString().take(3500)
    }

    /** 녹화폰: 현황을 작게 (뷰어 화면용) — 4KB 안에 들어가게 */
    fun statusJson(): String {
        val l = RecordService.list().filter { it.group() < 4 }.sortedWith(compareBy({ it.group() }, { it.id }))
        val o = JSONObject().put("n", l.count { it.isDownloading }).put("r", RecordService.rateText())
            .put("tot", l.sumOf { it.bytes } / 1024 / 1024).put("all", l.size)
        val a = JSONArray()
        var size = 200
        for (s in l) {
            val g = s.group()
            val name = s.streamer.ifEmpty { s.host }.take(30)
            val row = JSONArray().put(name).put(g).put(if (g == 3) "" else s.detail().take(70))
            if (!s.pageUrl.contains("stripchat.com/$name", true)) row.put(s.pageUrl.take(80))
            size += row.toString().toByteArray().size + 1
            if (size > 2300) { o.put("x", "… 외 ${l.size - a.length()}개"); break }
            a.put(row)
        }
        appCtxRef?.let { o.put("w", workJson(it)) }
        return o.put("s", a).toString()
    }

    /** 정리·업로드 진행 상황 (작게) */
    fun workJson(ctx: Context): JSONObject {
        val o = JSONObject()
        Tidy.current?.let { o.put("tc", JSONArray().put(it.name.take(48)).put(Tidy.percent).put(if (Tidy.converting) 1 else 0)) }
        val tq = Tidy.queuedNames(); o.put("tq", tq.size); o.put("tqn", JSONArray(tq.take(4).map { it.take(48) }))
        o.put("wc", Tidy.waitingCount())
        Uploader.current?.let { o.put("uc", JSONArray().put(it.name.take(48)).put(Uploader.percent).put(Uploader.speed / 1024)) }
        val uq = Uploader.queuedNames(); o.put("uq", uq.size); o.put("uqn", JSONArray(uq.take(4).map { it.take(60) }))
        val ud = Uploader.history(ctx).filter { !it.name.startsWith("[녹화폰]") }.sortedByDescending { it.time }.take(4)
        Uploader.trashFiles().let { t -> o.put("tr", JSONArray().put(t.size).put(t.sumOf { it.length() } / 1024 / 1024).put(Uploader.trashLabel(ctx))) }
        o.put("ud", JSONArray(ud.map { JSONArray().put(it.name.take(48)).put(it.time) }))
        return o
    }
    /** 정리·업로드 상황을 사람이 읽는 줄로 */
    fun workLines(w: JSONObject): List<Pair<String, String>> {   // (색, 글)
        val out = ArrayList<Pair<String, String>>()
        val fmt = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA)
        w.optJSONArray("tc")?.let { out.add("#6A1B9A" to (if (it.optInt(2) == 1) "변환 중 ${it.optInt(1)}% · ${it.optString(0)}" else "업로드 준비 중 · ${it.optString(0)}")) }
        val tq = w.optInt("tq"); if (tq > 0) out.add("#6A1B9A" to "변환 대기 ${tq}개 (" + (if (tq >= Tidy.MANY) "밀려서 짧은 영상부터" else "오래된 것부터") + "): " + (0 until (w.optJSONArray("tqn")?.length() ?: 0)).joinToString(", ") { w.getJSONArray("tqn").getString(it) })
        val wc = w.optInt("wc"); if (wc > 0) out.add("#EF6C00" to "충전 대기 ${wc}개 (H.264 → 충전기 꽂거나 80% 넘으면 변환)")
        w.optJSONArray("uc")?.let { out.add("#2E7D32" to "업로드 중 ${it.optInt(1)}% · ${String.format("%.1f", it.optLong(2) / 1024.0)}MB/s · ${it.optString(0)}") }
        val uq = w.optInt("uq"); if (uq > 0) out.add("#2E7D32" to "업로드 대기 ${uq}개 (오래된 것부터): " + (0 until (w.optJSONArray("uqn")?.length() ?: 0)).joinToString(", ") { w.getJSONArray("uqn").getString(it) })
        if (out.isEmpty()) out.add("#888888" to "정리·업로드할 것 없음")
        w.optJSONArray("tr")?.let { if (it.optInt(0) > 0) out.add("#888888" to "휴지통 ${it.optInt(0)}개 · ${it.optLong(1)}MB (업로드 끝난 원본, ${it.optString(2, "하루")} 뒤 자동 삭제)") }
        val ud = w.optJSONArray("ud")
        if (ud != null && ud.length() > 0) out.add("#555555" to "최근 업로드 완료: " + (0 until ud.length()).joinToString(" / ") { ud.getJSONArray(it).let { a -> a.optString(0) + " (" + fmt.format(java.util.Date(a.optLong(1))) + ")" } })
        return out
    }

    /** 정리·업로드 카드 (녹화폰 현황 / 뷰어 공통) */
    fun fillWork(box: android.widget.LinearLayout, w: JSONObject) {
        val c = box.context; val d = c.resources.displayMetrics.density
        box.removeAllViews()
        box.addView(android.widget.TextView(c).apply { text = "정리 · 업로드"; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#6A1B9A")); setPadding((4 * d).toInt(), (10 * d).toInt(), 0, (6 * d).toInt()) })
        val card = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = 12 * d } }
        for ((col, t) in workLines(w)) card.addView(android.widget.TextView(c).apply { text = t; textSize = 13f; setTextColor(android.graphics.Color.parseColor(col)); setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt()) })
        box.addView(card)
    }

    /** 뷰어 → 녹화폰: 중지·다시 시도 */
    fun sendCommand(ctx: Context, cmd: String, name: String) {
        val app = ctx.applicationContext
        Thread {
            try { relay(app, "notify", JSONObject().put("title", "명령").put("body", "$cmd|$name").put("from", deviceId(app)).toString()) } catch (_: Exception) {}
        }.start()
    }

    /** 녹화폰 → 원격폰 알림 (녹화 완료·업로드 완료 등) */
    fun notifyRemote(ctx: Context, title: String, body: String, force: Boolean = false) {
        if (BuildConfig.LAB || !recordHere(ctx) || (!notifyOn(ctx) && !force)) return
        val app = ctx.applicationContext
        Thread {
            try {
                val js = JSONObject().put("title", title).put("body", body).put("from", deviceId(app)).toString()
                relay(app, "notify", js)
            } catch (_: Exception) {}
        }.start()
    }
    @Volatile private var loopOn = false
    private val lock = Object()

    /** 이 폰에서 즐겨찾기가 바뀜 → 곧 올림 */
    /** 화질·설정을 이 폰에서 바꿈 → 시각 기록 후 올림 */
    fun qualityChanged(ctx: Context) { prefs(ctx).edit().putLong(qtKey(ctx), System.currentTimeMillis()).apply(); changed(ctx) }
    fun settingsChanged(ctx: Context) { prefs(ctx).edit().putLong(stKey(ctx), System.currentTimeMillis()).apply(); changed(ctx) }

    fun changed(ctx: Context) {
        if (!on(ctx)) return
        if (!recordHere(ctx) && !viewer(ctx)) return   // 다운로드 모드: 이 폰 변경은 올리지 않음
        prefs(ctx).edit().putBoolean("dirty", true).apply()
        val app = ctx.applicationContext
        Thread { Thread.sleep(800); sync(app) }.start()
    }

    /** 30초마다 확인 (앱·녹화 서비스가 살아 있는 동안) */
    fun startLoop(ctx: Context) {
        appCtxRef = ctx.applicationContext
        startRenewLoop(ctx)
        if (loopOn) return
        loopOn = true
        val app = ctx.applicationContext
        Thread({
            while (true) {
                try { if (on(app)) sync(app) } catch (_: Exception) {}
                // 푸시가 되면 바뀔 때 바로 오니 10분마다 확인만 (빠진 푸시 대비), 안 되면 30초마다
                try { Thread.sleep(if (Push.ready) 10 * 60_000L else 30_000L) } catch (_: InterruptedException) { break }
            }
        }, "cloudBm").apply { isDaemon = true }.start()
    }

    private fun get(u: String): JSONObject {
        client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("응답 ${r.code}")
            return try { JSONObject(s) } catch (e: Exception) { throw Exception("주소가 맞는지 확인 (스크립트 응답이 아님)") }
        }
    }

    private fun post(u: String, body: String): JSONObject {
        val rb = body.toRequestBody("text/plain; charset=utf-8".toMediaType())
        client.newCall(okhttp3.Request.Builder().url(u).post(rb).build()).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("응답 ${r.code}")
            return JSONObject(s)
        }
    }

    /** 맞추기: 결과 문구 돌려줌 */
    fun sync(ctx: Context): String = synchronized(lock) {
        val base = url(ctx)
        if (!on(ctx)) return "구글 주소가 없어요"
        val p = prefs(ctx)
        // 원격폰: 예전 판 동기화로 녹화폰의 자동 켬이 이 폰에 섞여 들어옴 → 한 번 모두 끔 (이 폰은 자동 다운 안 함)
        if (!recordHere(ctx) && !p.getBoolean("localWatchReset", false)) {
            val n = Bookmarks.clearLocalWatch(ctx)
            p.edit().putBoolean("localWatchReset", true).apply()
            if (n > 0) RecordService.addLog("원격폰: 섞여 들어온 자동 켬 ${n}개를 껐어요 (이 폰은 자동 다운 안 함)")
        }
        val viewer = viewer(ctx)
        val download = !recordHere(ctx) && !viewer
        // 예전 판은 자동 켬/끔을 빼고 올렸음 → 이번 판에서 녹화폰이 한 번 다시 올림
        val fmtOld = !viewer && !download && p.getInt("fmt", 0) < 2
        val dirty = (p.getBoolean("dirty", false) || fmtOld) && !download
        try {
            val ver = p.getLong("ver", 0)
            if (!approved(ctx)) register(ctx)
            val r = get("$base?v=$ver&" + auth(ctx))
            if (r.optString("err") == "auth") { lastResult = "이 폰은 아직 승인 전이에요 (등록된 폰에 온 알림에서 '승인'을 눌러 주세요)"; return lastResult }
            val remoteVer = r.optLong("ver", 0)
            if (r.optBoolean("same") && !dirty) { lastResult = "최신 상태"; return lastResult }
            // 즐겨찾기 저장 위치: 뷰어 모드면 '녹화폰 거울'(따로 보관), 아니면 이 폰 것
            val local = Bookmarks.all(ctx)
            val remote = r.optString("data").takeIf { it.startsWith("{") }?.let { JSONObject(it) }
            val remoteB = remote?.optJSONArray("b")?.let { runCatching { Bookmarks.fromJson(it) }.getOrNull() } ?: emptyList()
            val remoteC = remote?.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

            // 녹화폰 확정: 여러 대 가능 (1·2·3번). recs = [{id,name,no}], rec = 1번(대표 · 뷰어 현황용)
            val me = deviceId(ctx)
            val recsIn = remote?.optJSONArray("recs")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                ?: remote?.optJSONObject("rec")?.takeIf { it.optString("id").isNotEmpty() }?.let { listOf(JSONObject().put("id", it.optString("id")).put("name", it.optString("name")).put("no", 1)) }
                ?: emptyList()
            val recs = recsIn.toMutableList()
            when (p.getInt("wantRec", -1)) {
                1 -> if (recs.none { it.optString("id") == me }) {
                    val used = recs.map { it.optInt("no") }.toSet()
                    recs.add(JSONObject().put("id", me).put("name", android.os.Build.MODEL).put("no", (1..99).first { it !in used }))
                }
                0 -> recs.removeAll { it.optString("id") == me }
            }
            recs.sortBy { it.optInt("no") }
            val recChanged = recs.map { it.toString() } != recsIn.sortedBy { it.optInt("no") }.map { it.toString() }
            val recIdNow = recs.firstOrNull()?.optString("id").orEmpty(); val recNameNow = recs.firstOrNull()?.optString("name").orEmpty()
            val myNo = recs.firstOrNull { it.optString("id") == me }?.optInt("no") ?: 0
            p.edit().putString("recId", recIdNow).putString("recName", recNameNow).putInt("myNo", myNo).putInt("recCount", recs.size).putInt("wantRec", -1).apply()
            if (recChanged && (download || remote == null)) {
                // 다운로드 모드는 원래 안 올리지만 확정 정보는 올려야 함
                remote?.put("rec", JSONObject().put("id", recIdNow).put("name", recNameNow))?.put("recs", JSONArray(recs))
                post("$base?op=save&" + auth(ctx), (remote ?: JSONObject()).toString())
            }

            if (download) {
                // 다운로드 모드: 녹화폰 즐겨찾기를 이 폰에 '복사만' (새로 생긴 것만 더함, 올리지 않음)
                val have = local.map { it.url }.toSet()
                val add = remoteB.filter { !it.del && it.url !in have }.map { Bookmarks.Item(it.name, it.url, it.t, false, it.cat, false) }
                if (add.isNotEmpty()) {
                    Bookmarks.saveAll(ctx, local + add)
                    Bookmarks.setCats(ctx, (Bookmarks.cats(ctx) + remoteC).distinct())
                    RecordService.addLog("녹화폰 즐겨찾기 ${add.size}개를 이 폰에 복사")
                }
                p.edit().putLong("ver", remoteVer).putBoolean("dirty", false).apply()
                lastResult = "녹화폰 즐겨찾기 복사 완료 (${add.size}개 추가)"
                return lastResult
            }

            var merged = when {
                r.optBoolean("same") -> local
                viewer && !dirty -> remoteB          // 뷰어: 녹화폰 기준 그대로 (거울)
                else -> Bookmarks.merge(local, remoteB)
            }
            // 녹화폰: 뷰어에서 새로 들어온 즐겨찾기는 바로 '생방 대기'로
            val had = local.map { it.url }.toSet()
            val fresh = merged.filter { !it.del && !it.watch && it.url !in had }.map { it.url }.toSet()
            if (!viewer && fresh.isNotEmpty() && local.isNotEmpty()) {
                merged = merged.map { if (it.url in fresh) Bookmarks.Item(it.name, it.url, System.currentTimeMillis(), it.del, it.cat, true) else it }
                RecordService.addLog("뷰어에서 온 즐겨찾기 ${fresh.size}개 → 생방 대기로 등록")
            }
            val cats = (Bookmarks.cats(ctx) + remoteC).distinct()
            Bookmarks.saveAll(ctx, merged)
            Bookmarks.setCats(ctx, cats)
            if (!viewer && Watcher.any(ctx)) { if (RecordService.instance != null) Watcher.start(ctx) else RecordService.startWatch(ctx) }

            // 채팅 설정: 녹화폰 ↔ 뷰어, 더 최근에 바꾼 쪽으로 (화질은 녹화폰 자기 설정대로)
            val rs = remote?.optJSONObject("s"); val rst = remote?.optLong("st") ?: 0L
            if (rs != null && rst > p.getLong(stKey(ctx), 0)) {
                ctx.getSharedPreferences("main", Context.MODE_PRIVATE).edit().putBoolean(chatKey(ctx), rs.optBoolean("chatKoreanOnly", true)).apply()
                p.edit().putLong(stKey(ctx), rst).apply()
            }
            val sOut = JSONObject().put("chatKoreanOnly", ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getBoolean(chatKey(ctx), true))
            val out = JSONObject().put("b", Bookmarks.toJson(merged)).put("c", JSONArray(cats))
                .put("rec", JSONObject().put("id", recIdNow).put("name", recNameNow)).put("recs", JSONArray(recs))
                .put("s", sOut).put("st", maxOf(p.getLong(stKey(ctx), 0), rst)).toString()
            var newVer = remoteVer
            if (dirty || remote == null || out != remote.toString()) {
                newVer = post("$base?op=save&" + auth(ctx), out).optLong("ver", remoteVer)
            }
            p.edit().putLong("ver", newVer).putBoolean("dirty", false).putInt("fmt", 2).apply()
            lastResult = "맞춤 완료 (즐겨찾기 ${merged.count { !it.del }}개)"
        } catch (e: Exception) {
            lastResult = "맞추기 실패: ${e.message ?: e.javaClass.simpleName}"
        }
        lastResult
    }

    /** 구글 앱스 스크립트에 붙여 넣을 코드 */
    val SCRIPT = """
const FILE = 'live-recorder-bookmarks.json';
function file_() {
  const it = DriveApp.getFilesByName(FILE);
  return it.hasNext() ? it.next() : DriveApp.createFile(FILE, '');
}
function out_(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function doGet(e) {
  const ver = Number(PropertiesService.getScriptProperties().getProperty('ver') || 0);
  if (e.parameter.v && Number(e.parameter.v) === ver) return out_({ ver: ver, same: true });
  return out_({ ver: ver, data: file_().getBlob().getDataAsString('UTF-8') });
}
function doPost(e) {
  const lock = LockService.getScriptLock(); lock.waitLock(20000);
  try {
    file_().setContent(e.postData.contents);
    const ver = Date.now();
    PropertiesService.getScriptProperties().setProperty('ver', String(ver));
    return out_({ ver: ver });
  } finally { lock.releaseLock(); }
}
""".trimIndent()
}
