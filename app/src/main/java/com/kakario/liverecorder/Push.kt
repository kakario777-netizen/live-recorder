package com.kakario.liverecorder

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 파이어베이스 푸시 (kakario-apps 프로젝트, 공용).
 * 즐겨찾기가 바뀌면 구글 스크립트가 'bm' 주제로 쪽지를 보냄 → 받은 폰이 바로 맞춤.
 */
object Push {
    @Volatile var ready = false; private set
    /** 원격폰 화면이 떠 있을 때 현황을 팝업으로 보여 줄 곳 */
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun init(ctx: Context) {
        if (BuildConfig.LAB) return   // 실험판은 등록 안 된 앱이라 푸시 없이 (30초 확인만)
        try {
            val app = FirebaseApp.getApps(ctx).firstOrNull() ?: FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
                .setApiKey("AIzaSyBzTPF-FB-FQHXwn8ozzEksTSrlHO_Nwdk")
                .setApplicationId("1:968730531875:android:b718d16b5497d9db5ef996")
                .setProjectId("kakario-apps")
                .setGcmSenderId("968730531875")
                .build())
            if (app == null) return
            FirebaseMessaging.getInstance().subscribeToTopic("note")
            FirebaseMessaging.getInstance().subscribeToTopic("req")
            FirebaseMessaging.getInstance().subscribeToTopic("bm").addOnCompleteListener { t ->
                ready = t.isSuccessful
                if (!t.isSuccessful) RecordService.addLog("푸시 연결 실패 → 즐겨찾기는 30초마다 확인으로 맞춤 (${t.exception?.message})")
            }
        } catch (e: Exception) {
            RecordService.addLog("푸시 준비 실패: ${e.message}")
        }
    }
}

class PushService : FirebaseMessagingService() {
    companion object { @Volatile var lastSent = ""; @Volatile var lastSentAt = 0L }
    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        if (d["t"] == "bm" && CloudBm.on(this)) {
            val app = applicationContext
            Thread { CloudBm.sync(app) }.start()
        }
        // 원격폰이 현황을 물어봄 → 녹화폰이 짧게 답함
        if (d["t"] == "req" && CloudBm.recordHere(this)) {
            val st = CloudBm.statusJson()
            val same = st == lastSent && System.currentTimeMillis() - lastSentAt < 10 * 60_000L
            if (!same) { lastSent = st; lastSentAt = System.currentTimeMillis() }
            CloudBm.notifyRemote(this, "녹화폰 현황", if (same) "=" else st, force = true)   // 바뀐 게 없으면 '=' 한 글자만
        }
        // 원격폰(뷰어)이 녹화를 부탁함 → 녹화폰이 받아서 녹화
        if (d["t"] == "note" && d["src"] != CloudBm.deviceId(this) && CloudBm.recordHere(this) && d["title"] == "녹화 요청") {
            val parts = d["body"].orEmpty().split('\n')
            val url = parts.getOrNull(0).orEmpty(); val name = parts.getOrNull(1).orEmpty()
            if (url.startsWith("http")) {
                val ua = try { android.webkit.WebSettings.getDefaultUserAgent(this) } catch (e: Exception) { "" }
                RecordService.add(this, url, ua, name)
                RecordService.addLog("원격폰 요청으로 녹화 추가: ${name.ifEmpty { url }}")
                CloudBm.notifyRemote(this, "녹화 요청 받음", name.ifEmpty { url }, force = true)
            }
        }
        // 뷰어가 보낸 명령(중지·다시 시도) → 녹화폰이 실행하고 새 현황을 답함
        if (d["t"] == "note" && d["src"] != CloudBm.deviceId(this) && CloudBm.recordHere(this) && d["title"] == "명령") {
            val (cmd, name) = d["body"].orEmpty().split('|', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (cmd == "trashh") name.toIntOrNull()?.let { Uploader.setTrashHours(this, it) }
            if (cmd == "trash") { val (n, mb) = Uploader.emptyTrash(); CloudBm.notifyRemote(this, "휴지통 비움", "${n}개 · ${mb}MB", force = true) }
            val sl = RecordService.list().firstOrNull { it.streamer.equals(name, true) || it.host == name }
            if (sl != null) {
                when (cmd) {
                    "stop" -> { RecordService.stopOne(this, sl.id); RecordService.addLog("원격폰 요청으로 중지: $name") }
                    "retry" -> { sl.retryNow(); RecordService.addLog("원격폰 요청으로 다시 시도: $name") }
                }
            }
            Thread { Thread.sleep(1500); val st = CloudBm.statusJson(); lastSent = st; lastSentAt = System.currentTimeMillis(); CloudBm.notifyRemote(this, "녹화폰 현황", st, force = true) }.start()
        }
        // 녹화폰에서 온 알림: 원격폰만 처리
        if (d["t"] == "note" && d["src"] != CloudBm.deviceId(this) && !CloudBm.recordHere(this)) {
            val title = d["title"].orEmpty(); val body = d["body"].orEmpty()
            if (title == "녹화폰 현황") {
                // 물어본 답: 마지막 현황을 저장해 두고, 현황 화면이 떠 있으면 바로 갱신 (알림창엔 안 띄움)
                val p = getSharedPreferences("cloudBm", MODE_PRIVATE)
                if (body != "=") p.edit().putString("lastStatus", body).putLong("lastStatusAt", System.currentTimeMillis()).apply()
                p.edit().putLong("lastStatusCheck", System.currentTimeMillis()).apply()
                Push.onStatus?.invoke(body)
            } else if (CloudBm.notifyOn(this)) {
                Uploader.addNote(this, title, body)
                showNote(title, body)
            }
        }
    }

    private fun showNote(title: String, body: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val ch = "remote_note"
        if (nm.getNotificationChannel(ch) == null)
            nm.createNotificationChannel(android.app.NotificationChannel(ch, "녹화폰 알림", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(true) })
        val pi = android.app.PendingIntent.getActivity(this, 0,
            android.content.Intent(this, FilesActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(pi).setNumber(1).build()
        try { nm.notify((System.currentTimeMillis() % 100000).toInt() + 1000, n) } catch (_: SecurityException) {}
    }
    override fun onNewToken(token: String) {
        FirebaseMessaging.getInstance().subscribeToTopic("bm")
    }
}
