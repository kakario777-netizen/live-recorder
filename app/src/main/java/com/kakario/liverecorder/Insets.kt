package com.kakario.liverecorder

import android.view.View
import android.view.WindowInsets

/**
 * 안드로이드 15부터 앱 화면이 상태창·네비게이션 바 밑까지 그려지므로,
 * 그만큼(키보드가 뜨면 키보드 높이까지) 안쪽으로 여백을 준다.
 */
fun View.fitSystemBars() {
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsets.CONSUMED
    }
    requestApplyInsets()
}
