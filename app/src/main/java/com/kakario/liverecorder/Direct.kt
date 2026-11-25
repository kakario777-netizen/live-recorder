package com.kakario.liverecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 그래픽 칩(GPU)을 거치지 않는 H.265 변환.
 * 해독기(H.264) → 인코더(H.265) 입력 화면으로 바로 넘김. 소리는 다시 압축하지 않고 그대로 복사.
 * 화면이 꺼져 그래픽 칩이 잠들어도 멈추지 않게 하려는 것 (안드로이드 16 폴드5 문제).
 * 중간에 화질(해상도)이 바뀌는 파일 등 이 방식으로 못 하는 경우엔 오류를 돌려주고, 부른 쪽이 기존 방식으로 다시 함.
 */
object Direct {
    @Volatile var encoderName = ""; private set

    /** 성공하면 null, 실패하면 이유 */
    fun run(src: File, out: File, bitrate: Int, progress: (Int) -> Unit, cancelled: () -> Boolean): String? {
        val vEx = MediaExtractor(); val aEx = MediaExtractor()
        var dec: MediaCodec? = null; var enc: MediaCodec? = null; var mux: MediaMuxer? = null
        var muxStarted = false
        try {
            vEx.setDataSource(src.absolutePath); aEx.setDataSource(src.absolutePath)
            var vt = -1; var at = -1
            for (i in 0 until vEx.trackCount) {
                val m = vEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vt < 0 && m.startsWith("video/")) vt = i
                if (at < 0 && m.startsWith("audio/")) at = i
            }
            if (vt < 0) return "영상 트랙 없음"
            vEx.selectTrack(vt)
            val inF = vEx.getTrackFormat(vt)
            val w = inF.getInteger(MediaFormat.KEY_WIDTH); val h = inF.getInteger(MediaFormat.KEY_HEIGHT)
            val durUs = if (inF.containsKey(MediaFormat.KEY_DURATION)) inF.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = try { if (inF.containsKey(MediaFormat.KEY_FRAME_RATE)) inF.getInteger(MediaFormat.KEY_FRAME_RATE) else 30 } catch (_: Exception) { 30 }
            val rot = if (inF.containsKey("rotation-degrees")) inF.getInteger("rotation-degrees") else 0
            val aF = if (at >= 0) { aEx.selectTrack(at); aEx.getTrackFormat(at) } else null

            val outF = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 60))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            encoderName = enc.name
            enc.configure(outF, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            dec = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(inF, surface, null, 0)
            dec.start()

            val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = mx
            if (rot != 0) mx.setOrientationHint(rot)
            var muxV = -1; var muxA = -1
            val aBuf = ByteBuffer.allocate(1024 * 1024); val aInfo = MediaCodec.BufferInfo()
            var aDone = aF == null
            var aPending = false   // aInfo에 읽어 둔 소리 조각이 아직 안 쓰였는지

            /** 영상 시간 upToUs까지 소리를 끼워 넣음 (영상·소리 고르게 섞이게) */
            fun writeAudio(upToUs: Long) {
                if (aDone || muxA < 0) return
                while (true) {
                    if (!aPending) {
                        aBuf.clear()
                        val n = aEx.readSampleData(aBuf, 0)
                        if (n < 0) { aDone = true; return }
                        aInfo.set(0, n, aEx.sampleTime, if (aEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        aEx.advance()
                        aPending = true
                    }
                    if (aInfo.presentationTimeUs > upToUs) return
                    mx.writeSampleData(muxA, aBuf, aInfo)
                    aPending = false
                }
            }

            val info = MediaCodec.BufferInfo(); val eInfo = MediaCodec.BufferInfo()
            var inDone = false; var decDone = false; var encDone = false
            var decFrames = 0L; var encFrames = 0L
            var lastP = -1
            while (!encDone) {
                if (cancelled()) return "취소됨"
                // ① 해독기에 넣기
                if (!inDone) {
                    val ii = dec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val b = dec.getInputBuffer(ii)!!
                        val n = vEx.readSampleData(b, 0)
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(ii, 0, n, vEx.sampleTime, 0); vEx.advance() }
                    }
                }
                // ② 해독된 장면을 인코더 화면으로 바로 넘김
                if (!decDone) {
                    val oi = dec.dequeueOutputBuffer(info, 5_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        val nw = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        val nh = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (decFrames > 0 && (nw != w || nh != h)) return "중간에 화질이 바뀜(${w}x$h→${nw}x$nh)"
                    } else if (oi >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = info.size > 0
                        dec.releaseOutputBuffer(oi, render)
                        if (render) decFrames++
                        if (eos) { enc.signalEndOfInputStream(); decDone = true }
                    }
                }
                // ③ 인코더에서 나온 걸 파일에 씀
                while (true) {
                    val eo = enc.dequeueOutputBuffer(eInfo, if (decDone) 10_000 else 0)
                    if (eo == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxV = mx.addTrack(enc.outputFormat)
                        if (aF != null) muxA = mx.addTrack(aF)
                        mx.start(); muxStarted = true
                        continue
                    }
                    if (eo < 0) continue
                    val eb = enc.getOutputBuffer(eo)!!
                    if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                    if (eInfo.size > 0 && muxStarted) {
                        writeAudio(eInfo.presentationTimeUs)
                        mx.writeSampleData(muxV, eb, eInfo)
                        encFrames++
                        if (durUs > 0) {
                            val p = (eInfo.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 99)
                            if (p != lastP) { lastP = p; progress(p) }
                        }
                    }
                    val end = eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(eo, false)
                    if (end) { encDone = true; break }
                }
            }
            writeAudio(Long.MAX_VALUE)
            // 장면이 빠졌으면(인코더가 못 받아 버림) 실패로 보고 기존 방식으로
            if (decFrames > 0 && encFrames < decFrames * 97 / 100) return "장면 빠짐(${encFrames}/$decFrames)"
            mx.stop(); muxStarted = false
            mx.release(); mux = null
            progress(100)
            return verify(src, out)
        } catch (e: Exception) {
            return "직접 변환 오류: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { dec?.stop() } catch (_: Exception) {}; try { dec?.release() } catch (_: Exception) {}
            try { enc?.stop() } catch (_: Exception) {}; try { enc?.release() } catch (_: Exception) {}
            try { if (muxStarted) mux?.stop() } catch (_: Exception) {}; try { mux?.release() } catch (_: Exception) {}
            vEx.release(); aEx.release()
        }
    }

    /** 끝난 뒤 확인: 크기·길이를 원본과 비교 (1초 안쪽) */
    fun verify(src: File, out: File): String? {
        if (!out.exists() || out.length() < 1024) return "결과 파일이 비어 있음"
        fun dur(f: File): Long = try {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(f.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1 } finally { r.release() }
        } catch (_: Exception) { -1 }
        val a = dur(src); val b = dur(out)
        if (b <= 0) return "결과 파일 길이를 읽을 수 없음"
        if (a > 0 && kotlin.math.abs(a - b) > maxOf(3000L, a / 100)) return "길이가 다름(원본 ${a / 1000}초 / 결과 ${b / 1000}초)"
        return null
    }
}
