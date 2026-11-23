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
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    // ---------- 보고서 ----------
    private val sections = java.util.LinkedHashMap<String, StringBuilder>()
    @Synchronized fun add(section: String, line: String) {
        sections.getOrPut(section) { StringBuilder() }.append("[").append(fmt.format(Date())).append("] ").append(line).append('\n')
    }
    @Synchronized fun clear() { sections.clear(); probes.clear() }

    @Synchronized fun report(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("=== 라이브 녹화 실험 결과 ===\n")
        sb.append("앱 ").append(BuildConfig.VERSION_NAME).append(" / ").append(android.os.Build.MODEL).append(" / 안드로이드 ").append(android.os.Build.VERSION.RELEASE).append('\n')
        sb.append("메모리: ").append(memText(ctx)).append('\n')
        probes.values.forEach { it.dump(sb) }
        sections.forEach { (k, v) -> sb.append("\n##### ").append(k).append(" #####\n").append(v) }
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
            playlists.forEachIndexed { i, (u, t) ->
                sb.append("--- 방송 목록 ${i + 1} 주소: ").append(u).append('\n')
                val ls = t.lines()
                sb.append(ls.take(140).joinToString("\n")).append('\n')
                if (ls.size > 140) sb.append("…(${ls.size}줄 중 140줄)\n")
            }
            sb.append("--- 재생기가 실제로 요청한 조각 주소 (처음 ${segs.size}개)\n")
            segs.forEach { sb.append(it).append('\n') }
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
        if (p.pairs.isNotEmpty() && p.segs.size in listOf(5, 15, 30)) analyze(p, p.playlists.lastOrNull()?.first ?: "", p.playlists.lastOrNull()?.second ?: "")
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

    /** 알려진 후보 열쇠로 풀어 보고, 안 되면 '재생기가 요청한 진짜 이름'과 맞춰 반복 열쇠를 찾아본다 */
    private fun analyze(p: Probe, url: String, body: String) {
        val sb = StringBuilder()
        val pairs = synchronized(p) { p.pairs.toList() }
        if (pairs.isEmpty()) return
        val keys = LinkedHashSet<String>()
        Regex("""[?&]pkey=([^&]+)""").find(url)?.groupValues?.get(1)?.let { keys.add(java.net.URLDecoder.decode(it, "UTF-8")) }
        body.lines().filter { it.startsWith("#EXT-X-MOUFLON:PSCH:") }.forEach { l -> l.split(':').drop(2).forEach { if (it.length >= 6) keys.add(it.trim()) } }
        sb.append("후보 열쇠: ").append(keys.joinToString(", ")).append('\n')
        val enc0 = pairs.first().first
        for (k in keys) for ((how, d) in b64All(enc0)) {
            val r = xor(d, sha256(k))
            if (printable(r)) sb.append("성공 후보 sha256($k) $how → ").append(String(r)).append('\n')
        }
        // 진짜 이름(재생기가 요청한 주소의 마지막 부분)과 맞춰 보기
        val names = synchronized(p) { p.segs.map { it.substringBefore('?').substringAfterLast('/') } }.distinct()
        var learned = 0
        for ((enc, _) in pairs.take(30)) for ((how, d) in b64All(enc)) for (n in names) {
            val nb = n.toByteArray()
            if (nb.size != d.size) continue
            val ks = xor(d, nb)
            // 같은 열쇠로 다른 암호도 풀리면 '반복 열쇠' 찾은 것
            val ok = pairs.count { (e2, _) -> b64All(e2).any { (h2, d2) -> h2 == how && printable(xor(d2, ks)) && names.contains(String(xor(d2, ks))) } }
            if (ok >= 2 && learned < 3) {
                learned++
                sb.append("반복 열쇠 찾음($how, ${ok}개 맞음): ").append(ks.joinToString("") { "%02x".format(it) }).append('\n')
                keys.forEach { k -> val h = sha256(k); if (h.copyOf(minOf(32, ks.size)).contentEquals(ks.copyOf(minOf(32, ks.size)))) sb.append("  → sha256($k)와 같음\n") }
            }
        }
        if (learned == 0) sb.append("진짜 이름과 맞춘 반복 열쇠: 아직 못 찾음 (조각 이름 ${names.size}개, 암호 ${pairs.size}개)\n")
        sb.append("암호 예시: ").append(pairs.take(5).joinToString(" / ") { "${it.first} → ${it.second}" }).append('\n')
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
        if (p == null || plUrl0 == null) { add(sec, "방송 목록 원본이 아직 없음 (녹화 중인 방송에서 1분쯤 받은 뒤 다시)"); done(); return }
        val headers = p.playlistHeaders.split(" | ").mapNotNull { val i = it.indexOf(": "); if (i > 0) it.substring(0, i) to it.substring(i + 2) else null }
            .filter { it.first.lowercase() in setOf("origin", "referer", "user-agent", "accept", "accept-language") }.toMap()
        Thread {
            val plUrl = plUrl0.replace(Regex("""[?&]_HLS_(msn|part|skip)=[^&]*"""), "").let { if (!it.contains('?') && it.contains('&')) it.replaceFirst('&', '?') else it }
            add(sec, "시작: ${seconds}초 · 목록 주소 $plUrl")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Saver.DIR).apply { mkdirs() }
            val out = File(dir, "직접받기 시험 ${p.name} " + SimpleDateFormat("HHmmss", Locale.KOREA).format(Date()) + ".mp4")
            val fos = out.outputStream().buffered(256 * 1024)
            val seen = HashSet<String>()
            val codes = HashMap<String, Int>()
            var segOk = 0; var bytes = 0L; var initDone = false; var polls = 0; var listFail = 0
            val cpu0 = android.os.Process.getElapsedCpuTime()
            val t0 = System.currentTimeMillis()
            val keyHex = Regex("""반복 열쇠 찾음\([^)]*\): ([0-9a-f]+)""").find(p.decodeNote)?.groupValues?.get(1)
            var decodeHow = Regex("""반복 열쇠 찾음\(([^,]*),""").find(p.decodeNote)?.groupValues?.get(1)
            val ks = keyHex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            try {
                while (System.currentTimeMillis() - t0 < seconds * 1000L) {
                    polls++
                    val r = try { get(plUrl, headers) } catch (e: Exception) { null }
                    if (r == null || r.code !in 200..299) { listFail++; if (listFail <= 3) add(sec, "목록 받기 실패: ${r?.code ?: "연결 오류"}"); Thread.sleep(1000); continue }
                    val text = String(r.body)
                    if (polls == 1) add(sec, "직접 받은 목록 첫 40줄:\n" + text.lines().take(40).joinToString("\n"))
                    val ls = text.lines()
                    var pendingName: String? = null
                    for (l0 in ls) {
                        val l = l0.trim()
                        if (l.startsWith("#EXT-X-MAP:")) {
                            if (initDone) continue
                            val u = Regex("""URI="([^"]+)"""").find(l)?.groupValues?.get(1) ?: continue
                            val ir = try { get(resolve(plUrl, u), headers) } catch (e: Exception) { null }
                            codes["시작정보 ${ir?.code ?: "오류"}"] = (codes["시작정보 ${ir?.code ?: "오류"}"] ?: 0) + 1
                            if (ir != null && ir.code in 200..299) { fos.write(ir.body); bytes += ir.body.size; initDone = true }
                            continue
                        }
                        if (l.startsWith("#EXT-X-MOUFLON:FILE:")) {
                            val enc = l.removePrefix("#EXT-X-MOUFLON:FILE:").trim()
                            pendingName = if (ks != null) b64All(enc).firstOrNull { decodeHow == null || it.first == decodeHow }?.second?.let { String(xor(it, ks)) } else null
                            continue
                        }
                        if (l.isEmpty() || l.startsWith("#")) continue
                        // 조각 주소 줄 (암호가 풀렸으면 마지막 이름을 바꿔 끼움)
                        var u = l
                        if (pendingName != null) { u = u.substringBeforeLast('/', "").let { if (it.isEmpty()) pendingName!! else "$it/$pendingName" } + (if (l.contains('?')) "?" + l.substringAfter('?') else "") }
                        pendingName = null
                        val full = resolve(plUrl, u)
                        val key = full.substringBefore('?')
                        if (!seen.add(key)) continue
                        if (!initDone && seen.size > 1) { /* 시작정보 없이 받는 형식 */ }
                        val sr = try { get(full, headers) } catch (e: Exception) { null }
                        val c = "조각 ${sr?.code ?: "오류"}"
                        codes[c] = (codes[c] ?: 0) + 1
                        if (sr != null && sr.code in 200..299) {
                            if (segOk == 0) add(sec, "첫 조각: ${full.take(200)} · ${sr.body.size}바이트 · 앞부분 상자: " + boxes(sr.body))
                            fos.write(sr.body); bytes += sr.body.size; segOk++
                        } else if ((codes[c] ?: 0) <= 2) add(sec, "조각 실패 ${sr?.code}: ${full.take(200)}")
                    }
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                add(sec, "중단: ${e.message}")
            } finally {
                try { fos.close() } catch (_: Exception) {}
            }
            val cpuMs = android.os.Process.getElapsedCpuTime() - cpu0
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            add(sec, "결과: 조각 ${segOk}개 · ${bytes / 1024}KB · 목록 ${polls}번 읽음(실패 $listFail) · 응답 $codes")
            add(sec, "자원: 앱 전체 CPU ${cpuMs}ms / ${"%.0f".format(secs)}초 (다른 녹화 포함) · " + memText(ctx))
            add(sec, "저장: ${out.name} (" + (if (segOk > 0) "동영상 앱으로 열어 재생되는지 확인해 주세요" else "받은 게 없음") + ")")
            Saver.scan(ctx, out.absolutePath)
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

    // ---------- 1) 방송 대기 확인: 창 하나로 여러 명 ----------
    private var checker: WebView? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun checkerTest(ctx: Context, host: String, names: List<String>, ua: String, done: () -> Unit) {
        val sec = "방송 대기 확인 시험 (${names.size}명)"
        if (names.isEmpty()) { add(sec, "확인할 스트리머가 없음 (녹화 등록이나 즐겨찾기를 먼저)"); done(); return }
        add(sec, "시작: 창 하나(https://$host/robots.txt)에서 차례로 물어봄")
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
                    val st = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(t)?.groupValues?.get(1)
                    val live = Regex("\"isLive\"\\s*:\\s*(true|false)").find(t)?.groupValues?.get(1)
                    add(sec, "$n → 응답 $code · ${ms}ms · status=${st ?: "-"} · isLive=${live ?: "-"}" + (if (count <= 3) "\n   원본: " + t.take(600).replace('\n', ' ') else ""))
                }
                @JavascriptInterface fun done(total: Int) {
                    val s = (System.currentTimeMillis() - t0) / 1000.0
                    add(sec, "끝: ${total}명 확인에 ${"%.1f".format(s)}초 (한 명당 ${"%.0f".format(s * 1000 / total.coerceAtLeast(1))}ms) · " + memText(ctx))
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
        }
    }
}
