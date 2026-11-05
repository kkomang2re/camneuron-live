package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import kotlin.math.roundToInt

/**
 * 위젯을 그리는 가상 화면. OBS의 1920x1080 장면처럼 CSS 1px = 1px로 그린다 (치지직 위젯은 이 크기에 맞춰져 있다).
 * 실제 송출이 720p여도 이 크기로 그린 뒤 GPU가 줄여 얹는다.
 */
object WidgetCanvas {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    /** mdpi: 1dp = 1px = CSS 1px */
    const val DENSITY_DPI = 160

    /** 위젯 하나가 차지하는 픽셀 사각형 (왼쪽, 위, 폭, 높이). 최소 1px. */
    data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

    fun pixelRect(item: OverlayItem): PixelRect {
        val left = (item.x * WIDTH).roundToInt().coerceIn(0, WIDTH - 1)
        val top = (item.y * HEIGHT).roundToInt().coerceIn(0, HEIGHT - 1)
        val width = (item.w * WIDTH).roundToInt().coerceIn(1, WIDTH - left)
        val height = (item.h * HEIGHT).roundToInt().coerceIn(1, HEIGHT - top)
        return PixelRect(left, top, width, height)
    }

    /** 송출에 얹을지: 주소가 있고 "방송에 넣기"를 켰을 때. */
    fun streamed(item: OverlayItem): Boolean = item.active && item.stream
}
