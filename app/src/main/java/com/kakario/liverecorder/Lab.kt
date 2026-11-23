package com.kakario.liverecorder

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 실험판 전용: "숨은 재생기 없이" 가볍게 받을 수 있는지 시험하고, 결과를 원본 그대로 보고서로 만든다.
 *  1) 방송 대기 확인: 창 하나 안에서 여러 스트리머의 방송 여부를 묻기 (100명 이상 등록 대비)
 *  2) 직접 받기 조사: 재생기가 받는 방송 목록(m3u8)·조각 주소를 원본 그대로 모으고, 암호 풀기를 시도
 *  3) 직접 받기 시험: 재생기 없이 방송 목록만 읽어서 조각을 직접 받아 파일로 저장
 */
object Lab {
    val on get() = BuildConfig.LAB
    private val main = Handler(Looper.getMainLooper())
    @Volatile var appCtx: Context? = null

    // 채팅 연결: 페이지가 보낸 것(입장권·구독)을 통째로 기억 → 앱이 똑같이 보내서 직접 받기 시험
    val wsUrl = ConcurrentHashMap<Int, String>()
    val wsSent = ConcurrentHashMap<Int, MutableList<String>>()
    val pageChans = ConcurrentHashMap<String, Int>()
    private val lastWsUrl = ConcurrentHashMap<Int, String>()
    @Volatile var spConnect: String? = null
    @Volatile var spUrl = "wss://websocket-sp-v6.stripchat.com/connection/websocket"
    fun onWs(slotId: Int, kind: String, text: String) {
        if (kind == "받음") Regex("\"push\":\\{\"channel\":\"([^\"]+)\"").findAll(text).forEach { m ->
            val ch = m.groupValues[1].replace(Regex("[0-9]+"), "N")
            if (pageChans.merge(ch, 1, Int::plus) == 1) add("페이지 채팅 연결에서 받은 채널 (처음 본 것)", ch + " · 예: " + text.substringAfter(m.value).take(300))
        }
        if (kind == "연결") lastWsUrl[slotId] = text.substringBefore(" | ")
        // 채팅 서버(websocket-sp)로 보낸 입장권만 따로 기억
        if (kind == "보냄" && lastWsUrl[slotId]?.contains("websocket-sp") == true) {
            text.lines().firstOrNull { it.contains("\"connect\"") && it.contains("token") }?.let { spConnect = it; spUrl = lastWsUrl[slotId]!! }
        }
        when (kind) {
            "연결" -> { wsUrl[slotId] = text.substringBefore(" | "); wsSent.remove(slotId) }
            "보냄" -> wsSent.getOrPut(slotId) { java.util.Collections.synchronizedList(ArrayList()) }.let { if (it.size < 300) it.add(text) }
        }
    }
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    // ---------- 진행 상황 (화면에 보여 주기용) ----------
    val progress = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var running = 0
    @Synchronized fun recent(n: Int): String {
        val all = sections.values.flatMap { it.lines().filter { l -> l.isNotBlank() } }
        return all.takeLast(n).joinToString("\n")
    }

    // ---------- 보고서 ----------
    private val sections = java.util.LinkedHashMap<String, StringBuilder>()
    @Synchronized fun add(section: String, line: String) {
        sections.getOrPut(section) { StringBuilder() }.append("[").append(fmt.format(Date())).append("] ").append(line).append('\n')
    }
    @Synchronized fun clear() { sections.clear(); probes.clear(); progress.clear() }

    @Synchronized fun report(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("=== 라이브 녹화 실험 결과 ===\n")
        sb.append("앱 ").append(BuildConfig.VERSION_NAME).append(" / ").append(android.os.Build.MODEL).append(" / 안드로이드 ").append(android.os.Build.VERSION.RELEASE).append('\n')
        sb.append("메모리: ").append(memText(ctx)).append('\n')
        // 시험 결과를 먼저, 재생기 원본(길다)은 맨 뒤에 짧게
        sections.forEach { (k, v) -> sb.append("\n##### ").append(k).append(" #####\n").append(v) }
        probes.values.take(2).forEach { it.dump(sb) }
        return sb.toString()
    }

