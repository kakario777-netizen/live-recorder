package com.kakario.liverecorder

import android.content.Context
import android.os.PowerManager

/** 화면이 꺼져도 정리·변환·업로드가 멈추지 않게 CPU를 깨워 둠 (일하는 동안만) */
object Awake {
    private val locks = HashMap<String, PowerManager.WakeLock>()
    @Synchronized fun hold(ctx: Context, tag: String, on: Boolean) {
        val cur = locks[tag]
        if (on) {
            if (cur?.isHeld == true) return
            locks[tag] = ctx.applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liverec:$tag").apply { setReferenceCounted(false); acquire(6 * 3600 * 1000L) }
        } else {
            try { if (cur?.isHeld == true) cur.release() } catch (_: Exception) {}
            locks.remove(tag)
        }
    }
}
