package com.camneuron.core.overlay

/** 위젯 창을 끌어 옮기거나 크기를 바꿀 때의 계산. 좌표는 영상 기준 0~1. */
object OverlayGeometry {
    const val MIN_FRACTION = 0.08f

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * [start]에서 손가락이 영상 폭·높이 대비 [dx]·[dy]만큼 움직였을 때의 새 자리.
     * [resize]면 오른쪽 아래 모서리를 끈 것이고, 아니면 통째로 옮긴 것이다. 영상 밖으로 나가지 않고 [MIN_FRACTION]보다 작아지지 않는다.
     */
    fun dragged(start: Box, dx: Float, dy: Float, resize: Boolean): Box {
        val w = (if (resize) start.w + dx else start.w).coerceIn(MIN_FRACTION, 1f)
        val h = (if (resize) start.h + dy else start.h).coerceIn(MIN_FRACTION, 1f)
        val x = (if (resize) start.x else start.x + dx).coerceIn(0f, 1f - w)
        val y = (if (resize) start.y else start.y + dy).coerceIn(0f, 1f - h)
        return Box(x, y, w, h)
    }
}
