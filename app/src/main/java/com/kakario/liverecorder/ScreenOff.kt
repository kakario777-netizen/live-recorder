package com.kakario.liverecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** 화면이 꺼지면 변환이 멈추는 폰: 그 폰에 알림 + 원격폰에 푸시 → 배터리 설정을 바꾸게 안내 */
object ScreenOff {
    fun exempt(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun stalled(ctx: Context) = ctx.getSharedPreferences("main", Context.MODE_PRIVATE).getLong("offStallAt", 0L) > 0

    fun warn(ctx: Context) {
        val p = ctx.getSharedPreferences("main", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("offStallAt", 0L) < 6 * 3600_000L) return   // 6시간에 한 번만
        p.edit().putLong("offStallAt", now).putBoolean("askedBattery", false).apply()
        val model = android.os.Build.MODEL
        val ex = exempt(ctx)
        RecordService.addLog("화면 꺼진 동안 변환이 멈춤 → 배터리 설정 안내 (배터리 최적화 제외: ${if (ex) "됨" else "안 됨"})")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("remote_note") == null)
            nm.createNotificationChannel(NotificationChannel("remote_note", "녹화폰 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 7301, Intent(ctx, MainActivity::class.java).putExtra("batteryHelp", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, "remote_note").setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("화면이 꺼지면 변환이 멈춰요")
            .setContentText("눌러서 배터리 설정을 바꿔 주세요 (이 폰만 해당)")
            .setContentIntent(pi).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "검은 화면 켜 두기",
                PendingIntent.getActivity(ctx, 7302, Intent(ctx, KeepOnActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        try { nm.notify(7301, n) } catch (_: SecurityException) {}
        CloudBm.notifyRemote(ctx, "변환 멈춤", "$model: 화면이 꺼지면 변환이 멈춰요. 그 폰에 온 알림을 눌러 배터리 설정을 바꿔 주세요", force = true)
    }
}
