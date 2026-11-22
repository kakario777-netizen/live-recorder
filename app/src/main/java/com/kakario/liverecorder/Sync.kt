package com.kakario.liverecorder

import android.content.Context
import android.content.Intent
import android.os.Build
import org.apache.commons.net.ftp.FTPClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 폰끼리 동기화 — NAS(FTP)의 '.sync' 폴더를 중간 저장소로 쓴다.
 * - 즐겨찾기·설정: 바뀐 시각이 최근인 쪽으로 합침
 * - 녹화 명령: 리모컨 폰이 명령 파일을 올리면, 녹화 담당 폰이 15초마다 확인해서 실행
 * - 녹화 현황: 녹화 담당 폰이 30초마다 현황 파일을 올리고, 리모컨 폰은 그걸 읽어 보여 줌
 */
object Sync {
    const val SOLO = "solo"
    const val RECORDER = "recorder"
    const val REMOTE = "remote"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
    fun role(ctx: Context) = prefs(ctx).getString("syncRole", SOLO) ?: SOLO
    fun setRole(ctx: Context, r: String) = prefs(ctx).edit().putString("syncRole", r).apply()
    fun active(ctx: Context) = role(ctx) != SOLO && FtpConfig.ready(ctx)
    fun isRemote(ctx: Context) = role(ctx) == REMOTE && FtpConfig.ready(ctx)
    fun isRecorder(ctx: Context) = role(ctx) == RECORDER && FtpConfig.ready(ctx)

    private fun dir(ctx: Context) = FtpConfig.folder(ctx).trimEnd('/') + "/.sync"
    private val device: String get() = Build.MODEL

    private fun <T> ftp(ctx: Context, block: (FTPClient) -> T): T {
        val f = Uploader.connect(ctx)
        try {
            Uploader.mkdirs(f, dir(ctx) + "/cmd")
            return block(f)
        } finally {
            try { f.logout() } catch (_: Exception) {}
            try { f.disconnect() } catch (_: Exception) {}
        }
    }

    private fun read(f: FTPClient, path: String): String? {
        val out = ByteArrayOutputStream()
        return if (f.retrieveFile(path, out)) String(out.toByteArray()) else null
    }

    /** 임시 이름으로 올린 뒤 바꿔서, 읽는 쪽이 반쯤 쓴 파일을 보지 않게 */
    private fun write(f: FTPClient, path: String, text: String) {
        val tmp = "$path.tmp"
        if (!f.storeFile(tmp, ByteArrayInputStream(text.toByteArray()))) throw Exception("동기화 파일 쓰기 실패")
        f.deleteFile(path)
        f.rename(tmp, path)
    }

    // ---------- 즐겨찾기·설정 ----------

    private fun localSettings(ctx: Context) = JSONObject()
        .put("nameSite", Names.useSite(ctx)).put("nameStreamer", Names.useStreamer(ctx)).put("nameTime", Names.useTime(ctx))
        .put("t", prefs(ctx).getLong("settingsT", 0))

    fun settingsChanged(ctx: Context) {
        prefs(ctx).edit().putLong("settingsT", System.currentTimeMillis()).apply()
        pushSoon(ctx)
    }

    /** 즐겨찾기·설정을 NAS와 맞춤 (양쪽 합친 결과를 양쪽에 저장) */
    fun syncData(ctx: Context): String = try {
        ftp(ctx) { f ->
            val d = dir(ctx)
            val remoteB = read(f, "$d/bookmarks.json")?.let { runCatching { Bookmarks.fromJson(JSONArray(it)) }.getOrNull() } ?: emptyList()
            val merged = Bookmarks.merge(Bookmarks.all(ctx), remoteB)
            Bookmarks.saveAll(ctx, merged)
            write(f, "$d/bookmarks.json", Bookmarks.toJson(merged).toString())

            val remoteS = read(f, "$d/settings.json")?.let { runCatching { JSONObject(it) }.getOrNull() }
            val localS = localSettings(ctx)
            if (remoteS != null && remoteS.optLong("t") > localS.optLong("t")) {
                Names.setParts(ctx, remoteS.optBoolean("nameSite", true), remoteS.optBoolean("nameStreamer", true), remoteS.optBoolean("nameTime", true))
                prefs(ctx).edit().putLong("settingsT", remoteS.optLong("t")).apply()
            } else {
                write(f, "$d/settings.json", localS.toString())
            }
            "동기화 완료 (즐겨찾기 ${merged.count { !it.del }}개)"
        }
    } catch (e: Exception) {
        "동기화 실패: ${e.message ?: e.javaClass.simpleName}"
    }

