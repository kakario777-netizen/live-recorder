package com.kakario.liverecorder

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 진단 기록 — 영상 내용은 담지 않고, 영상 주소를 찾았는지·어디서 막혔는지 같은
 * 기술 정보만 글로 정리한다. 주소의 토큰 값은 가린다.
 */
object Diag {
    fun build(version: String, pageUrl: String, ua: String, videoInfo: String): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        sb.appendLine("=== 라이브 녹화 진단 ===")
        sb.appendLine("시각: $now / 앱 $version / ${Build.MODEL} / 안드로이드 ${Build.VERSION.RELEASE}")
        sb.appendLine("페이지: ${maskUrl(pageUrl)}")
        sb.appendLine("브라우저 정보: $ua")
        sb.appendLine()
        sb.appendLine("[화면 속 동영상 요소]")
        sb.appendLine(videoInfo.ifBlank { "(없음)" })
        sb.appendLine()

        val items = Sniff.list()
        sb.appendLine("[잡힌 영상 주소] ${items.size}개")
        if (items.isEmpty()) {
            sb.appendLine("- 없음: 방송을 재생한 뒤 진단하거나, 이 사이트는 다른 방식(웹소켓 등)으로 영상을 보낼 수 있음")
        }
        items.forEachIndexed { i, c ->
            sb.appendLine("${i + 1}. [${c.kind}] ${maskUrl(c.url)}")
            sb.appendLine("   보낸 머리말: ${c.headers.keys.joinToString(", ").ifBlank { "(없음)" }}")
        }
        val segs = Sniff.segments()
        if (segs.isNotEmpty()) {
            sb.appendLine("[영상 조각 예시]")
            segs.forEach { sb.appendLine("- ${maskUrl(it.url)}") }
        }
        sb.appendLine()

        // 재생목록을 앱이 직접 받아 볼 수 있는지 확인
        items.filter { it.kind == "hls" }.take(4).forEachIndexed { i, c ->
            sb.appendLine("[재생목록 확인 ${i + 1}] ${maskUrl(c.url)}")
            try {
                val r = Net.get(c.url, c, ua, maxBytes = 512 * 1024)
                sb.appendLine("응답: HTTP ${r.code} / ${r.type}")
                if (r.code in 200..299) {
                    val text = String(r.body)
                    val p = Hls.parse(text, r.finalUrl)
                    if (p.isMaster) {
                        sb.appendLine("종류: 화질 목록 (${p.variants.size}개)")
                        p.variants.forEach { v -> sb.appendLine("  - ${v.resolution ?: "?"} ${v.bandwidth / 1000}kbps") }
                        p.variants.maxByOrNull { it.bandwidth }?.let { v ->
                            val r2 = Net.get(v.url, c, ua, maxBytes = 512 * 1024)
                            sb.appendLine("최고 화질 목록: HTTP ${r2.code}")
                            if (r2.code in 200..299) describeMedia(sb, Hls.parse(String(r2.body), r2.finalUrl), c, ua)
                        }
                    } else {
                        describeMedia(sb, p, c, ua)
                    }
                    sb.appendLine("앞부분:")
                    text.lines().take(14).forEach { l ->
                        sb.appendLine("  " + if (l.startsWith("#")) l.take(160) else maskUrl(l).take(160))
                    }
                } else {
                    sb.appendLine("앞부분: " + String(r.body).take(200).replace("\n", " "))
                }
            } catch (e: Exception) {
                sb.appendLine("오류: ${e.javaClass.simpleName} ${e.message}")
            }
            sb.appendLine()
        }

        codecTest(sb, items, ua)

