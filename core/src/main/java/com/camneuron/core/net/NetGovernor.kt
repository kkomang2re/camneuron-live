package com.camneuron.core.net

import com.camneuron.core.device.HeatGovernor

/**
 * Wi-Fi가 약해 송신 대기열이 밀리면 송출 화질을 단계별로 낮추고, 좋아지면 천천히 되돌린다 (폰을 들고 집 안을 다닐 때).
 *
 * - 1단계: 비트레이트 70%
 * - 2단계: 비트레이트 50%
 * - 3단계: 비트레이트 35%
 * - 4단계: 그리고 720p 이하
 *
 * 1~3단계는 인코더 비트레이트만 바꿔 끊기지 않고, 4단계로 가고 올 때만 인코더를 새로 만든다.
 * 쌓인 프레임을 버릴 만큼 밀리면 바로 한 단계, 대기열이 반 넘게 찬 채로 [SLOW_SECONDS]초 이어지면 한 단계 내린다.
 * 잘 보내지는 상태가 [RECOVER_MS] 이어질 때마다 한 단계씩 되돌린다 (720p에서 돌아올 때는 [RECOVER_RESIZE_MS]).
 * 올리자마자([PROBE_FAIL_MS] 안에) 다시 밀리면 그 자리는 아직 약한 것이므로 다음에는 두 배씩([MAX_RECOVER_MS]까지) 더 기다린다.
 * 약한 방에 머무는 동안 올렸다 멈췄다를 되풀이하지 않게.
 * 송출 서버 하나가 계속 들고 있어, 끊겼다 다시 연결돼도 [STALE_MS] 안이면 낮춘 단계로 시작한다.
 */
class NetGovernor {
    @Volatile
    var level = 0
        private set
    private var lastDownMs = NONE
    private var goodSinceMs = NONE
    private var slowSeconds = 0
    private var lastUpdateMs = NONE
    private var lastUpMs = NONE
    private var recoverMs = RECOVER_MS

    /**
     * 1초마다 부른다. [drops]는 그동안 쌓인 프레임을 버린 횟수, [queueFill]은 대기열이 가장 찼을 때의 비율(1이면 버리기 직전).
     * 단계가 바뀌었으면 true.
     */
    @Synchronized
    fun update(drops: Int, queueFill: Float, nowMs: Long): Boolean {
        if (lastUpdateMs != NONE && nowMs - lastUpdateMs > STALE_MS) reset()
        lastUpdateMs = nowMs

        val slow = queueFill >= SLOW_FILL
        slowSeconds = if (slow) slowSeconds + 1 else 0
        val congested = drops > 0 || slowSeconds >= SLOW_SECONDS
        if (congested) {
            goodSinceMs = NONE
            if (lastUpMs != NONE && nowMs - lastUpMs <= PROBE_FAIL_MS) recoverMs = minOf(recoverMs * 2, MAX_RECOVER_MS)
            lastUpMs = NONE
            if (level < MAX_LEVEL && (lastDownMs == NONE || nowMs - lastDownMs >= DOWN_INTERVAL_MS)) {
                level++
                lastDownMs = nowMs
                slowSeconds = 0
                return true
            }
            return false
        }
        // 올린 단계가 한동안 버티면 다시 짧게 기다려도 된다.
        if (lastUpMs != NONE && nowMs - lastUpMs >= PROBE_OK_MS) {
            recoverMs = RECOVER_MS
            lastUpMs = NONE
        }
        if (level == 0 || queueFill >= GOOD_FILL) {
            goodSinceMs = NONE
            return false
        }
        if (goodSinceMs == NONE) goodSinceMs = nowMs
        val wait = if (level == MAX_LEVEL) maxOf(recoverMs, RECOVER_RESIZE_MS) else recoverMs
        if (nowMs - goodSinceMs >= wait) {
            level--
            goodSinceMs = nowMs // 다음 단계도 다시 기다린다
            lastUpMs = nowMs
            return true
        }
        return false
    }

    @Synchronized
    fun reset(): Boolean {
        val changed = level != 0
        level = 0
        lastDownMs = NONE
        goodSinceMs = NONE
        slowSeconds = 0
        lastUpMs = NONE
        recoverMs = RECOVER_MS
        return changed
    }

    companion object {
        const val MAX_LEVEL = 4
        const val SLOW_FILL = 0.5f
        const val GOOD_FILL = 0.2f
        const val SLOW_SECONDS = 2
        const val DOWN_INTERVAL_MS = 2_000L
        const val RECOVER_MS = 15_000L
        const val RECOVER_RESIZE_MS = 30_000L
        const val PROBE_FAIL_MS = 10_000L
        const val PROBE_OK_MS = 60_000L
        const val MAX_RECOVER_MS = 120_000L
        const val STALE_MS = 60_000L
        private const val NONE = -1L
        private const val MIN_BITRATE = 1_500_000

        fun limitBitrate(bitrate: Int, level: Int): Int {
            val scale = when {
                level <= 0 -> return bitrate
                level == 1 -> 0.7
                level == 2 -> 0.5
                else -> 0.35
            }
            return maxOf((bitrate * scale).toInt(), minOf(bitrate, MIN_BITRATE))
        }

        /** 요청한(또는 발열로 낮춘) 형식을 [level]에 맞게 낮춘 값. 원래 값보다 높이지는 않는다. */
        fun limit(limits: HeatGovernor.Limits, level: Int): HeatGovernor.Limits {
            if (level <= 0) return limits
            val (w, h) = if (level >= MAX_LEVEL && limits.height > 720) {
                (limits.width.toLong() * 720 / limits.height).toInt() to 720
            } else {
                limits.width to limits.height
            }
            return HeatGovernor.Limits(w, h, limits.fps, limitBitrate(limits.bitrate, level))
        }

        /** 이 단계에서 PC 화면 보기(PC → 폰)에 쓸 비트레이트(kbps). Wi-Fi가 약하면 방송 카메라에 먼저 양보한다. */
        fun monitorKbps(level: Int): Int = when {
            level <= 0 -> 1500
            level == 1 -> 1000
            level == 2 -> 600
            else -> 300
        }
    }
}
