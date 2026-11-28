package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoClockTest {
    // 부팅 뒤 1시간 잠들어 있던 폰: BOOTTIME이 MONOTONIC보다 1시간 앞선다.
    private val mono = 500_000_000L
    private val boot = mono + 3_600_000_000L
    private val latency = 80_000L

    @Test
    fun monotonicCameraIsLeftAlone() {
        val clock = VideoClock()
        assertEquals(mono - latency, clock.toMonotonic(mono - latency, mono, boot))
    }

    @Test
    fun boottimeCameraIsMovedToMonotonic() {
        val clock = VideoClock()
        assertEquals(mono - latency, clock.toMonotonic(boot - latency, mono, boot))
        // 다음 프레임도 같은 만큼 옮긴다 (프레임 간격은 카메라가 찍은 그대로)
        assertEquals(mono - latency + 33_333, clock.toMonotonic(boot - latency + 33_333, mono + 33_000, boot + 33_000))
    }

    @Test
    fun unknownClockIsAnchoredToNow() {
        val clock = VideoClock()
        val odd = 77_000_000_000_000L // 어느 시계와도 맞지 않는다
        assertEquals(mono, clock.toMonotonic(odd, mono, boot))
        assertEquals(mono + 33_333, clock.toMonotonic(odd + 33_333, mono + 40_000, boot + 40_000))
    }

    @Test
    fun switchingToACameraWithAnotherClockDoesNotJump() {
        val clock = VideoClock()
        // 후면 카메라: BOOTTIME
        val last = clock.toMonotonic(boot - latency, mono, boot)
        // 1.2초 뒤 전면 카메라로 바꿨는데 그 카메라는 MONOTONIC으로 찍는다. 다시 정하지 않으면 1시간 뒤로 뛴다.
        clock.reset()
        val later = 1_200_000L
        val next = clock.toMonotonic(mono + later - latency, mono + later, boot + later)
        assertEquals(later, next - last)
    }

    @Test
    fun withoutSleepBothClocksAgree() {
        val clock = VideoClock()
        assertEquals(mono - latency, clock.toMonotonic(mono - latency, mono, mono))
    }

    @Test
    fun closerClockWinsWhenTheyAreNear() {
        // 1.5초만 잠들어 있던 폰의 BOOTTIME 카메라: 둘 다 2초 안이지만 BOOTTIME이 더 가깝다.
        val nearBoot = mono + 1_500_000L
        assertEquals(mono - latency, VideoClock().toMonotonic(nearBoot - latency, mono, nearBoot))
    }
}