        sb.appendLine("[녹화 기록]")
        sb.appendLine(RecordService.logText().ifBlank { "(아직 없음)" })
        return sb.toString()
    }

    private fun setParam(url: String, key: String, value: String?): String {
        val base = url.substringBefore('?')
        val q = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() && it.substringBefore('=') != key && !it.startsWith("_HLS_") }.toMutableList()
        if (value != null) q.add("$key=$value")
        return if (q.isEmpty()) base else base + "?" + q.joinToString("&")
    }

    /**
     * 코덱 시험: 대표 재생목록(master)을 코덱 칸만 바꿔 가며 요청해서,
     * 서버가 H.265(HEVC)·AV1·H.264 중 무엇을 실제로 주는지 확인한다.
     */
    private fun codecTest(sb: StringBuilder, items: List<Captured>, ua: String) {
        val hls = items.filter { it.kind == "hls" }
        val master = hls.firstOrNull { it.url.contains("master", true) }
        // 화질별 목록: 접근 키(pkey)가 붙은 것 중 하나
        val media = hls.firstOrNull { !it.url.contains("master", true) && it.url.contains("pkey") }
        sb.appendLine("[코덱 시험]")
        if (master == null && media == null) { sb.appendLine("- 재생목록을 못 찾았어요 (방송을 틀어 놓고 다시 진단해 주세요)"); sb.appendLine(); return }
        val tries = listOf("hevc", "h265", "h264", "av1", null)
        if (master != null) {
            sb.appendLine("대표 목록: ${maskUrl(master.url).take(120)}")
            for (v in tries) {
                val label = v ?: "(코덱 칸 없음)"
                try {
                    val r = Net.get(setParam(master.url, "preferredVideoCodec", v), master, ua, maxBytes = 512 * 1024)
                    if (r.code !in 200..299) { sb.appendLine("- $label → HTTP ${r.code}"); continue }
                    val p = Hls.parse(String(r.body), r.finalUrl)
                    val kinds = p.variants.mapNotNull { it.codecs?.substringBefore(',') }
                        .map { c -> Codec.label(Codec.fromCodecsAttr(c)).let { if (it == "알 수 없음") c else it } }.distinct()
                    val best = p.variants.maxByOrNull { it.bandwidth }
                    sb.appendLine("- $label → ${kinds.joinToString("/").ifEmpty { "코덱 표시 없음" }} · 화질 ${p.variants.size}개" +
                        (best?.let { " · 최고 ${it.resolution ?: "?"} ${it.bandwidth / 1000}kbps" } ?: ""))
                } catch (e: Exception) { sb.appendLine("- $label → 오류 ${e.message}") }
            }
        }
        if (media != null) {
            sb.appendLine("화질별 목록: ${maskUrl(media.url).take(120)}")
            for (v in tries) {
                val label = v ?: "(코덱 칸 없음)"
                try {
                    val r = Net.get(setParam(media.url, "preferredVideoCodec", v), media, ua, maxBytes = 512 * 1024)
                    if (r.code !in 200..299) { sb.appendLine("- $label → HTTP ${r.code}"); continue }
                    val body = String(r.body)
                    // 실제 조각 이름에 들어 있는 코덱 표시
                    val tag = Regex("""_(h264|h265|hevc|av1|avc|vp9)_""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
                        ?: if (body.contains("#EXT-X-MAP")) "이름에 코덱 표시 없음(원본 추정)" else "?"
                    sb.appendLine("- $label → 받은 조각: $tag")
                } catch (e: Exception) { sb.appendLine("- $label → 오류 ${e.message}") }
            }
        }
        sb.appendLine("※ hevc·h265 줄에 H.265(또는 hevc)가 나오면 처음부터 H.265로 녹화할 수 있어요")
        sb.appendLine()
    }

    private fun describeMedia(sb: StringBuilder, p: Playlist, c: Captured, ua: String) {
        sb.appendLine("종류: 영상 목록 / 조각 ${p.segments.size}개 / 조각 길이 ${p.target}초 / " +
            (if (p.endList) "끝난 영상(다시보기)" else "생방송"))
        if (p.keys.isEmpty()) sb.appendLine("암호화: 없음")
        else p.keys.distinctBy { it.method + it.format }.forEach {
            sb.appendLine("암호화: ${it.method}" + (it.format?.let { f -> " / $f" } ?: ""))
        }
        sb.appendLine("DRM: " + if (Hls.isDrm(p)) "있음 → 녹화 불가" else "없음")
        sb.appendLine("형식: " + if (p.hasMap) "fMP4(.mp4)" else "TS(.ts)")
        if (p.parts.isNotEmpty()) sb.appendLine("저지연 방송: 부분 조각 ${p.parts.size}개")
        val s = p.parts.lastOrNull { it.independent || it.part == 0 } ?: p.segments.lastOrNull() ?: return
        sb.appendLine("시험할 조각: ${maskUrl(s.url).take(160)}")
        try {
            val r = Net.get(s.url, c, ua, maxBytes = 8 * 1024 * 1024)
            val head = r.body.take(8).joinToString(" ") { "%02x".format(it) }
            sb.appendLine("조각 받기 시험: HTTP ${r.code}, ${r.body.size / 1024}KB, 시작 바이트 $head")
        } catch (e: Exception) {
            sb.appendLine("조각 받기 시험 실패: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
