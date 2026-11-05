package com.camneuron.core.overlay

import com.camneuron.core.overlay.OverlayGeometry.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {
    private fun assertBox(expected: Box, actual: Box) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.w, actual.w, 1e-5f)
        assertEquals(expected.h, actual.h, 1e-5f)
    }

    @Test
    fun moveKeepsSize() {
        assertBox(Box(0.3f, 0.2f, 0.2f, 0.3f), OverlayGeometry.dragged(Box(0.1f, 0.1f, 0.2f, 0.3f), 0.2f, 0.1f, resize = false))
    }

    @Test
    fun moveStopsAtEdges() {
        assertBox(Box(0.8f, 0f, 0.2f, 0.3f), OverlayGeometry.dragged(Box(0.5f, 0.1f, 0.2f, 0.3f), 0.9f, -0.5f, resize = false))
    }

    @Test
    fun resizeKeepsCorner() {
        assertBox(Box(0.1f, 0.1f, 0.4f, 0.5f), OverlayGeometry.dragged(Box(0.1f, 0.1f, 0.2f, 0.3f), 0.2f, 0.2f, resize = true))
    }

    @Test
    fun resizeHasMinimumAndMaximum() {
        val small = OverlayGeometry.dragged(Box(0.1f, 0.1f, 0.2f, 0.3f), -0.5f, -0.5f, resize = true)
        assertBox(Box(0.1f, 0.1f, OverlayGeometry.MIN_FRACTION, OverlayGeometry.MIN_FRACTION), small)
        // 오른쪽 끝을 넘게 키우면 크기는 1까지, 자리는 안으로 밀린다.
        assertBox(Box(0f, 0.1f, 1f, 0.3f), OverlayGeometry.dragged(Box(0.5f, 0.1f, 0.2f, 0.3f), 2f, 0f, resize = true))
    }
}