    /** 보고서를 파일로 저장하고 클립보드에 복사 */
    fun export(ctx: Context): String {
        val text = report(ctx)
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Saver.DIR).apply { mkdirs() }
        val f = File(dir, "실험결과 " + SimpleDateFormat("yyyyMMdd HHmmss", Locale.KOREA).format(Date()) + ".txt")
        try { f.writeText(text) } catch (_: Exception) {}
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("실험 결과", if (text.length > 200_000) text.take(200_000) + "\n…(잘림, 전체는 파일)" else text))
        return f.absolutePath
    }

    fun memText(ctx: Context): String {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = try { android.os.Debug.getPss() / 1024 } catch (e: Exception) { -1 }
        return "앱 ${pss}MB · 폰 여유 ${mi.availMem / 1048576}/${mi.totalMem / 1048576}MB"
    }

    // ---------- 2) 재생기가 받는 것 원본 그대로 모으기 ----------
    class Probe(val slotId: Int, val name: String) {
        var master: String? = null; var masterUrl = ""
        val playlists = ArrayList<Pair<String, String>>()   // 주소, 내용
        var playlistHeaders = ""
        val segs = ArrayList<String>()
        var segHeaders = ""
        val pairs = ArrayList<Pair<String, String>>()       // 암호 글자, 그 다음 주소 줄
        var decodeNote = ""

        @Synchronized fun dump(sb: StringBuilder) {
            sb.append("\n##### 재생기 원본: ${slotId}번 $name #####\n")
            sb.append("--- 전체 목록(master) 주소: ").append(masterUrl).append('\n')
            sb.append(master?.lines()?.take(60)?.joinToString("\n") ?: "(못 받음)").append('\n')
            sb.append("--- 방송 목록 요청 머리글: ").append(playlistHeaders).append('\n')
            playlists.take(1).forEachIndexed { i, (u, t) ->
                sb.append("--- 방송 목록 ${i + 1} 주소: ").append(u).append('\n')
                val ls = t.lines()
                sb.append(ls.take(40).joinToString("\n")).append('\n')
                if (ls.size > 40) sb.append("…(${ls.size}줄 중 40줄)\n")
            }
            sb.append("--- 재생기가 실제로 요청한 조각 주소 (처음 ${segs.size}개)\n")
            segs.take(8).forEach { sb.append(it).append('\n') }
            sb.append("--- 조각 요청 머리글: ").append(segHeaders).append('\n')
            sb.append("--- 암호 풀기 시도\n").append(decodeNote.ifEmpty { "(암호 줄 없음 또는 아직 분석 전)" }).append('\n')
        }
    }

    val probes = ConcurrentHashMap<Int, Probe>()
    private fun probe(slotId: Int, name: String) = probes.getOrPut(slotId) { Probe(slotId, name) }

    fun onMaster(slotId: Int, name: String, url: String, body: String) {
        if (!on) return
        val p = probe(slotId, name)
        synchronized(p) { if (p.master == null) { p.master = body; p.masterUrl = url } }
    }

    /** 재생기의 방송 목록 요청: 처음 몇 개만 앱이 대신 받아 원본을 남기고 그대로 넘겨줌 */
    fun wantPlaylist(slotId: Int): Boolean = on && (probes[slotId]?.playlists?.size ?: 0) < 4

    fun onPlaylist(slotId: Int, name: String, url: String, headers: Map<String, String>, body: String) {
        val p = probe(slotId, name)
        synchronized(p) {
            if (p.playlists.size < 4) p.playlists.add(url to body)
            if (p.playlistHeaders.isEmpty()) p.playlistHeaders = headers.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
            val ls = body.lines()
            for (i in ls.indices) {
                val l = ls[i]
                if (l.startsWith("#EXT-X-MOUFLON:FILE:")) {
                    val next = ls.drop(i + 1).firstOrNull { it.isNotBlank() && (!it.startsWith("#") || it.startsWith("#EXT-X-PART")) } ?: ""
                    if (p.pairs.size < 60) p.pairs.add(l.removePrefix("#EXT-X-MOUFLON:FILE:").trim() to next.trim())
                }
            }
        }
        analyze(p, url, body)
    }

    fun onSegment(slotId: Int, name: String, url: String, headers: Map<String, String>) {
        if (!on) return
        val p = probe(slotId, name)
        synchronized(p) {
            if (p.segs.size < 40) p.segs.add(url)
            if (p.segHeaders.isEmpty()) p.segHeaders = headers.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
        }
        // 재생기가 요청한 진짜 이름과 암호를 맞춰 보며 풀이 방식 찾기
        if (p.playlists.isNotEmpty() && p.segs.size in listOf(6, 12, 20, 30)) analyze(p, p.playlists.lastOrNull()?.first ?: "", p.playlists.lastOrNull()?.second ?: "")
    }

    private fun b64(s: String): ByteArray? {
        for (t in listOf(s, s.reversed())) {
            for (flag in listOf(android.util.Base64.DEFAULT, android.util.Base64.URL_SAFE)) {
                try { val pad = t + "=".repeat((4 - t.length % 4) % 4); return android.util.Base64.decode(pad, flag) } catch (_: Exception) {}
            }
        }
        return null
    }
    private fun b64All(s: String): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        for ((tn, t) in listOf("그대로" to s, "뒤집어" to s.reversed())) for ((fn, flag) in listOf("기본" to android.util.Base64.DEFAULT, "URL" to android.util.Base64.URL_SAFE)) {
            try { out.add("$tn/$fn" to android.util.Base64.decode(t + "=".repeat((4 - t.length % 4) % 4), flag)) } catch (_: Exception) {}
        }
        return out
    }
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun xor(d: ByteArray, k: ByteArray) = ByteArray(d.size) { (d[it].toInt() xor k[it % k.size].toInt()).toByte() }
    private fun printable(b: ByteArray) = b.isNotEmpty() && b.all { it in 0x20..0x7e }

    /** pkey별로 알아낸 풀이 열쇠(16바이트) */
    val keys = ConcurrentHashMap<String, ByteArray>().apply {
        // 지금까지 확인된 열쇠 (사이트가 바꾸면 재생기로 다시 배움)
        put("NTK9aqcLmNFMWrpQ", "1838c78f543606e6de5fc81167f36e5a".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
    fun loadKeys(ctx: Context) {
        try {
            val o = org.json.JSONObject(ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getString("mfKeys", "{}") ?: "{}")
            o.keys().forEach { k -> keys[k] = o.getString(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        } catch (_: Exception) {}
    }
    fun saveKeys(ctx: Context) {
        val o = org.json.JSONObject()
        keys.forEach { (k, v) -> o.put(k, v.joinToString("") { "%02x".format(it) }) }
        ctx.getSharedPreferences("main", Context.MODE_PRIVATE).edit().putString("mfKeys", o.toString()).apply()
    }
    private val segRe = Regex("""_(\d+)_([A-Za-z0-9+/=]{16,})_(\d+)(_part\d+)?\.mp4""")
    private val plainRe = Regex("""_(\d+)_([A-Za-z0-9]{16})_(\d+)(_part\d+)?\.mp4""")

    /** 거꾸로 뒤집어 base64로 풀고 열쇠와 XOR */
    fun decodeToken(enc: String, ks: ByteArray): String? = try {
        val r = enc.reversed()
        val b = android.util.Base64.decode(r + "=".repeat((4 - r.length % 4) % 4), android.util.Base64.DEFAULT)
        val o = ByteArray(b.size) { (b[it].toInt() xor ks[it % ks.size].toInt()).toByte() }
        if (o.all { it in 0x21..0x7e }) String(o) else null
    } catch (e: Exception) { null }

    /** 암호 주소 한 줄 → 진짜 주소 */
    fun decodeUrl(url: String, ks: ByteArray): String? {
        val m = segRe.find(url) ?: return null
        val dec = decodeToken(m.groupValues[2], ks) ?: return null
        return url.replaceRange(m.groups[2]!!.range, dec)
    }

    /** 재생기가 요청한 진짜 이름과 목록의 암호를 (번호+시각)으로 짝지어 열쇠를 알아냄 */
    private fun analyze(p: Probe, url: String, body: String) {
        val pkey = Regex("""[?&]pkey=([^&]+)""").find(url)?.groupValues?.get(1) ?: return
        val encMap = HashMap<String, String>()   // "번호_시각" → 암호
        body.lines().filter { it.startsWith("#EXT-X-MOUFLON:URI:") }.forEach { l ->
            segRe.find(l)?.let { encMap[it.groupValues[1] + "_" + it.groupValues[3]] = it.groupValues[2] }
        }
        val segs = synchronized(p) { p.segs.toList() }
        val sb = StringBuilder()
        var found: ByteArray? = null; var agree = 0; var tried = 0
        for (sUrl in segs) {
            val m = plainRe.find(sUrl) ?: continue
            val enc = encMap[m.groupValues[1] + "_" + m.groupValues[3]] ?: continue
            tried++
            val r = enc.reversed()
            val b = try { android.util.Base64.decode(r + "=".repeat((4 - r.length % 4) % 4), android.util.Base64.DEFAULT) } catch (e: Exception) { continue }
            val plain = m.groupValues[2].toByteArray()
            if (b.size != plain.size) continue
            val ks = ByteArray(b.size) { (b[it].toInt() xor plain[it].toInt()).toByte() }
            if (found == null) found = ks
            if (found.contentEquals(ks)) agree++
        }
        if (found != null && agree >= 2 && agree == tried) {
            keys[pkey] = found
            appCtx?.let { saveKeys(it) }
            sb.append("풀이 열쇠 찾음 (pkey $pkey): ").append(found.joinToString("") { "%02x".format(it) }).append(" · ${agree}개 모두 일치\n")
        } else sb.append("풀이 열쇠 아직: 짝 ${tried}개 중 일치 $agree\n")
        synchronized(p) { p.decodeNote = sb.toString() }
    }

    // ---------- 3) 직접 받기 시험: 재생기 없이 ----------
    /**
     * 재생기가 쓰던 방송 목록 주소를 받아, 앱이 혼자서 1초마다 목록을 읽고 새 조각을 받아 저장해 본다.
     * 결과: 받은 조각 수, 실패 수(응답 번호별), 파일 크기, 걸린 자원 → 보고서
     */
    fun directTest(ctx: Context, slotId: Int, seconds: Int, done: () -> Unit) {
        val p = probes[slotId]
        val sec = "직접 받기 시험 ${slotId}번 ${p?.name ?: ""}"
        val plUrl0 = p?.playlists?.lastOrNull()?.first
        if (p == null || plUrl0 == null) { add(sec, "방송 목록 원본이 아직 없음 (녹화 중인 방송에서 1분쯤 받은 뒤 다시)"); progress["② 직접 받기 ${slotId}번"] = "못 함 · 원본 없음"; done(); return }
        val headers = p.playlistHeaders.split(" | ").mapNotNull { val i = it.indexOf(": "); if (i > 0) it.substring(0, i) to it.substring(i + 2) else null }
            .filter { it.first.lowercase() in setOf("origin", "referer", "user-agent", "accept", "accept-language") }.toMap()
        directRun(ctx, sec, "② 직접 받기 ${slotId}번 ${p.name}", p.name, plUrl0, headers, seconds, done)
    }

    /** 재생기 없이 방송 목록 주소 하나로 직접 받기 (시험용 공통) */
    fun directRun(ctx: Context, sec: String, pk: String, name: String, plUrl0: String, headers: Map<String, String>, seconds: Int, done: () -> Unit) {
        running++
        progress[pk] = "시작 중…"
        Thread {
            val plUrl = plUrl0.replace(Regex("""[?&]_HLS_(msn|part|skip)=[^&]*"""), "").let { if (!it.contains('?') && it.contains('&')) it.replaceFirst('&', '?') else it }
            add(sec, "시작: ${seconds}초 · 목록 주소 $plUrl")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Saver.DIR).apply { mkdirs() }
            val out = File(dir, "직접받기 시험 $name " + SimpleDateFormat("HHmmss", Locale.KOREA).format(Date()) + ".mp4")
            val fos = out.outputStream().buffered(256 * 1024)
            val seen = HashSet<String>()
            val codes = HashMap<String, Int>()
            var segOk = 0; var bytes = 0L; var initDone = false; var polls = 0; var listFail = 0
            val cpu0 = android.os.Process.getElapsedCpuTime()
            val tcpu0 = android.os.SystemClock.currentThreadTimeMillis()
            val t0 = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - t0 < seconds * 1000L) {
                    polls++
                    progress[pk] = "${(System.currentTimeMillis() - t0) / 1000}/${seconds}초 · 조각 ${segOk}개 · ${bytes / 1024}KB · 응답 $codes"
                    val r = try { get(plUrl, headers) } catch (e: Exception) { null }
                    if (r == null || r.code !in 200..299) { listFail++; if (listFail <= 3) add(sec, "목록 받기 실패: ${r?.code ?: "연결 오류"}"); Thread.sleep(1000); continue }
                    val text = String(r.body)
                    if (polls == 1) add(sec, "직접 받은 목록 첫 40줄:\n" + text.lines().take(40).joinToString("\n"))
                    val pkey = Regex("""[?&]pkey=([^&]+)""").find(plUrl)?.groupValues?.get(1) ?: ""
                    val ks = keys[pkey]
                    if (ks == null) { if (polls == 1) add(sec, "풀이 열쇠가 없음 (pkey $pkey) → 재생기로 1분 이상 받은 뒤 다시"); Thread.sleep(1500); continue }
                    val ls = text.lines()
                    for ((idx, l0) in ls.withIndex()) {
                        val l = l0.trim()
                        if (l.startsWith("#EXT-X-MAP:")) {
                            if (initDone) continue
                            val u = Regex("""URI="([^"]+)"""").find(l)?.groupValues?.get(1) ?: continue
                            val ir = try { get(resolve(plUrl, u), headers) } catch (e: Exception) { null }
                            codes["시작정보 ${ir?.code ?: "오류"}"] = (codes["시작정보 ${ir?.code ?: "오류"}"] ?: 0) + 1
                            if (ir != null && ir.code in 200..299) { fos.write(ir.body); bytes += ir.body.size; initDone = true }
                            continue
                        }
                        // 통째 조각만 받음: 암호 주소 줄 바로 다음 줄이 실제 조각 줄(# 아님)인 것
                        if (!l.startsWith("#EXT-X-MOUFLON:URI:")) continue
                        val next = ls.getOrNull(idx + 1)?.trim() ?: continue
                        if (next.startsWith("#")) continue
                        val encUrl = l.removePrefix("#EXT-X-MOUFLON:URI:").trim()
                        val full = decodeUrl(encUrl, ks)
                        if (full == null) { codes["풀기 실패"] = (codes["풀기 실패"] ?: 0) + 1; continue }
                        if (!initDone) continue
                        if (!seen.add(full.substringBefore('?'))) continue
                        val sr = try { get(full, headers) } catch (e: Exception) { null }
                        val c = "조각 ${sr?.code ?: "오류"}"
                        codes[c] = (codes[c] ?: 0) + 1
                        if (sr != null && sr.code in 200..299) {
                            if (segOk == 0) add(sec, "첫 조각: ${full.substringAfterLast('/')} · ${sr.body.size}바이트 · 상자: " + boxes(sr.body))
                            fos.write(sr.body); bytes += sr.body.size; segOk++
                        } else if ((codes[c] ?: 0) <= 2) add(sec, "조각 실패 ${sr?.code}: ${full.take(200)}")
                    }
                    // 앱 혼자 받을 때 실제 부담(이 시험 스레드만)
                    Thread.sleep(1500)
                }
            } catch (e: Exception) {
                add(sec, "중단: ${e.message}")
            } finally {
                try { fos.close() } catch (_: Exception) {}
            }
            val cpuMs = android.os.Process.getElapsedCpuTime() - cpu0
            val myCpu = android.os.SystemClock.currentThreadTimeMillis() - tcpu0
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            add(sec, "결과: 조각 ${segOk}개 · ${bytes / 1024}KB · 목록 ${polls}번 읽음(실패 $listFail) · 응답 $codes")
            add(sec, "자원: 이 직접 받기만 CPU ${myCpu}ms / ${"%.0f".format(secs)}초 (= ${"%.1f".format(myCpu / 10.0 / secs)}%) · 비교: 앱 전체(숨은 재생기 포함) ${cpuMs}ms (= ${"%.0f".format(cpuMs / 10.0 / secs)}%) · " + memText(ctx))
            add(sec, "저장: ${out.name} (" + (if (segOk > 0) "동영상 앱으로 열어 재생되는지 확인해 주세요" else "받은 게 없음") + ")")
            Saver.scan(ctx, out.absolutePath)
            progress[pk] = "끝 · 조각 ${segOk}개 · ${bytes / 1024}KB" + (if (segOk == 0) " (실패)" else "")
            running--
            main.post(done)
        }.start()
    }

    private fun get(url: String, headers: Map<String, String>): HttpResult {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return HttpResult(code, conn.contentType, body, url, conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") })
    }

    private fun resolve(base: String, u: String) = try { java.net.URL(java.net.URL(base), u).toString() } catch (e: Exception) { u }

    private fun boxes(b: ByteArray): String {
        val sb = StringBuilder(); var i = 0; var n = 0
        while (i + 8 <= b.size && n < 6) {
            val size = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            sb.append(type).append('(').append(size).append(") ")
            if (size < 8) break
            i += size; n++
        }
        return sb.toString().ifEmpty { "알 수 없음" }
    }

    // ---------- 한 번에 시험: 방송 서버 확인 + 재생기 없이 녹화 + 채팅 직접 받기 ----------
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val CDN_H = mapOf("Origin" to "https://ko.stripchat.com", "Referer" to "https://ko.stripchat.com/", "Accept" to "*/*", "User-Agent" to UA)

    fun allInOne(ctx: Context, seconds: Int, done: () -> Unit) {
        appCtx = ctx.applicationContext
        loadKeys(ctx)
        val sec = "한 번에 시험"
        running++
        progress["전체 시험"] = "방송 서버에 물어보는 중…"
        Thread {
            // 1) 자동 녹화 켠 스트리머 전부: 방송 중(200) / 꺼짐(404)
            val live = ArrayList<Triple<String, String, String>>()   // 이름, 주소, master 내용
            val rec = RecordService.list().map { Bookmarks.Item(it.streamer.ifEmpty { Names.streamerFromUrl(it.pageUrl) }, it.pageUrl) }
            val list = (Watcher.watched(ctx) + rec).filter { it.url.contains("stripchat") }.distinctBy { it.url.trimEnd('/').lowercase() }
            add(sec, "자동 녹화 켠 스트리머 + 녹화 중인 방송 ${list.size}명 확인 (번호 모르는 사람은 건너뜀)")
            var on = 0; var off = 0; var unknown = 0
            val t0 = System.currentTimeMillis()
            for (b in list) {
                val id = Watcher.idOf(ctx, b.url)
                if (id == null) { unknown++; add(sec, "${b.name}: 번호 모름"); continue }
                val u = "https://edge-hls.doppiocdn.org/hls/$id/master/${id}_auto.m3u8?playlistType=lowLatency"
                val r = try { get(u, CDN_H) } catch (e: Exception) { null }
                val body = r?.body?.let { String(it) } ?: ""
                val vs = Regex("""NAME="([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
                add(sec, "${b.name} ($id) → ${r?.code ?: "오류"} " + (if (vs.isNotEmpty()) "방송 중 · 화질 ${vs.joinToString(",")}" + (if (body.contains("blurred")) " · 흐린 화면 있음" else "") else "꺼짐"))
                if (r != null && r.code in 200..299 && vs.isNotEmpty()) { on++; live.add(Triple(b.name, u, body)) } else off++
                Thread.sleep(150)
            }
            add(sec, "결과: 방송 중 $on · 꺼짐 $off · 번호 모름 $unknown · ${System.currentTimeMillis() - t0}ms")
            // 2) 방송 중인 사람 최대 4명: 재생기 없이 master → 화질 고르기 → 직접 받기
            val pick = live.take(4)
            if (pick.isEmpty()) add(sec, "방송 중인 자동 녹화 스트리머가 없어서 직접 녹화 시험은 건너뜀")
            var left = pick.size + 1
            val fin = { left--; if (left == 0) { progress["전체 시험"] = "끝"; running--; main.post(done) } }
            for ((name, mu, body) in pick) {
                val target = Quality.forUrl(ctx, "https://ko.stripchat.com/$name")
                val pk = body.lines().filter { it.startsWith("#EXT-X-MOUFLON:PSCH:") }.map { it.substringAfterLast(':').trim() }.firstOrNull { keys.containsKey(it) }
                if (pk == null) { add(sec, "$name: 아는 열쇠가 없음 (재생기로 1분 녹화하면 배움)"); fin(); continue }
                // 목표 화질부터 차례로 실제로 열리는지 확인 (어떤 화질은 404가 나기도 함)
                var pl: String? = null
                for (v in rankVariants(body, target)) {
                    val u = v.second + (if (v.second.contains('?')) "&" else "?") + "psch=v2&pkey=$pk"
                    val c = try { get(u, CDN_H).code } catch (e: Exception) { -1 }
                    add(sec, "$name: ${v.first} 확인 → $c")
                    if (c in 200..299) { pl = u; add(sec, "$name: ${v.first} 고름 (목표 ${Quality.label(target)})"); break }
                }
                if (pl == null) { add(sec, "$name: 열리는 화질이 없음"); fin(); continue }
                directRun(ctx, "재생기 없이 녹화 시험 $name", "녹화 $name", name, pl, CDN_H, seconds) { fin() }
            }
            // 3) 채팅 직접 받기 (페이지가 보낸 입장권·구독을 그대로)
            chatTest(ctx, seconds) { fin() }
        }.start()
    }

    /** 목표 화질 이하를 좋은 순으로, 그다음 목표보다 높은 것 */
    private fun rankVariants(master: String, target: Int): List<Pair<String, String>> {
        val ls = master.lines()
        val vs = ArrayList<Triple<Int, String, String>>()
        for (i in ls.indices) if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(ls[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val n = Regex("""NAME="([^"]+)"""").find(ls[i])?.groupValues?.get(1) ?: "${h}p"
            val u = ls.getOrNull(i + 1)?.trim() ?: continue
            if (!n.contains("blurred", true)) vs.add(Triple(h, "$n(${h}p)", u))
        }
        return (vs.filter { it.first <= target }.sortedByDescending { it.first } + vs.filter { it.first > target }.sortedBy { it.first }).map { it.second to it.third }
    }

    /** master에서 목표 화질 이하 중 가장 좋은 것 (최고 화질이면 제일 큰 것) */
    private fun chooseVariant(master: String, target: Int): Pair<String, String>? {
        val ls = master.lines()
        val vs = ArrayList<Triple<Int, String, String>>()
        for (i in ls.indices) if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(ls[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val n = Regex("""NAME="([^"]+)"""").find(ls[i])?.groupValues?.get(1) ?: "${h}p"
            val u = ls.getOrNull(i + 1)?.trim() ?: continue
            if (!n.contains("blurred", true)) vs.add(Triple(h, n, u))
        }
        if (vs.isEmpty()) return null
        val ok = vs.filter { it.first <= target }.maxByOrNull { it.first } ?: vs.minByOrNull { it.first }!!
        return ok.second to ok.third
    }

    /** 앱이 직접 채팅 연결: 페이지가 보낸 것을 똑같이 보내고, 받은 것 중 채팅만 골라 셈 */
    fun chatTest(ctx: Context, seconds: Int, done: () -> Unit) {
        val sec = "채팅 직접 받기 시험"
        val conn = spConnect
        if (conn == null) { add(sec, "채팅 입장권이 아직 없음 → 재생기로 녹화 중인 방송이 1개 이상 있어야 해요"); done(); return }
        val url = spUrl
        // 지금 녹화 중이거나 자동 녹화 중인 방송들의 채팅 채널
        val ids = (RecordService.list().map { it.pageUrl } + Watcher.watched(ctx).map { it.url })
            .mapNotNull { Watcher.idOf(ctx, it) }.distinct().take(10)
        if (ids.isEmpty()) { add(sec, "채팅을 받을 스트리머 번호가 없음"); done(); return }
        val frames = listOf(conn) + ids.mapIndexed { i, id -> "{\"subscribe\":{\"channel\":\"newChatMessage@$id\"},\"id\":${i + 2}}" }
        progress["채팅"] = "연결 중…"
        add(sec, "앱이 직접 연결: 입장권 + 채팅 채널 ${ids.size}개 (newChatMessage@번호) · $url")
        val client = okhttp3.OkHttpClient.Builder().build()   // 사이트 방식 핑("{}")만 씀
        val req = okhttp3.Request.Builder().url(url).header("Origin", "https://ko.stripchat.com").header("User-Agent", UA).build()
        val chans = ConcurrentHashMap<String, Int>()
        var msgs = 0; var kor = 0; var samples = 0
        val hangul = Regex("[\\uAC00-\\uD7A3]")
        val t0 = System.currentTimeMillis()
        val ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
            var sentRest = false
            var rawN = 0
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                add(sec, "연결됨 (${response.code}) → 입장권 보냄")
                webSocket.send(frames.first())
            }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                if (rawN < 8) { rawN++; add(sec, "받은 원본 $rawN: " + text.take(400)) }
                if (text == "{}" || text.isBlank()) { webSocket.send("{}"); return }
                // 입장 답을 받은 뒤에 나머지(구독)를 보냄
                if (!sentRest && text.contains("\"connect\"")) { sentRest = true; frames.drop(1).forEach { webSocket.send(it) }; add(sec, "입장 성공 → 구독 ${frames.size - 1}개 보냄") }
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    val o = try { org.json.JSONObject(line) } catch (e: Exception) { continue }
                    val push = o.optJSONObject("push") ?: continue
                    val ch = push.optString("channel")
                    chans.merge(ch.substringBefore('@').substringBefore('#'), 1, Int::plus)
                    val data = push.optJSONObject("pub")?.optJSONObject("data") ?: continue
                    val chat = pickChat(data)
                    if (chat != null) {
                        msgs++; if (hangul.containsMatchIn(chat)) kor++
                        if (samples < 20) { samples++; add(sec, "채팅 [$ch] " + chat.replace('\n', ':').take(150)) }
                        if (samples == 1) add(sec, "채팅 원본 예: " + line.take(800))
                    }
                    progress["채팅"] = "받는 중 · 채팅 ${msgs}줄 (한국어 $kor)"
                }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                add(sec, "연결 실패: ${t.message} ${response?.code ?: ""}")
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { add(sec, "닫힘 $code $reason") }
        })
        main.postDelayed({
            ws.close(1000, "끝")
            add(sec, "결과: ${(System.currentTimeMillis() - t0) / 1000}초 동안 채팅 ${msgs}줄 (한국어 ${kor}줄)")
            add(sec, "받은 채널: " + chans.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" })
            progress["채팅"] = "끝 · ${msgs}줄"
            done()
        }, seconds * 1000L)
    }

    /** 채팅 한 줄 꺼내기: 닉네임 + 내용 */
    private fun pickChat(o: org.json.JSONObject, d: Int = 0): String? {
        if (d > 6) return null
        val u = o.optJSONObject("user") ?: o.optJSONObject("userData") ?: o.optJSONObject("author")
        val nick = u?.optString("username")?.ifEmpty { null } ?: o.optString("username").ifEmpty { null }
        val body = o.optJSONObject("details")?.optString("body")?.ifEmpty { null } ?: o.optString("text").ifEmpty { null } ?: o.optString("body").ifEmpty { null }
        if (nick != null && body != null) return "$nick\n$body"
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v is org.json.JSONObject) pickChat(v, d + 1)?.let { return it }
        }
        return null
    }

    // ---------- 1-2) 방송 대기 확인: 방송 서버의 목록(master) 주소만 가볍게 물어보기 ----------
    /** 재생기 원본에서 본 master 주소로 확인 (방송 중이면 200, 꺼지면 다른 응답이 나오는지) */
    fun masterCheckTest(ctx: Context, extraIds: List<String>, done: () -> Unit) {
        val sec = "방송 서버로 직접 확인 시험"
        running++
        progress["①-2 방송 서버 확인"] = "확인 중…"
        Thread {
            val list = ArrayList<Pair<String, String>>()
            probes.values.forEach { p -> if (p.masterUrl.isNotEmpty()) list.add(p.name to p.masterUrl) }
            // 같은 형식에 있지 않을 번호(꺼진 방송 흉내)도 비교
            extraIds.forEach { id -> list.add("번호 $id" to "https://edge-hls.doppiocdn.org/hls/$id/master/${id}_auto.m3u8?playlistType=lowLatency") }
            val h = mapOf("Origin" to "https://ko.stripchat.com", "Referer" to "https://ko.stripchat.com/", "Accept" to "*/*")
            val t0 = System.currentTimeMillis()
            for ((n, u) in list) {
                val s0 = System.currentTimeMillis()
                val r = try { get(u, h) } catch (e: Exception) { null }
                val t = r?.body?.let { String(it) } ?: ""
                val variants = Regex("""NAME="([^"]+)"""").findAll(t).map { it.groupValues[1] }.toList()
                add(sec, "$n → ${r?.code ?: "오류"} · ${System.currentTimeMillis() - s0}ms · ${r?.body?.size ?: 0}바이트 · 화질: ${variants.joinToString(",").ifEmpty { "-" }}" +
                    (if (r != null && r.code !in 200..299) " · 원본: " + t.take(200).replace('\n', ' ') else ""))
            }
            add(sec, "끝: ${list.size}개 ${System.currentTimeMillis() - t0}ms")
            progress["①-2 방송 서버 확인"] = "끝 · ${list.size}개"
            running--
            main.post(done)
        }.start()
    }

    // ---------- 1) 방송 대기 확인: 창 하나로 여러 명 ----------
    private var checker: WebView? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun checkerTest(ctx: Context, host: String, names: List<String>, ua: String, done: () -> Unit) {
        val sec = "방송 대기 확인 시험 (${names.size}명)"
        if (names.isEmpty()) { add(sec, "확인할 스트리머가 없음 (녹화 등록이나 즐겨찾기를 먼저)"); done(); return }
        add(sec, "시작: 창 하나(https://$host/robots.txt)에서 차례로 물어봄")
        running++
        progress["① 방송 대기 확인"] = "창 여는 중… (0/${names.size})"
        // 비교: 앱이 페이지 없이 바로 물어볼 때
        Thread {
            for (n in names.take(3)) {
                val u = "https://$host/api/front/v2/models/username/" + java.net.URLEncoder.encode(n, "UTF-8") + "/cam"
                val r = try { get(u, mapOf("User-Agent" to ua, "Accept" to "application/json")) } catch (e: Exception) { null }
                add(sec, "비교(창 없이 바로): $n → ${r?.code ?: "오류"} " + (r?.body?.let { String(it).take(150).replace('\n', ' ') } ?: ""))
            }
        }.start()
        val t0 = System.currentTimeMillis()
        var count = 0
        main.post {
            checker?.destroy()
            val w = WebView(ctx.applicationContext)
            checker = w
            w.settings.javaScriptEnabled = true
            w.settings.blockNetworkImage = true
            w.settings.userAgentString = ua
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.addJavascriptInterface(object {
                @JavascriptInterface fun res(n: String, code: Int, ms: Int, t: String) {
                    count++
                    progress["① 방송 대기 확인"] = "확인 중 $count/${names.size}명"
                    val st = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(t)?.groupValues?.get(1)
                    val live = Regex("\"isLive\"\\s*:\\s*(true|false)").find(t)?.groupValues?.get(1)
                    add(sec, "$n → 응답 $code · ${ms}ms · status=${st ?: "-"} · isLive=${live ?: "-"}" + (if (count <= 3) "\n   원본: " + t.take(600).replace('\n', ' ') else ""))
                }
                @JavascriptInterface fun done(total: Int) {
                    val s = (System.currentTimeMillis() - t0) / 1000.0
                    add(sec, "끝: ${total}명 확인에 ${"%.1f".format(s)}초 (한 명당 ${"%.0f".format(s * 1000 / total.coerceAtLeast(1))}ms) · " + memText(ctx))
                    progress["① 방송 대기 확인"] = "끝 · ${total}명 ${"%.1f".format(s)}초"
                    running--
                    main.post { checker?.destroy(); checker = null; done() }
                }
            }, "LRLab")
            w.webViewClient = object : WebViewClient() {
                var fired = false
                override fun onPageFinished(view: WebView, url: String) {
                    if (fired) return
                    fired = true
                    add(sec, "창 열림: $url (" + ((System.currentTimeMillis() - t0) / 1000.0) + "초)")
                    val js = "(async()=>{const ns=" + org.json.JSONArray(names).toString() + ";let i=0;for(const n of ns){const s=Date.now();" +
                        "try{const r=await fetch('/api/front/v2/models/username/'+encodeURIComponent(n)+'/cam',{credentials:'include',headers:{'Accept':'application/json'}});" +
                        "const t=await r.text();LRLab.res(n,r.status,Date.now()-s,t.slice(0,4000));}catch(e){LRLab.res(n,-1,Date.now()-s,String(e));}" +
                        "i++;await new Promise(z=>setTimeout(z,150));}LRLab.done(ns.length);})();"
                    view.evaluateJavascript(js, null)
                }
            }
            w.loadUrl("https://$host/robots.txt")
            main.postDelayed({
                if (checker === w && progress["① 방송 대기 확인"]?.startsWith("끝") != true && count == 0) {
                    add(sec, "60초 동안 응답 없음 → 중단 (창이 안 열렸거나 사이트가 막음)")
                    progress["① 방송 대기 확인"] = "끝 · 응답 없음(실패)"
                    running--
                    checker?.destroy(); checker = null; done()
                }
            }, 60_000)
        }
    }
}
