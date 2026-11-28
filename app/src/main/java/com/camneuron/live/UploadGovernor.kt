package com.camneuron.live

/**
 * 야외 방송(모바일 데이터)의 업로드가 밀리면 비트레이트 단계를 내리고, 좋아지면 한 단계씩 되돌린다.
 * 단계는 0(원래 비트레이트)부터 [maxLevel]까지이고, 단계마다의 비트레이트는 [LiveNet.limitBitrate]가 정한다.
 *
 * 목표는 방송이 끊기지 않는 것이다. 밀린 것이 한도를 넘으면 대기열이 영상·소리를 버리고 건너뛰는데 (MediaQueue), 그때마다
 * 시청자 화면이 멈춘다. 망이 갑자기 1/3로 느려지면 1초 남짓 만에 2초 분량이 쌓이므로, 1초마다 한 단계씩 내려서는 늦다.
 * 그래서 대기열을 자주([onDelay], 0.125초마다) 보고, 밀려 있으면 방금 **잰 망 속도**에 맞는 단계로 한 번에 내려간다.
 * 올릴 때는 천천히 올린다 ([update], 1초마다).
 *
 * - 대기열 맨 앞이 [BACKLOG_MS] 넘게 밀려 있으면 보내는 쪽은 망이 받아 주는 만큼만 보내고 있다. 그때 소켓 쓰기가 막혔다
 *   풀리는 간격으로 잰 속도(SendMeter)가 곧 망 속도다. 그 [HEADROOM]배에 들어가는 단계로 바로 내린다.
 *   (밀린 시간이 늘어나는 빠르기로 어림하면 안 된다: 비트레이트를 내린 뒤에도 먼저 쌓인 것 때문에 한동안 계속 늘어나 보여서
 *   필요 없이 바닥까지 내려간다. 정해진 시간 동안 나간 양으로 재도 안 된다: 막힌 쓰기는 덩어리로 풀려서 0 아니면 두 배가 나온다.)
 * - 잰 속도가 없으면 (아예 막혀 있거나 아직 재지 못했다) 여기서는 내리지 않는다. 잠깐 끊겼다 돌아오는 일에 바닥까지 내려가지
 *   않게. 정말 끊긴 것이면 곧 건너뛰게 되고, 그때 아래 규칙으로 내려간다.
 * - 대기열이 건너뛰었으면 적어도 [SKIP_STEP]단계 내린다. 그 뒤 [SETTLE_MS] 동안은 잰 속도를 믿지 않는다
 *   (건너뛴 직후에는 소켓 버퍼를 줄여서, 남은 옛것이 빠질 때까지 쓰기가 막힌다. LiveNet.sendBuffer).
 * - 대기열이 [SLOW_FILL] 넘게 찬 채로 [SLOW_SECONDS]초 동안 줄지 않으면 한 단계 내린다. 위의 길이 듣지 않을 때를 위한
 *   것이다 (인코더가 정한 비트레이트보다 많이 내보내면, 잰 망 속도로는 지금 단계가 맞아 보여도 계속 밀린다).
 * - 잘 보내지는 상태가 [recoverMs] 이어질 때마다 한 단계 올린다. 올렸다가 넘치면 위의 길로 1초 안팎에 도로 내려오므로
 *   (건너뛰지 않고) 빨리 올려 봐도 된다.
 * - 올리자마자([PROBE_FAIL_MS] 안에) 올리기 전 단계로 도로 내려오면 망이 딱 거기까지인 것이므로, 다음에는 두 배씩
 *   ([MAX_RECOVER_MS]까지) 더 기다린다. 올린 단계가 [PROBE_OK_MS] 버티면 다시 짧게 기다린다. 올리기 전 단계보다 더 낮게
 *   내려갔으면 올린 탓이 아니라 망이 나빠진 것이므로 더 기다리지 않는다 (걸을 때처럼 망이 주기적으로 느려지는 곳에서,
 *   그때마다 올린 탓으로 여겨 낮은 화질에 눌러앉지 않게).
 * - 방송 세션 하나가 계속 들고 있어, 끊겼다 다시 연결돼도 [STALE_MS] 안이면 낮춘 단계로 시작한다.
 *
 * (집 안 Wi-Fi용인 core의 NetGovernor는 35%까지만, 2초에 한 단계씩 내린다. 모바일 데이터는 그보다 훨씬 깊고 빠르게 꺼진다.)
 */
