package com.kakario.liverecorder

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 자동 업데이트 — 앱을 켤 때 GitHub 릴리스(latest)를 확인해서 새 빌드가 있으면 몰래 받아 두고,
 * '지금 업데이트'를 누르거나 앱을 나갈 때 설치한다. 설치 화면 없이 껐다 켜지기만 한다.
 * 녹화·변환 중에는 설치하지 않는다(설치하면 앱이 꺼져 녹화가 끊기므로).
 */
class Updater(private val a: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = a.getSharedPreferences("main", Context.MODE_PRIVATE)
    private var busy = false
    private var pendingApk: File? = null
    var readyApk: File? = null; private set

    companion object {
        private val TAG = if (BuildConfig.LAB) "lab" else "latest"
        val API = "https://api.github.com/repos/kakario777-netizen/live-recorder/releases/tags/$TAG"
        val PAGE = "https://github.com/kakario777-netizen/live-recorder/releases/tag/$TAG"

        fun working() = RecordService.running || ConvertService.current != null
    }

    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()

    fun myBuild(): Int = try {
        a.packageManager.getPackageInfo(a.packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) { 0 }

    private class Release(val build: Int, val notes: String, val apkUrl: String, val size: Long)

    /** silent: 새 버전이 없거나 실패하면 아무 말 안 함. 앱 켤 때 30분에 한 번 */
    fun check(silent: Boolean) {
        if (busy) return
        if (silent && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < 30 * 60_000L) return
        busy = true
        if (!silent) toast("업데이트 확인 중…")
        Thread {
            var rel: Release? = null
            var err: String? = null
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) throw Exception("서버 응답 ${c.responseCode}")
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                val build = Regex("#(\\d+)").find(o.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                var url = ""; var size = 0L
                o.optJSONArray("assets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        val u = x.optString("browser_download_url")
                        // 내 저장소 릴리스의 https 파일만
                        if (x.optString("name").endsWith(".apk") && u.startsWith("https://github.com/kakario777-netizen/live-recorder/releases/download/")) { url = u; size = x.optLong("size") }
                    }
                }
                rel = Release(build, o.optString("body"), url, size)
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
            main.post {
                busy = false
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                val r = rel
                when {
                    r == null -> if (!silent) AlertDialog.Builder(a).setTitle("업데이트 확인 실패").setMessage(err ?: "").setPositiveButton("확인", null).show()
                    r.build <= myBuild() || r.apkUrl.isEmpty() -> if (!silent) toast("최신 버전이에요 (빌드 ${myBuild()})")
                    else -> download(r)
                }
            }
        }.start()
    }

    private fun download(r: Release) {
        val dir = File(a.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "live-recorder-${r.build}.apk")
        Thread {
            val ok = try {
                dir.listFiles()?.forEach { if (it != f) it.delete() }
                if (!(f.exists() && r.size > 0 && f.length() == r.size)) {
                    val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 60000
                    if (c.responseCode != 200) throw Exception()
                    c.inputStream.use { ins -> FileOutputStream(f).use { ins.copyTo(it, 128 * 1024) } }
                    c.disconnect()
                }
                // 받은 파일이 정말 이 앱의 새 버전인지 확인 (이름·버전이 다르면 버림)
                val info = a.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                (f.length() > 0 && info != null && info.packageName == a.packageName && info.longVersionCode.toInt() == r.build && r.build > myBuild())
                    .also { if (!it) f.delete() }
            } catch (e: Exception) { false }
            main.post {
                if (!ok || a.isFinishing) return@post
                readyApk = f
                val notes = r.notes.substringAfter("변경 내용:", r.notes).trim().lines().firstOrNull()?.take(120) ?: ""
                AlertDialog.Builder(a)
                    .setTitle("새 버전 준비됨 (빌드 ${myBuild()} → ${r.build})")
                    .setMessage((if (notes.isNotBlank()) "$notes\n\n" else "") +
                        "지금 업데이트하면 앱이 잠깐 껐다 켜져요.\n'나중에'를 누르면 앱을 나갈 때 알아서 업데이트해요." +
                        if (RecordService.running) "\n\n녹화 중이에요. 업데이트하면 녹화가 10~30초 끊겼다가 같은 파일에 자동으로 이어져요." else "")
                    .setPositiveButton("지금 업데이트") { _, _ ->
                        // 정리·업로드·변환은 멈추고 업데이트부터 (하던 건 저장돼 있어서 업데이트 뒤 이어서 함)
                        if (Tidy.busy() || Uploader.busy() || ConvertService.current != null) RecordService.addLog("업데이트 먼저 → 정리·업로드 잠시 멈춤 (업데이트 뒤 이어서)")
                        install(f, relaunch = !RecordService.running)
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
        }.start()
    }

    /** 앱을 나갈 때: 받아 둔 업데이트가 있고 녹화·변환 중이 아니면 조용히 설치 */
    fun installOnLeave() {
        val f = readyApk ?: return
        if (!f.exists() || !a.packageManager.canRequestPackageInstalls()) return   // 녹화·정리 중이어도 업데이트 먼저 (뒤에 이어서)
        readyApk = null
        install(f, relaunch = false)
    }

    fun install(f: File, relaunch: Boolean) {
        prefs.edit().putBoolean("relaunchAfterUpdate", relaunch).apply()
        if (!a.packageManager.canRequestPackageInstalls()) {
            pendingApk = f
            AlertDialog.Builder(a)
                .setTitle("설치 허용이 필요해요")
                .setMessage("앱이 스스로 업데이트하려면 '출처를 알 수 없는 앱 설치'를 한 번 허용해야 해요.\n허용하고 돌아오면 바로 업데이트돼요.")
                .setPositiveButton("허용하러 가기") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
                    catch (e: Exception) { toast("설정 화면을 열 수 없어요") }
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        pendingApk = null
        try {
            val pi = a.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(a.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) params.setRequestUpdateOwnership(true)
            params.setSize(f.length())
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out, 256 * 1024) }
                    s.fsync(out)
                }
                val cb = PendingIntent.getBroadcast(a, id, Intent(a, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(cb.intentSender)
            }
            if (relaunch) toast("업데이트 설치 중… 잠시 뒤 앱이 다시 열려요")
        } catch (e: Exception) {
            AlertDialog.Builder(a).setTitle("업데이트를 설치하지 못했어요")
                .setMessage("릴리스 페이지에서 직접 받아 주세요.\n${e.message ?: ""}")
                .setPositiveButton("릴리스 페이지 열기") { _, _ -> a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGE))) }
                .setNegativeButton("닫기", null).show()
        }
    }

    /** 설치 허용하고 돌아왔을 때 이어서 설치 */
    fun onResume() {
        val f = pendingApk ?: return
        if (a.packageManager.canRequestPackageInstalls() && f.exists()) install(f, relaunch = true)
    }
}

/** 앱 안 설치(PackageInstaller) 결과 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 처음 한 번은 안드로이드가 '업데이트할까요?' 확인을 요구할 수 있음
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (e: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> if (st != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(ctx, "업데이트 설치 실패: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** 업데이트가 끝나면: 녹화는 자동 재개, 앱 화면은 '지금 업데이트'를 눌렀을 때만 다시 연다 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val p = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
        // 녹화 중이던 게 있으면 묻지 않고 바로 이어서 녹화
        try { RecordService.resumeAfterUpdate(ctx) } catch (_: Exception) {}
        if (!p.getBoolean("relaunchAfterUpdate", false)) return
        p.edit().putBoolean("relaunchAfterUpdate", false).apply()
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(open) } catch (e: Exception) {}
        try {
            val m = ctx.getSystemService(NotificationManager::class.java)
            if (m.getNotificationChannel("update") == null) m.createNotificationChannel(NotificationChannel("update", "업데이트", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            m.notify(7001, Notification.Builder(ctx, "update")
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("라이브 녹화 업데이트 완료")
                .setContentText("눌러서 열기")
                .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60_000)
                .build())
        } catch (e: Exception) {}
    }
}
