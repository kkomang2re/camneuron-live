package com.camneuron.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private val rear = CameraCaps(
        front = false, hasFront = true, hasBack = true, zoomMin = 0.6f, zoomMax = 100f,
        evMin = -2f, evMax = 2f, evStep = 0.1f, manualFocus = true, torch = true, stabilization = true,
        whiteBalances = WhiteBalance.entries,
    )
    private val selfie = rear.copy(front = true, zoomMin = 1f, zoomMax = 8f, manualFocus = false, torch = false, stabilization = false)

    @Test
    fun switchingToTheRearCameraGoesToOneTimesZoomWithStabilizationOn() {
        // 전면에서 줌을 당겨 두었고, 전면에는 손떨림 보정이 없어 꺼져 있다.
        val onSelfie = CameraControls(front = false, zoom = 1.6f, ev = 0.5f, whiteBalance = WhiteBalance.CLOUDY, stabilization = false)
        val out = onSelfie.switchedTo(rear)
        assertEquals(1f, out.zoom, 0f)
        assertTrue(out.stabilization)
        // 나머지 값은 그대로.
        assertEquals(0.5f, out.ev, 0f)
        assertEquals(WhiteBalance.CLOUDY, out.whiteBalance)

        // 광각(0.6×)으로 쓰던 중이었어도 바꾸면 1×.
        assertEquals(1f, CameraControls(zoom = 0.6f).switchedTo(rear).zoom, 0f)
    }

    @Test
    fun rearCameraWithoutStabilizationStaysOff() {
        val out = CameraControls(zoom = 3f).switchedTo(rear.copy(stabilization = false, zoomMin = 1f, zoomMax = 4f))
        assertEquals(1f, out.zoom, 0f)
        assertFalse(out.stabilization)
    }

    @Test
    fun switchingToTheFrontCameraOnlyFitsItsLimits() {
        val onRear = CameraControls(front = true, zoom = 0.6f, torch = true, stabilization = true, ev = 1f)
        val out = onRear.switchedTo(selfie)
        assertEquals(1f, out.zoom, 0f) // 전면의 가장 넓은 화각
        assertFalse(out.torch)
        assertFalse(out.stabilization)
        assertEquals(1f, out.ev, 0f)
        // 전면에서 쓸 수 있는 줌은 그대로 둔다.
        assertEquals(2f, CameraControls(front = true, zoom = 2f).switchedTo(selfie).zoom, 0f)
    }

    @Test
    fun reopeningTheSameCameraKeepsWhatWasSet() {
        // 같은 카메라를 다시 열 때는 clampedTo만 쓴다: 맞춰 둔 줌과 꺼 둔 손떨림 보정이 그대로다.
        val set = CameraControls(zoom = 3f, stabilization = false)
        assertEquals(set, set.clampedTo(rear))
    }
}
