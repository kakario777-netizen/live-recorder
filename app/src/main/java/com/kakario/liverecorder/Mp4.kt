package com.kakario.liverecorder

import java.io.File
import java.io.RandomAccessFile

/**
 * 조각형 MP4 손보기.
 * 이어 녹화나 화질 변경 때 파일 중간에 '시작 정보'(ftyp·moov)가 한 번 더 들어가면
 * 대부분의 동영상 앱이 거기서 재생을 멈춘다. 같은 시작 정보가 중복된 경우 빼 준다.
 */
object Mp4 {
    private class Box(val type: String, val start: Long, val size: Long)

    private fun boxes(raf: RandomAccessFile): Sequence<Box> = sequence {
        var pos = 0L
        val len = raf.length()
        val hdr = ByteArray(16)
        while (pos + 8 <= len) {
            raf.seek(pos)
            raf.readFully(hdr, 0, 8)
            var size = ((hdr[0].toLong() and 0xff) shl 24) or ((hdr[1].toLong() and 0xff) shl 16) or
                ((hdr[2].toLong() and 0xff) shl 8) or (hdr[3].toLong() and 0xff)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                if (pos + 16 > len) break
                raf.readFully(hdr, 8, 8)
                size = 0
                for (i in 8 until 16) size = (size shl 8) or (hdr[i].toLong() and 0xff)
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < 8 || pos + size > len) {
                // 녹화 중이라 마지막 상자가 덜 써진 경우: 나머지를 하나로 취급
                yield(Box(type, pos, len - pos)); break
            }
            yield(Box(type, pos, size))
            pos += size
        }
    }

    private fun read(raf: RandomAccessFile, b: Box): ByteArray {
        val out = ByteArray(b.size.toInt())
        raf.seek(b.start); raf.readFully(out)
        return out
    }

    /**
     * 시작 정보에서 '영상·소리 형식(stsd)' 부분만 뽑아 이어 붙인 것.
     * 만든 시각 같은 기록 값은 빼고 실제 형식만 비교하려고 쓴다.
     */
    private fun formatKey(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 4
        while (i + 4 <= b.size) {
            if (b[i] == 's'.code.toByte() && b[i + 1] == 't'.code.toByte() && b[i + 2] == 's'.code.toByte() && b[i + 3] == 'd'.code.toByte()) {
                val start = i - 4
                val size = ((b[start].toInt() and 0xff) shl 24) or ((b[start + 1].toInt() and 0xff) shl 16) or
                    ((b[start + 2].toInt() and 0xff) shl 8) or (b[start + 3].toInt() and 0xff)
                if (size in 8..(b.size - start)) { out.write(b, start, size); i = start + size; continue }
            }
            i++
        }
        return out.toByteArray()
    }

    /** 두 시작 정보가 같은 형식(코덱 설정)인지 */
    fun sameFormat(a: ByteArray, b: ByteArray): Boolean {
        if (a.contentEquals(b)) return true
        val ka = formatKey(a); val kb = formatKey(b)
        return ka.isNotEmpty() && ka.contentEquals(kb)
    }

    /** 파일 맨 앞의 시작 정보(첫 moof 전까지) */
    fun initOf(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val bs = boxes(raf).takeWhile { it.type != "moof" && it.type != "mdat" }.toList()
            if (bs.none { it.type == "moov" }) null
            else {
                val end = bs.last().start + bs.last().size
                ByteArray(end.toInt()).also { raf.seek(0); raf.readFully(it) }
            }
        }
    } catch (e: Exception) { null }

    /** 이어 쓸 때: 새 시작 정보가 파일 맨 앞 것과 똑같으면 다시 쓸 필요 없음 */
    fun sameInit(file: File, init: ByteArray): Boolean {
        val cur = initOf(file) ?: return false
        return sameFormat(cur, init)
    }

    /**
     * 중간에 중복으로 들어간 같은 시작 정보(ftyp·moov)를 빼고 dst에 쓴다.
     * 반환: 뺀 개수 (0이면 손볼 것 없음, -1이면 MP4가 아님)
     */
    fun removeDuplicateInits(src: File, dst: File): Int {
        RandomAccessFile(src, "r").use { raf ->
            var firstFtyp: ByteArray? = null
            var firstMoov: ByteArray? = null
            var removed = 0
            dst.outputStream().buffered(512 * 1024).use { out ->
                val buf = ByteArray(512 * 1024)
                for (b in boxes(raf)) {
                    if (b.type == "ftyp" || b.type == "moov") {
                        val bytes = read(raf, b)
                        val first = if (b.type == "ftyp") firstFtyp else firstMoov
                        if (first == null) {
                            if (b.type == "ftyp") firstFtyp = bytes else firstMoov = bytes
                            out.write(bytes)
                        } else if (b.type == "ftyp" || sameFormat(first, bytes)) {
                            removed++
                        } else {
                            out.write(bytes)  // 화질이 바뀌어 내용이 다른 시작 정보는 그대로 둠
                        }
                        continue
                    }
                    raf.seek(b.start)
                    var left = b.size
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n); left -= n
                    }
                }
            }
            if (firstMoov == null) return -1
            return removed
        }
    }
}
