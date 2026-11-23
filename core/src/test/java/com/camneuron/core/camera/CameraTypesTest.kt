package com.camneuron.core.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTypesTest {
    @Test
    fun maxFpsRoundsTheFrameDuration() {
        assertEquals(30, maxFpsForFrameDuration(33_333_333, fallback = 60))
        assertEquals(60, maxFpsForFrameDuration(16_666_666, fallback = 30))
        assertEquals(60, maxFpsForFrameDuration(16_666_667, fallback = 30)) // 59.99…도 60으로
        assertEquals(24, maxFpsForFrameDuration(41_666_666, fallback = 60))
    }

    @Test
    fun unknownDurationUsesTheRequestedFps() {
        assertEquals(60, maxFpsForFrameDuration(0, fallback = 60))
        assertEquals(1, maxFpsForFrameDuration(5_000_000_000, fallback = 60))
    }
}
