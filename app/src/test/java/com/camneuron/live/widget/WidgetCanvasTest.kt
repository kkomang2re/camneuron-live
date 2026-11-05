package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCanvasTest {
    @Test
    fun defaultChatBoxOn1080Canvas() {
        // x 0.70, y 0.05, w 0.28, h 0.60
        assertEquals(WidgetCanvas.PixelRect(1344, 54, 538, 648), WidgetCanvas.pixelRect(OverlaySettings.DEFAULT_CHAT))
    }

    @Test
    fun clampsToCanvas() {
        val item = OverlayItem(x = 0.9f, y = 0.95f, w = 0.5f, h = 0.5f)
        assertEquals(WidgetCanvas.PixelRect(1728, 1026, 192, 54), WidgetCanvas.pixelRect(item))
        val outside = OverlayItem(x = 1.2f, y = -0.1f, w = 0f, h = 2f)
        assertEquals(WidgetCanvas.PixelRect(1919, 0, 1, 1080), WidgetCanvas.pixelRect(outside))
    }

    @Test
    fun streamedNeedsUrlAndSwitch() {
        val base = OverlayItem(x = 0f, y = 0f, w = 0.5f, h = 0.5f)
        assertFalse(WidgetCanvas.streamed(base.copy(stream = true)))
        assertFalse(WidgetCanvas.streamed(base.copy(url = "https://example.com/chat")))
        assertTrue(WidgetCanvas.streamed(base.copy(url = "https://example.com/chat", stream = true)))
    }
}
