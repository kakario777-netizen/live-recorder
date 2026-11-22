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

        sb.appendLine("[녹화 기록]")
        sb.appendLine(RecordService.logText().ifBlank { "(아직 없음)" })
        return sb.toString()
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
        val s = p.segments.lastOrNull() ?: return
        try {
            val r = Net.get(s.url, c, ua, maxBytes = 8 * 1024 * 1024)
            val head = r.body.take(8).joinToString(" ") { "%02x".format(it) }
            sb.appendLine("조각 받기 시험: HTTP ${r.code}, ${r.body.size / 1024}KB, 시작 바이트 $head")
        } catch (e: Exception) {
            sb.appendLine("조각 받기 시험 실패: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
