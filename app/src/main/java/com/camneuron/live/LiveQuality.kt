package com.camneuron.live

/**
 * 방송 화질: 해상도(16:9 높이), 프레임 수, 비트레이트, 키프레임 간격.
 * 방송을 시작할 때 정하고, 방송 중에는 발열·네트워크에 따라 비트레이트만 이 값보다 낮아진다.
 */
data class LiveQuality(
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrateKbps: Int = recommendedKbps(1080, 30),
    val keyFrameS: Int = 2,
) {
    val width: Int get() = height * 16 / 9
    val bitrate: Int get() = bitrateKbps * 1000

    /** 예: "1080p30" */
    val label: String get() = "${height}p$fps"

    /** 범위를 벗어난 값을 가장 가까운 허용값으로 (저장된 값이 잘못됐을 때). */
    fun sanitized(): LiveQuality {
        val h = if (height in HEIGHTS) height else HEIGHTS.minBy { kotlin.math.abs(it - height) }
        val f = if (fps in FPS_VALUES) fps else FPS_VALUES.minBy { kotlin.math.abs(it - fps) }
        val kbps = (bitrateKbps.coerceIn(MIN_KBPS, MAX_KBPS) + STEP_KBPS / 2) / STEP_KBPS * STEP_KBPS
        val key = if (keyFrameS in KEY_FRAME_VALUES) keyFrameS else 2
        return LiveQuality(h, f, kbps.coerceIn(MIN_KBPS, MAX_KBPS), key)
    }

    /** 해상도·프레임 수를 바꾸면 비트레이트도 그 조합의 권장값으로 바꾼다. */
    fun withPreset(height: Int, fps: Int) = copy(height = height, fps = fps, bitrateKbps = recommendedKbps(height, fps))

    companion object {
        val HEIGHTS = listOf(1080, 720)
        val FPS_VALUES = listOf(30, 60)
        val KEY_FRAME_VALUES = listOf(1, 2)

        /**
         * 치지직이 받는 최대 비트레이트는 공식 문서로 확인하지 못했다. 1080p60을 넘는 값은 모바일 데이터로는
         * 버티기 어려워 8 Mbps를 상한으로 둔다.
         */
        const val MIN_KBPS = 1000
        const val MAX_KBPS = 8000
        const val STEP_KBPS = 500

        /**
         * 해상도·프레임 수별 권장 비트레이트. 폰 방송은 LTE·5G 업로드가 6~8 Mbps 안팎에서 흔들리므로, 업로드의
         * 2/3 아래로 잡아 여유를 둔다 (PC 방송 권장값보다 낮다). 화질을 더 원하면 설정에서 올린다.
         */
        fun recommendedKbps(height: Int, fps: Int): Int = when {
            height >= 1080 && fps >= 60 -> 6000
            height >= 1080 -> 4500
            fps >= 60 -> 3500
            else -> 2500
        }
    }
}
