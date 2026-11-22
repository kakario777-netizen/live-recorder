package com.kakario.liverecorder

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** FTP 서버 설정 — 비밀번호는 폰 보안 저장소(키스토어) 키로 암호화해서 보관 */
object FtpConfig {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ftp", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun host(ctx: Context) = prefs(ctx).getString("host", "") ?: ""
    fun port(ctx: Context) = prefs(ctx).getInt("port", 21)
    fun user(ctx: Context) = prefs(ctx).getString("user", "") ?: ""
    fun folder(ctx: Context) = prefs(ctx).getString("folder", "/녹화") ?: "/녹화"
    fun password(ctx: Context): String = try {
        prefs(ctx).getString("pw", null)?.let { decrypt(it) } ?: ""
    } catch (e: Exception) { "" }

    fun save(ctx: Context, enabled: Boolean, host: String, port: Int, user: String, pw: String, folder: String) {
        prefs(ctx).edit()
            .putBoolean("enabled", enabled)
            .putString("host", host.trim().removePrefix("ftp://").trimEnd('/'))
            .putInt("port", port)
            .putString("user", user.trim())
            .putString("pw", encrypt(pw))
            .putString("folder", "/" + folder.trim().trim('/'))
            .apply()
    }

    fun ready(ctx: Context) = enabled(ctx) && host(ctx).isNotEmpty() && user(ctx).isNotEmpty()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("ftp_pw", null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder("ftp_pw", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return g.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(s.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

/**
 * 자동 업로드: 정리가 끝난 녹화 파일을 FTP(시놀로지 NAS)에 올리고,
 * 서버 파일 크기가 원본과 바이트 단위까지 같은지 확인한 뒤 원본을 휴지통(7일 보관)으로 옮긴다.
 * - 임시 이름(.part)으로 올리고, 확인 후 진짜 이름으로 바꾼다 (반쯤 올라간 파일이 진짜처럼 보이지 않게)
 * - 끊기면 끊긴 지점부터 이어 올리고, 실패하면 1·5·15·30분 간격으로 계속 다시 시도
 * - 대기 목록은 저장해 둬서 앱이 꺼졌다 켜져도 이어서 처리
 */
object Uploader {
    class Job(val name: String, val path: String, val streamer: String, var tries: Int = 0, var nextAt: Long = 0, var lastError: String = "")

    private val queue = ArrayList<Job>()
    @Volatile var current: Job? = null; private set
    @Volatile var percent = 0; private set
    @Volatile var lastResult = ""; private set
    var onChange: (() -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    private var worker: Thread? = null
    private var loaded = false

    const val TRASH_DAYS = 7

    fun recDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LiveRecorder")
    fun trashDir(): File = File(recDir(), "휴지통").apply {
        mkdirs()
        File(this, ".nomedia").let { if (!it.exists()) try { it.createNewFile() } catch (_: Exception) {} }
    }

    fun busy(): Boolean = current != null || synchronized(queue) { queue.isNotEmpty() }

    fun statusOf(name: String): String? {
        if (current?.name == name) return "업로드 중… $percent%"
        val j = synchronized(queue) { queue.firstOrNull { it.name == name } } ?: return null
        if (j.tries == 0) return "업로드 대기"
        val left = ((j.nextAt - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
        return "업로드 실패 · ${left}분 뒤 다시"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ftp", Context.MODE_PRIVATE)

    private fun persist(ctx: Context) {
        val a = JSONArray()
        synchronized(queue) {
            (listOfNotNull(current) + queue).distinctBy { it.name }.forEach {
                a.put(JSONObject().put("name", it.name).put("path", it.path).put("streamer", it.streamer))
            }
        }
        prefs(ctx).edit().putString("queue", a.toString()).apply()
    }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(prefs(ctx).getString("queue", "[]"))
            synchronized(queue) {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val j = Job(o.getString("name"), o.getString("path"), o.optString("streamer"))
                    if (File(j.path).exists() && queue.none { it.name == j.name }) queue.add(j)
                }
            }
        } catch (_: Exception) {}
    }

    fun pendingCount(ctx: Context): Int { load(ctx); return synchronized(queue) { queue.size } + (if (current != null) 1 else 0) }

    fun enqueue(ctx: Context, name: String, path: String?, streamer: String) {
        if (path == null || !FtpConfig.ready(ctx)) return
        load(ctx)
        synchronized(queue) {
            if (current?.name == name || queue.any { it.name == name }) return
            queue.add(Job(name, path, streamer))
        }
        persist(ctx)
        RecordService.addLog("업로드 대기: $name")
        onChange?.invoke()
        kick(ctx)
    }

    /** 앱·서비스가 켜질 때 남은 업로드 이어서 처리 */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        load(app)
        if (!FtpConfig.ready(app)) return
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = Thread({ loop(app) }, "ftp-upload").also { it.start() }
        }
    }

    private fun loop(ctx: Context) {
        while (true) {
            val now = System.currentTimeMillis()
            val job = synchronized(queue) {
                queue.filter { it.nextAt <= now }.minByOrNull { it.nextAt }?.also { queue.remove(it) }
            }
            if (job == null) {
                val waiting = synchronized(queue) { queue.isNotEmpty() }
                if (!waiting) { onIdle?.invoke(); return }
                Thread.sleep(5000); continue
            }
            current = job; percent = 0; onChange?.invoke()
            val ok = try { upload(ctx, job); true } catch (e: Exception) {
                job.lastError = e.message ?: e.javaClass.simpleName; false
            }
            current = null
            if (ok) {
                lastResult = "업로드 완료: ${job.name}"
                RecordService.addLog("$lastResult (원본은 휴지통으로, ${TRASH_DAYS}일 뒤 삭제)")
            } else {
                job.tries++
                val waitMin = when (job.tries) { 1 -> 1; 2 -> 5; 3 -> 15; else -> 30 }
                job.nextAt = System.currentTimeMillis() + waitMin * 60_000L
                lastResult = "업로드 실패(${job.tries}번째): ${job.name} — ${job.lastError}"
                RecordService.addLog("$lastResult → ${waitMin}분 뒤 다시")
                if (File(job.path).exists()) synchronized(queue) { queue.add(job) }
            }
            persist(ctx)
            onChange?.invoke()
        }
    }

    fun connect(ctx: Context): FTPClient {
        val f = FTPClient()
        f.controlEncoding = "UTF-8"
        f.connectTimeout = 15000
        f.setDataTimeout(java.time.Duration.ofSeconds(60))
        f.connect(FtpConfig.host(ctx), FtpConfig.port(ctx))
        if (!FTPReply.isPositiveCompletion(f.replyCode)) { f.disconnect(); throw Exception("서버 연결 거부 (${f.replyCode})") }
        f.soTimeout = 60000
        if (!f.login(FtpConfig.user(ctx), FtpConfig.password(ctx))) { f.disconnect(); throw Exception("아이디나 비밀번호가 맞지 않아요") }
        try { f.sendCommand("OPTS UTF8 ON") } catch (_: Exception) {}
        f.enterLocalPassiveMode()
        f.setFileType(FTP.BINARY_FILE_TYPE)
        f.bufferSize = 256 * 1024
        return f
    }

    /** 설정 화면 '연결 시험' */
    fun test(ctx: Context): String = try {
        val f = connect(ctx)
        val dir = FtpConfig.folder(ctx)
        mkdirs(f, dir)
        val ok = f.changeWorkingDirectory(dir)
        f.logout(); f.disconnect()
        if (ok) "연결 성공 — '$dir' 폴더에 올릴 수 있어요" else "연결은 됐지만 '$dir' 폴더에 들어갈 수 없어요"
    } catch (e: Exception) {
        "연결 실패: ${e.message ?: e.javaClass.simpleName}"
    }

    fun mkdirs(f: FTPClient, dir: String) {
        var cur = ""
        dir.trim('/').split('/').filter { it.isNotEmpty() }.forEach { d ->
            cur += "/$d"
            if (!f.changeWorkingDirectory(cur)) f.makeDirectory(cur)
        }
    }

    private fun remoteSize(f: FTPClient, path: String): Long {
        return try {
            val code = f.sendCommand("SIZE", path)
            if (code == 213) f.replyString.trim().substringAfter(' ').trim().toLong() else -1
        } catch (e: Exception) { -1 }
    }

    private fun upload(ctx: Context, job: Job) {
        val src = File(job.path)
        if (!src.exists()) throw Exception("원본 파일이 없어요")
        val total = src.length()
        val folderName = Names.clean(job.streamer).ifEmpty { "기타" }
        val dir = FtpConfig.folder(ctx).trimEnd('/') + "/" + folderName
        val finalPath = "$dir/${job.name}"
        val partPath = "$finalPath.part"
        val f = connect(ctx)
        try {
            mkdirs(f, dir)
            // 이미 다 올라가 있으면(예: 확인 직전 앱이 꺼짐) 바로 확인 단계로
            if (remoteSize(f, finalPath) != total) {
                var offset = remoteSize(f, partPath).coerceAtLeast(0)
                if (offset > total) { f.deleteFile(partPath); offset = 0 }
                if (offset > 0) RecordService.addLog("이어 올리기: ${job.name} (${offset / 1024 / 1024}MB부터)")
                src.inputStream().use { inp ->
                    if (offset > 0) { var skipped = 0L; while (skipped < offset) skipped += inp.skip(offset - skipped) }
                    val os = (if (offset > 0) f.appendFileStream(partPath) else f.storeFileStream(partPath))
                        ?: throw Exception("서버가 업로드를 받지 않아요 (${f.replyString.trim()})")
                    var done = offset
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    os.use { o ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 1000) { last = now; percent = (done * 100 / total).toInt(); onChange?.invoke() }
                        }
                    }
                    if (!f.completePendingCommand()) throw Exception("업로드 마무리 실패 (${f.replyString.trim()})")
                }
                // 확인 1: 임시 파일 크기
                val got = remoteSize(f, partPath)
                if (got != total) throw Exception("크기가 달라요 (서버 $got / 원본 $total)")
                f.deleteFile(finalPath)
                if (!f.rename(partPath, finalPath)) throw Exception("서버에서 이름 바꾸기 실패")
            }
            // 확인 2: 진짜 이름으로 바뀐 파일 크기 (바이트 단위까지 같아야 통과)
            val fin = remoteSize(f, finalPath)
            if (fin != total) throw Exception("최종 확인 실패 (서버 $fin / 원본 $total)")
            percent = 100
            // 채팅 파일(.ass 자막, .chat.txt 기록)도 같은 폴더에 올리고 크기 확인
            companions(src).forEach { cf ->
                val rp = "$dir/${cf.name}"
                if (remoteSize(f, rp) != cf.length()) {
                    cf.inputStream().use { if (!f.storeFile(rp, it)) throw Exception("채팅 파일 올리기 실패: ${cf.name}") }
                    if (remoteSize(f, rp) != cf.length()) throw Exception("채팅 파일 크기 확인 실패: ${cf.name}")
                }
            }
        } finally {
            try { f.logout() } catch (_: Exception) {}
            try { f.disconnect() } catch (_: Exception) {}
        }
        // 확인 통과 → 원본(영상·채팅)은 휴지통으로 (7일 보관)
        companions(src).forEach { cf ->
            val d = File(trashDir(), cf.name); if (d.exists()) d.delete()
            if (cf.renameTo(d)) d.setLastModified(System.currentTimeMillis())
        }
        val dst = File(trashDir(), src.name)
        if (dst.exists()) dst.delete()
        if (src.renameTo(dst)) {
            dst.setLastModified(System.currentTimeMillis())
            Saver.scan(ctx, src.absolutePath)
        } else {
            RecordService.addLog("휴지통으로 옮기지 못해 원본을 그대로 둬요: ${src.name}")
        }
    }

    /** 영상과 같이 다니는 채팅 파일 */
    fun companions(video: File): List<File> {
        val base = video.absolutePath.substringBeforeLast('.')
        return listOf(File("$base.ass"), File("$base.chat.txt")).filter { it.exists() }
    }

    /** 휴지통에서 7일 지난 파일 삭제 */
    fun cleanTrash() {
        val limit = System.currentTimeMillis() - TRASH_DAYS * 24 * 3600_000L
        trashDir().listFiles()?.forEach { if (it.name != ".nomedia" && it.lastModified() < limit) it.delete() }
    }

    fun trashFiles(): List<File> = trashDir().listFiles()?.filter { it.name != ".nomedia" }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
