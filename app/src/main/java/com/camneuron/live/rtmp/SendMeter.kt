package com.camneuron.live.rtmp

/**
 * 소켓에 쓴 바이트를 세고, 쓰기가 막혔다 풀리는 간격으로 망의 업로드 속도를 잰다.
 *
 * 망이 보내는 양을 못 따라가면 소켓 송신 버퍼가 차서 쓰기가 막힌다. 리눅스(Android)는 막힌 쓰기를 버퍼가 조금 빌 때마다
 * 깨우지 않고 **1/3쯤 비었을 때** 한꺼번에 깨운다. 그래서 정해진 시간(예: 0.4초) 동안 쓴 바이트로 속도를 재면 그 사이에
 * 깨어난 횟수에 따라 0이 나오기도, 실제의 두 배가 나오기도 한다. 대신 깨어난 때부터 다음에 깨어난 때까지 쓴 양을 그 간격으로
 * 나누면 한 번 깨어날 때마다 실제 속도가 나온다 (그동안 망이 비워 준 만큼을 다시 채워 넣은 것이므로).
 * 간격 하나로는 ±15%쯤 흔들려서, 두 번째부터는 지난 두 간격을 합쳐 잰다.
 *
 * 쓰기가 막히지 않는 동안에는 (망이 넉넉하다) 재지 않는다: [linkBps]는 -1.
 * [onWrite]와 [restart]의 뒤처리는 쓰는 스레드에서만 돈다. 읽는 것([count], [linkBps])은 아무 스레드에서나.
 */
class SendMeter(
    /** 쓰기 하나가 이보다 오래 걸렸으면 버퍼가 차서 기다린 것이다. */
    private val blockedNs: Long = 5_000_000L,
    /** 깨어난 간격이 이보다 길면 그 사이에 쉬었거나 끊겼던 것으로 보고 재지 않는다. */
    private val maxIntervalNs: Long = 3_000_000_000L,
) {
    /** 소켓이 받아 준 바이트 수. */
    @Volatile
    var count = 0L
        private set

    @Volatile
    private var rateBps = -1

    @Volatile
    private var rateAtNs = 0L

    @Volatile
    private var restartRequested = false
    private var wakeNs = 0L
    private var countAtWake = 0L

    // 그 앞의 깨어남 (없으면 0): 두 간격을 합쳐 재는 데 쓴다.
    private var earlierWakeNs = 0L
    private var countAtEarlierWake = 0L

    /** 쓰기 하나가 끝났다: [bytes]바이트를 쓰는 데 [startNs]부터 [endNs]까지 걸렸다. */
    fun onWrite(bytes: Int, startNs: Long, endNs: Long) {
        if (restartRequested) {
            restartRequested = false
            idle()
            rateBps = -1
        }
        if (endNs - startNs >= blockedNs) {
            // 막혀 있다가 깨어났다. 지난번 깨어난 뒤로 쓴 양이 그동안 망이 실어 간 양이다.
            val interval = endNs - wakeNs
            if (wakeNs != 0L && interval in 1..maxIntervalNs) {
                val two = earlierWakeNs != 0L && endNs - earlierWakeNs <= maxIntervalNs
                val fromNs = if (two) earlierWakeNs else wakeNs
                val fromCount = if (two) countAtEarlierWake else countAtWake
                rateBps = ((count - fromCount) * 8e9 / (endNs - fromNs)).toInt()
                rateAtNs = endNs
                earlierWakeNs = wakeNs
                countAtEarlierWake = countAtWake
            } else {
                earlierWakeNs = 0L
            }
            wakeNs = endNs
            countAtWake = count
        }
        count += bytes
    }

    /**
     * 보낼 것이 없어 쉬었다 (대기열이 비었다): 망이 보내는 양을 다 싣고 있으니 잴 것이 없다. 쉰 시간이 섞인 간격으로 재지
     * 않게, 다음에 막혔다 깨어나는 것을 새 시작으로 삼는다. 쓰는 스레드에서.
     */
    fun idle() {
        wakeNs = 0L
        earlierWakeNs = 0L
    }

    /**
     * 소켓 버퍼 크기를 바꿨다: 다음 간격은 버퍼가 새 크기로 줄어들 때까지 기다린 시간이 섞여 망 속도가 아니므로, 처음부터 다시 잰다.
     */
    fun restart() {
        restartRequested = true
    }

    /** [maxAgeNs] 안에 잰 업로드 속도 (bps). 그런 것이 없으면 (쓰기가 막힌 적이 없거나 오래됐다) -1. */
    fun linkBps(nowNs: Long, maxAgeNs: Long): Int {
        val rate = rateBps
        return if (!restartRequested && rate >= 0 && nowNs - rateAtNs <= maxAgeNs) rate else -1
    }
}
