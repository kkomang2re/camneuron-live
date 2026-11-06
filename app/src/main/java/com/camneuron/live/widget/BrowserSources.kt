package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings
import kotlin.math.roundToInt

/**
 * 위젯 칸 번호와 브라우저 소스 계산. 칸은 [WidgetHost.CHAT], [WidgetHost.ALERT], 그 뒤로 브라우저 소스가 [MAX]개까지.
 * 칸 번호가 곧 인코더의 그림 층 번호라서, 뒤 칸일수록 위에 그려진다.
 */
object BrowserSources {
    /** 위젯 하나마다 화면 밖 웹 페이지 하나와 초당 몇 번의 그림 뜨기가 든다. 폰 발열을 생각해 넉넉하지 않게. */
    const val MAX = 4

    const val ZOOM_MIN = 0.25f
    const val ZOOM_MAX = 3f
    const val ZOOM_STEP = 0.1f

    /** 칸마다의 설정. 길이는 [WidgetHost.COUNT], 빈 칸은 null. */
    fun slots(settings: OverlaySettings): List<OverlayItem?> =
        List(WidgetHost.COUNT) { index ->
            when (index) {
                WidgetHost.CHAT -> settings.chat
                WidgetHost.ALERT -> settings.alert
                else -> settings.sources.getOrNull(index - WidgetHost.FIRST_SOURCE)
            }
        }

    /** [index] 칸만 [transform]으로 바꾼다. 없는 칸이면 그대로. */
    fun update(settings: OverlaySettings, index: Int, transform: (OverlayItem) -> OverlayItem): OverlaySettings =
        when (index) {
            WidgetHost.CHAT -> settings.copy(chat = transform(settings.chat))
            WidgetHost.ALERT -> settings.copy(alert = transform(settings.alert))
            else -> {
                val i = index - WidgetHost.FIRST_SOURCE
                if (i !in settings.sources.indices) {
                    settings
                } else {
                    settings.copy(sources = settings.sources.toMutableList().also { it[i] = transform(it[i]) })
                }
            }
        }

    /** 내용 배율을 [steps]칸(+면 크게) 바꾼다. 10% 단위로 맞춘다. */
    fun stepZoom(zoom: Float, steps: Int): Float {
        val next = zoom + steps * ZOOM_STEP
        return ((next / ZOOM_STEP).roundToInt() * ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    /** 배율 표시 (예: 80%). */
    fun zoomPercent(zoom: Float): Int = (zoom * 100).roundToInt()
}
