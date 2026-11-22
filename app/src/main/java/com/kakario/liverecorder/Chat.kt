package com.kakario.liverecorder

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 채팅 녹화: 녹화 파일 옆에 같은 이름으로
 * - .ass  : 동영상 앱(VLC·MX·팟플레이어)에서 화면 오른쪽 아래에 실제 방송 채팅창처럼 보이는 자막
 * - .chat.txt : [시:분:초] 닉네임: 내용  (검색용 기록)
 * 시간은 '녹화된 영상 기준'이라 일시정지 구간은 건너뛰어 영상과 맞춘다.
 */
class ChatWriter(videoPath: String, append: Boolean) {
    private val base = videoPath.substringBeforeLast('.')
    val assFile = File("$base.ass")
    val txtFile = File("$base.chat.txt")
    private val ass: OutputStreamWriter
    private val txt: OutputStreamWriter
    private val recent = ArrayDeque<Pair<String, String>>()  // (닉, 내용) 최근 10줄
    private var blockStart = -1L
    var count = 0; private set

    companion object {
        const val LINES = 10
        private val NICK_COLORS = listOf("FFD54F", "4FC3F7", "81C784", "FF8A65", "BA68C8", "F06292", "AED581", "4DD0E1", "FFB74D", "9575CD")

        /** 이어 녹화일 때: 기존 기록의 마지막 시간(밀리초)부터 계속 */
        fun lastTimeMs(videoPath: String): Long {
            val f = File(videoPath.substringBeforeLast('.') + ".chat.txt")
            if (!f.exists()) return 0
            val last = try { f.readLines().lastOrNull { it.startsWith("[") } } catch (e: Exception) { null } ?: return 0
            val m = Regex("""^\[(\d+):(\d\d):(\d\d)]""").find(last) ?: return 0
            val (h, mi, s) = m.destructured
            return (h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000
        }
    }

    init {
        val newAss = !append || !assFile.exists()
        ass = OutputStreamWriter(FileOutputStream(assFile, !newAss), Charsets.UTF_8)
        txt = OutputStreamWriter(FileOutputStream(txtFile, append && txtFile.exists()), Charsets.UTF_8)
        if (newAss) {
            ass.write("""
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 1280
                PlayResY: 720
                WrapStyle: 0
                ScaledBorderAndShadow: yes

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Chat,Malgun Gothic,21,&H00FFFFFF,&H00FFFFFF,&H70000000,&H70000000,0,0,0,0,100,100,0,0,3,7,0,3,880,18,18,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text

            """.trimIndent() + "\n")
            ass.flush()
        }
    }

    private fun t(ms: Long): String {
        val cs = (ms / 10) % 100
        val s = ms / 1000
        return "%d:%02d:%02d.%02d".format(s / 3600, (s / 60) % 60, s % 60, cs)
    }

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    private fun esc(s: String) = s.replace("\\", "＼").replace("{", "(").replace("}", ")").replace("\n", " ").trim()

    private fun color(nick: String) = NICK_COLORS[(nick.hashCode() and 0x7fffffff) % NICK_COLORS.size]
        .let { "${it.substring(4, 6)}${it.substring(2, 4)}${it.substring(0, 2)}" }  // ASS는 BBGGRR 순서

    /** 지금까지 쌓인 채팅창 모습을 [blockStart, until] 동안 보이게 기록 */
    private fun flushBlock(until: Long) {
        if (blockStart < 0 || recent.isEmpty() || until <= blockStart) return
        val text = recent.joinToString("\\N") { (n, m) ->
            if (n.isEmpty()) esc(m) else "{\\c&H${color(n)}&}${esc(n)}{\\c&HFFFFFF&}: ${esc(m)}"
        }
        ass.write("Dialogue: 0,${t(blockStart)},${t(until)},Chat,,0,0,0,,$text\n")
        ass.flush()
    }

    /** 새 채팅 한 줄 (atMs = 녹화된 영상 기준 시간) */
    @Synchronized
    fun add(raw: String, atMs: Long) {
        val (nick, msg) = parse(raw) ?: return
        flushBlock(atMs)
        recent.addLast(nick to msg.take(120))
        while (recent.size > LINES) recent.removeFirst()
        blockStart = atMs
        txt.write("[${hms(atMs)}] ${if (nick.isEmpty()) "" else "$nick: "}$msg\n")
        txt.flush()
        count++
    }

    /** 화면 글자에서 닉네임과 내용 나누기 — "닉\n내용" 또는 "닉: 내용" */
    private fun parse(raw: String): Pair<String, String>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val lines = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2 && lines[0].length <= 30) return lines[0].removeSuffix(":") to lines.drop(1).joinToString(" ")
        val i = s.indexOf(':')
        if (i in 1..30) return s.substring(0, i).trim() to s.substring(i + 1).trim()
        return "" to s.replace("\n", " ")
    }

    @Synchronized
    fun close(endMs: Long) {
        flushBlock(endMs.coerceAtLeast(blockStart + 5000))
        try { ass.close() } catch (_: Exception) {}
        try { txt.close() } catch (_: Exception) {}
    }
}
