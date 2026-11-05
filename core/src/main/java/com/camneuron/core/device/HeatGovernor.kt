package com.camneuron.core.device

/**
 * 폰이 뜨거워지면 송출 화질을 단계별로 낮추고, 식으면 천천히 되돌린다.
 *
 * - 1단계: 30fps 이하, 비트레이트 80%
 * - 2단계: 그리고 1080p 이하, 비트레이트 60%
 * - 3단계: 그리고 720p 이하, 비트레이트 40%
 *
 * 열은 주로 카메라 처리량(해상도 × fps)과 인코더에서 나므로 fps부터 줄인다.
 * 뜨거운 상태가 [RAISE_MS] 동안 이어지면 그 단계로 바로 내리고, 식은 상태가 [RECOVER_MS] 동안 이어질 때마다
 * 한 단계씩 되돌린다. 화질을 바꿀 때마다 인코더를 새로 만들어 화면이 잠깐 멈추므로 되돌리는 쪽을 느리게 한다.
 * 송출 서버 하나가 계속 들고 있어, PC가 다시 접속해도 뜨거운 동안은 낮춘 화질로 시작한다.
 */
class HeatGovernor {
    data class Limits(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    @Volatile
    var level = 0
        private set
    private var hotSinceMs = NONE
    private var coolSinceMs = NONE

    /** 1초마다 부른다. 단계가 바뀌었으면 true. */
    @Synchronized
    fun update(thermalStatus: Int, batteryTempC: Float, nowMs: Long): Boolean {
        val target = targetLevel(thermalStatus, batteryTempC)
        when {
            target > level -> {
                coolSinceMs = NONE
                if (hotSinceMs == NONE) hotSinceMs = nowMs
                if (nowMs - hotSinceMs >= RAISE_MS) {
                    level = target
                    hotSinceMs = NONE
                    return true
                }
            }
            target < level -> {
                hotSinceMs = NONE
                if (coolSinceMs == NONE) coolSinceMs = nowMs
                if (nowMs - coolSinceMs >= RECOVER_MS) {
                    level--
                    coolSinceMs = nowMs // 다음 단계도 다시 RECOVER_MS를 기다린다
                    return true
                }
            }
            else -> {
                hotSinceMs = NONE
                coolSinceMs = NONE
            }
        }
        return false
    }

    /** 자동 대응을 끄면 바로 원래 화질로 되돌린다. 단계가 바뀌었으면 true. */
    @Synchronized
    fun reset(): Boolean {
        val changed = level != 0
        level = 0
        hotSinceMs = NONE
        coolSinceMs = NONE
        return changed
    }

    companion object {
        const val MAX_LEVEL = 3
        const val RAISE_MS = 5_000L
        const val RECOVER_MS = 180_000L
        private const val NONE = -1L
        private const val MIN_BITRATE = 2_000_000

        /**
         * Android 발열 단계(PowerManager.THERMAL_STATUS_*)의 MODERATE(2) → 1, SEVERE(3) → 2, CRITICAL(4) 이상 → 3.
         * 발열 단계를 늦게 올리는 폰도 있어 배터리 온도도 함께 본다.
         */
        fun targetLevel(thermalStatus: Int, batteryTempC: Float): Int {
            val byStatus = (thermalStatus - 1).coerceIn(0, MAX_LEVEL)
            val byBattery = when {
                batteryTempC >= 50f -> 3
                batteryTempC >= 47f -> 2
                batteryTempC >= 44f -> 1
                else -> 0
            }
            return maxOf(byStatus, byBattery)
        }

        /** 요청한 송출 형식을 [level]에 맞게 낮춘 값. 원래 값보다 높이지는 않는다. */
        fun limit(width: Int, height: Int, fps: Int, bitrate: Int, level: Int): Limits {
            if (level <= 0) return Limits(width, height, fps, bitrate)
            val maxHeight = when (level) {
                1 -> Int.MAX_VALUE
                2 -> 1080
                else -> 720
            }
            val (w, h) = if (height > maxHeight) (width.toLong() * maxHeight / height).toInt() to maxHeight else width to height
            return Limits(w, h, minOf(fps, 30), limitBitrate(bitrate, level))
        }

        fun limitBitrate(bitrate: Int, level: Int): Int {
            val scale = when {
                level <= 0 -> return bitrate
                level == 1 -> 0.8
                level == 2 -> 0.6
                else -> 0.4
            }
            return maxOf((bitrate * scale).toInt(), minOf(bitrate, MIN_BITRATE))
        }
    }
}