    private var pushThread: Thread? = null

    /** 바뀐 내용을 곧 NAS에 반영 (여러 번 바뀌어도 한 번만) */
    fun pushSoon(ctx: Context) {
        if (!active(ctx)) return
        val app = ctx.applicationContext
        synchronized(this) {
            if (pushThread?.isAlive == true) return
            pushThread = Thread { Thread.sleep(1500); syncData(app) }.also { it.start() }
        }
    }

    // ---------- 녹화 명령 (리모컨 → 담당 폰) ----------

    /** 리모컨 폰: 명령 보내기. action = "add" / "stop" / "stopAll" */
    fun sendCommand(ctx: Context, action: String, url: String = "", name: String = "", ua: String = "", done: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val r = try {
                ftp(app) { f ->
                    val o = JSONObject().put("action", action).put("url", url).put("name", name).put("ua", ua)
                        .put("from", device).put("t", System.currentTimeMillis())
                    write(f, "${dir(app)}/cmd/${System.currentTimeMillis()}_${(1000..9999).random()}.json", o.toString())
                    "녹화 담당 폰에 명령을 보냈어요 (15초 안에 실행돼요)"
                }
            } catch (e: Exception) { "명령 보내기 실패: ${e.message}" }
            done(r)
        }.start()
    }

    /** 담당 폰: 명령 확인·실행 */
    fun pollCommands(ctx: Context) {
        try {
            ftp(ctx) { f ->
                val cmdDir = "${dir(ctx)}/cmd"
                val names = f.listNames(cmdDir)?.map { it.substringAfterLast('/') }?.filter { it.endsWith(".json") }?.sorted() ?: emptyList()
                for (n in names) {
                    val path = "$cmdDir/$n"
                    val o = read(f, path)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    f.deleteFile(path)
                    if (o == null) continue
                    val url = o.optString("url")
                    RecordService.addLog("원격 명령(${o.optString("from")}): ${o.optString("action")} ${o.optString("name").ifEmpty { url.take(60) }}")
                    when (o.optString("action")) {
                        "add" -> RecordService.add(ctx, url, o.optString("ua"), o.optString("name"))
                        "stop" -> RecordService.list().firstOrNull { it.pageUrl == url }?.let { RecordService.stopOne(ctx, it.id) }
                        "stopAll" -> RecordService.list().forEach { RecordService.stopOne(ctx, it.id) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ---------- 녹화 현황 (담당 폰 → 리모컨) ----------

    fun pushStatus(ctx: Context) {
        try {
            ftp(ctx) { f ->
                val a = JSONArray()
                RecordService.list().forEach {
                    a.put(JSONObject().put("url", it.pageUrl).put("name", it.streamer.ifEmpty { it.host })
                        .put("summary", it.summary()).put("state", it.state))
                }
                val o = JSONObject().put("device", device).put("t", System.currentTimeMillis()).put("slots", a)
                    .put("uploads", Uploader.pendingCount(ctx))
                write(f, "${dir(ctx)}/status.json", o.toString())
            }
        } catch (_: Exception) {}
    }

    class RemoteSlot(val url: String, val name: String, val summary: String)
    class RemoteStatus(val device: String, val ageSec: Long, val slots: List<RemoteSlot>, val uploads: Int)

    fun fetchStatus(ctx: Context): RemoteStatus? = try {
        ftp(ctx) { f ->
            val o = JSONObject(read(f, "${dir(ctx)}/status.json") ?: return@ftp null)
            val a = o.optJSONArray("slots") ?: JSONArray()
            RemoteStatus(
                o.optString("device"), (System.currentTimeMillis() - o.optLong("t")) / 1000,
                (0 until a.length()).map { val s = a.getJSONObject(it); RemoteSlot(s.optString("url"), s.optString("name"), s.optString("summary")) },
                o.optInt("uploads"),
            )
        }
    } catch (e: Exception) { null }

    /** 담당 폰: 녹화가 없어도 명령을 받을 수 있게 서비스 유지 */
    fun startRecorderService(ctx: Context) {
        if (!isRecorder(ctx)) return
        ctx.startForegroundService(Intent(ctx, RecordService::class.java).setAction(RecordService.ACTION_SYNC))
    }
}
