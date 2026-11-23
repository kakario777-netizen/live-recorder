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
    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        if (d["t"] == "bm" && CloudBm.on(this)) {
            val app = applicationContext
            Thread { CloudBm.sync(app) }.start()
        }
        // 녹화폰에서 온 알림: 원격폰만 알림창에 띄우고 업로드 기록 목록에 남김
        if (d["t"] == "note" && d["src"] != CloudBm.deviceId(this) && !CloudBm.recordHere(this) && CloudBm.notifyOn(this)) {
            val title = d["title"].orEmpty(); val body = d["body"].orEmpty()
            Uploader.addNote(this, title, body)
            showNote(title, body)
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
