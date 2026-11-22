package com.kakario.liverecorder

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent

/**
 * 앱이 예상 못 한 오류로 튕길 때: 죽기 직전에 '3초 뒤 다시 켜기'를 예약해서
 * 안드로이드가 살려 주길 기다리지 않고 바로 이어서 녹화한다.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val hadWork = RecordService.running || RecordService.savedJobs(this).isNotEmpty() || Sync.isRecorder(this)
                if (hadWork) {
                    getSharedPreferences("main", MODE_PRIVATE).edit()
                        .putString("lastCrash", "${java.util.Date()} ${e.javaClass.simpleName}: ${e.message}".take(300))
                        .commit()
                    val pi = PendingIntent.getForegroundService(this, 77,
                        Intent(this, RecordService::class.java).setAction(RecordService.ACTION_RESUME).putExtra("why", "crash"),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val am = getSystemService(AlarmManager::class.java)
                    val at = System.currentTimeMillis() + 3000
                    try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                    catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
                }
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
