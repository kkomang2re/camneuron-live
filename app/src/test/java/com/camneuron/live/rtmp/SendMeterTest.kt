package com.camneuron.live.rtmp

import org.junit.Assert.assertEquals
import org.junit.Test

class SendMeterTest {
    private val ms = 1_000_000L
    private val meter = SendMeter()
    private var t = 10_000 * ms

    /** 바로 끝난 쓰기 (버퍼에 자리가 있었다). */
    private fun quick(bytes: Int = 8_192) {
        meter.onWrite(bytes, t, t + ms / 10)
        t += ms / 10
    }

    /** 버퍼가 차서 [waitMs] 동안 막혔다가 끝난 쓰기. */
    private fun blocked(waitMs: Long, bytes: Int = 8_192) {
        meter.onWrite(bytes, t, t + waitMs * ms)
        t += waitMs * ms
    }

    private fun kbps() = meter.linkBps(t, 1_500 * ms).let { if (it < 0) -1 else it / 1000 }

    @Test
    fun countsEveryByte() {
        quick(100)
        blocked(50, 8_000)
        assertEquals(8_100L, meter.count)
    }

    @Test
    fun nothingIsMeasuredWhileWritesNeverBlock() {
        repeat(100) { quick() }
        assertEquals(-1, kbps())
    }

    @Test
    fun speedIsWhatWasWrittenBetweenTwoWakeUps() {
        // 망이 1.5 Mbps: 버퍼의 1/3(48KB)이 빌 때마다 0.26초 간격으로 깨어난다.
        blocked(260)
        assertEquals(-1, kbps()) // 처음 깨어난 것은 기준일 뿐이다
        repeat(5) { quick() }    // 깨어나자마자 빈 자리를 채운다
        blocked(260)
        // 지난번 깨어난 뒤로 48KB(막혔던 8KB + 바로 쓴 40KB)를 0.26초에: 1.5 Mbps
        assertEquals(1_509, kbps())
    }

    @Test
    fun fromTheSecondIntervalOnTwoAreAveraged() {
        blocked(260)
        repeat(5) { quick() }
        blocked(260)
        // 이번 간격은 0.2초로 짧았다 (그것만 보면 1.96 Mbps). 두 간격을 합쳐 96KB / 0.46초 = 1.7 Mbps.
        repeat(5) { quick() }
        blocked(200)
        assertEquals(1_705, kbps())
    }

    @Test
    fun timeSpentIdleIsNotMeasured() {
        blocked(260)
        repeat(5) { quick() }
        // 보낼 것이 없어 쉬었다: 그 뒤에 처음 막히는 것은 다시 기준일 뿐이다.
        t += 900 * ms
        meter.idle()
        blocked(260)
        assertEquals(-1, kbps())
        repeat(5) { quick() }
        blocked(260)
        assertEquals(1_509, kbps())
    }

    @Test
    fun anOldMeasurementIsNotUsed() {
        blocked(260)
        repeat(5) { quick() }
        blocked(260)
        assertEquals(1_509, kbps())
        t += 1_501 * ms
        assertEquals(-1, kbps())
    }

    @Test
    fun aVeryLongIntervalIsNotASpeed() {
        blocked(260)
        repeat(5) { quick() }
        blocked(3_500) // 3초 넘게 막혀 있었다: 끊겼던 것이다
        assertEquals(-1, kbps())
        // 그 다음 간격부터 다시 잰다.
        repeat(5) { quick() }
        blocked(260)
        assertEquals(1_509, kbps())
    }

    @Test
    fun changingTheBufferSizeStartsOver() {
        blocked(260)
        repeat(5) { quick() }
        blocked(260)
        assertEquals(1_509, kbps())
        meter.restart()
        assertEquals(-1, kbps())
        blocked(2_000) // 버퍼가 새 크기로 줄어들 때까지 기다린 시간: 망 속도가 아니다
        assertEquals(-1, kbps())
        repeat(2) { quick() }
        blocked(130)
        assertEquals(1_510, kbps())
    }
}