class UploadGovernor(
    private val maxLevel: Int = LiveNet.MAX_LEVEL,
    private val recoverMs: Long = LiveNet.RECOVER_MS,
) {
    @Volatile
    var level = 0
        private set
    private var slowSeconds = 0
    private var lastFill = 0f
    private var goodSinceMs = NONE
    private var lastUpMs = NONE

    /** 마지막으로 올리기 전의 단계. */
    private var lastUpFrom = 0
    private var lastUpdateMs = NONE
    private var waitMs = recoverMs

    private var settleUntilMs = NONE

    /**
     * 자주 (0.125초마다) 부른다. [delayMs]는 지금 대기열 맨 앞이 기다린 시간, [linkBps]는 방금 잰 망의 업로드 속도 (재지
     * 못했으면 음수), [bitrateAt]은 단계마다 보내는 양 (영상과 소리를 합친 bps). 밀려 있고 지금 단계가 망 속도에 넘치면,
     * 망이 실을 수 있는 단계로 바로 내리고 true.
     */
    @Synchronized
    fun onDelay(delayMs: Long, linkBps: Int, nowMs: Long, bitrateAt: (level: Int) -> Int): Boolean {
        if (level >= maxLevel || delayMs < BACKLOG_MS || linkBps < 0) return false
        if (settleUntilMs != NONE && nowMs < settleUntilMs) return false
        val target = levelWithin(linkBps * HEADROOM, bitrateAt)
        return target > level && lower(target, nowMs)
    }

    /**
     * 1초마다 부른다. [skips]는 그동안 대기열이 건너뛴 횟수, [queueFill]은 대기열이 가장 찼을 때의 비율 (1이면 건너뛰기 직전).
     * 단계가 바뀌었으면 true.
     */
    @Synchronized
    fun update(skips: Int, queueFill: Float, nowMs: Long): Boolean {
        if (lastUpdateMs != NONE && nowMs - lastUpdateMs > STALE_MS) reset()
        lastUpdateMs = nowMs

        val draining = queueFill < lastFill - DRAIN
        lastFill = queueFill
        slowSeconds = if (queueFill >= SLOW_FILL && !draining) slowSeconds + 1 else 0

        var target = level
        if (skips > 0) {
            target = level + SKIP_STEP
            settleUntilMs = nowMs + SETTLE_MS
        }
        if (slowSeconds >= SLOW_SECONDS) {
            target = maxOf(target, level + 1)
            slowSeconds = 0
        }
        if (target > level) return lower(target, nowMs)

        // 올린 단계가 한동안 버티면 다시 짧게 기다려도 된다.
        if (lastUpMs != NONE && nowMs - lastUpMs >= PROBE_OK_MS) {
            waitMs = recoverMs
            lastUpMs = NONE
        }
        if (level == 0 || queueFill >= GOOD_FILL) {
            goodSinceMs = NONE
            return false
        }
        if (goodSinceMs == NONE) goodSinceMs = nowMs
        if (nowMs - goodSinceMs >= waitMs) {
            lastUpFrom = level
            level--
            goodSinceMs = nowMs // 다음 단계도 다시 기다린다
            lastUpMs = nowMs
            return true
        }
        return false
    }

    /** [target]단계로 내린다 (가장 낮은 단계까지만). 실제로 내려갔으면 true. */
    private fun lower(target: Int, nowMs: Long): Boolean {
        goodSinceMs = NONE
        slowSeconds = 0
        val next = minOf(target, maxLevel)
        // 올리자마자 올리기 전 단계로 돌아왔다: 망이 거기까지다. 더 낮게 내려갔으면 올린 탓이 아니다.
        if (lastUpMs != NONE && nowMs - lastUpMs <= PROBE_FAIL_MS && next <= lastUpFrom) waitMs = minOf(waitMs * 2, MAX_RECOVER_MS)
        lastUpMs = NONE
        if (next <= level) return false
        level = next
        return true
    }

    /** [budget] bps 안에 들어가는, 지금보다 낮지 않은 단계 가운데 화질이 가장 좋은 것. 없으면 가장 낮은 단계. */
    private fun levelWithin(budget: Float, bitrateAt: (Int) -> Int): Int {
        for (candidate in level..maxLevel) if (bitrateAt(candidate) <= budget) return candidate
        return maxLevel
    }

    @Synchronized
    fun reset(): Boolean {
        val changed = level != 0
        level = 0
        slowSeconds = 0
        lastFill = 0f
        goodSinceMs = NONE
        lastUpMs = NONE
        waitMs = recoverMs
        settleUntilMs = NONE
        return changed
    }

    companion object {
        /** 맨 앞이 이만큼 넘게 밀려 있으면 망이 보내는 양을 못 싣는 것이다. */
        const val BACKLOG_MS = 300L

        /** 잰 망 속도의 이만큼만 쓴다 (재는 것이 거칠고, 쌓인 것도 빼내야 한다). */
        const val HEADROOM = 0.85f

        /** 건너뛰었을 때 적어도 내리는 단계 수, 그리고 그 뒤 잰 속도를 믿지 않는 시간. */
        const val SKIP_STEP = 2
        const val SETTLE_MS = 2_000L

        /**
         * 대기열이 이만큼 찬 채로 [SLOW_SECONDS]초 동안 줄지 않으면 느린 것으로 본다. 비트레이트를 알맞게 내린 뒤에도 먼저 쌓인
         * 것 때문에 2초쯤은 더 차오르므로 그보다 길게 본다.
         */
        const val SLOW_FILL = 0.5f
        const val SLOW_SECONDS = 3

        /** 대기열이 한 번에 이만큼 넘게 줄었으면 밀린 것이 빠지는 중이다. */
        const val DRAIN = 0.1f
        const val GOOD_FILL = 0.2f

        const val PROBE_FAIL_MS = 10_000L
        const val PROBE_OK_MS = 60_000L
        const val MAX_RECOVER_MS = 120_000L
        const val STALE_MS = 60_000L
        private const val NONE = -1L
    }
}
