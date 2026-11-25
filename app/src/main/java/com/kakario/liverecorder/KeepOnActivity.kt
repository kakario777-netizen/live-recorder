package com.kakario.liverecorder

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 변환용 검은 화면: 화면이 꺼지면 변환이 멈추거나 느려지는 폰을 위해 화면을 켠 채(밝기 최저) 둠.
 * 앱이 앞에 있으니 폰이 앱을 끄지도 않음. 글자는 1분마다 자리를 옮겨 화면 자국을 막음. 두 번 누르면 닫힘.
 */
class KeepOnActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var tv: TextView
    private var lastTap = 0L
    private var tick = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        tv = TextView(this).apply { setTextColor(Color.parseColor("#3A3A3A")); textSize = 13f }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTap < 1500) finish() else Toast.makeText(this@KeepOnActivity, "한 번 더 누르면 닫혀요", Toast.LENGTH_SHORT).show()
                lastTap = now
            }
        }
        setContentView(root)
        RecordService.addLog("변환용 검은 화면 켬 (화면 켜 둔 채 변환)")
        update()
    }

    private fun update() {
        val c = Tidy.current
        val wait = Tidy.queuedNames().size
        val up = Uploader.current
        tv.text = "변환용 화면 켜 둠 · 두 번 누르면 닫기\n" +
            (if (c != null) (if (Tidy.converting) "변환 중 ${Tidy.percent}%" else "정리 중 ${Tidy.percent}%") + "\n" else "변환할 것 없음\n") +
            "대기 ${wait}개 · 업로드 " + (if (up != null) "${Uploader.percent}%" else "쉬는 중")
        if (tick++ % 12 == 0) {   // 1분마다 글자 자리 이동
            val w = (resources.displayMetrics.widthPixels * 0.5).toInt(); val hh = (resources.displayMetrics.heightPixels * 0.8).toInt()
            tv.translationX = (Math.random() * w).toFloat(); tv.translationY = (Math.random() * hh).toFloat()
        }
        h.postDelayed({ if (!isFinishing) update() }, 5000)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); RecordService.addLog("변환용 검은 화면 끔"); super.onDestroy() }
}
