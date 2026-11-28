package com.camneuron.live

/**
 * 폰이 뜨거울 때 방송이 줄이는 것 ([com.camneuron.core.device.HeatGovernor]의 단계 0~3).
 *
 * 비트레이트를 낮춰도 폰은 거의 식지 않는다. 열은 카메라·GPU·인코더가 처리하는 양(해상도 × 프레임 수)과 화면에서
 * 나고, 하드웨어 인코더가 쓰는 힘은 비트레이트와 거의 상관이 없다. 그런데 1080p를 그대로 둔 채 비트레이트만 낮추면
 * 화면이 모자이크처럼 깨진다 (예전에는 가장 뜨거울 때 40%까지 낮춰 1080p를 1.8 Mbps로 보냈다).
 * 그래서 처리량을 먼저 줄이고, 비트레이트는 가장 뜨거울 때만 조금 낮춘다.
 *
 * - 1단계: 60fps면 30fps로. 위젯 그림 뜨기를 1.5배 느리게.
 * - 2단계: 위젯 2배 느리게, 비트레이트 90%.
 * - 3단계: 위젯 3배 느리게, 비트레이트 75%.
 *
 * 해상도는 바꾸지 않는다 (방송 중에 해상도를 바꾸면 시청자 플레이어가 멈추는 경우가 있다).
 */
object LiveHeat {
    const val MAX_FPS_WHEN_HOT = 30

    /** 이 단계에서 보낼 프레임 수. 요청한 값보다 높이지는 않는다. */
    fun fps(requested: Int, level: Int): Int = if (level >= 1) minOf(requested, MAX_FPS_WHEN_HOT) else requested

    /** 이 단계에서 쓸 비트레이트 (bps). 네트워크 대응([UploadGovernor])은 이 값에서 다시 낮춘다. */
    fun bitrate(bitrate: Int, level: Int): Int = when {
        level <= 1 -> bitrate
        level == 2 -> (bitrate.toLong() * 9 / 10).toInt()
        else -> (bitrate.toLong() * 3 / 4).toInt()
    }

    /** 위젯 그림을 뜨는 간격에 곱할 값 ([com.camneuron.live.widget.WidgetHost.slowdown]). */
    fun widgetSlowdown(level: Int): Float = when {
        level <= 0 -> 1f
        level == 1 -> 1.5f
        level == 2 -> 2f
        else -> 3f
    }
}
