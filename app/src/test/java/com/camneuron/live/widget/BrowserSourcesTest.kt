package com.camneuron.live.widget

import com.camneuron.core.encode.VideoEffects
import com.camneuron.core.overlay.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSourcesTest {
    private val toonation = OverlaySettings.DEFAULT_SOURCE.copy(name = "투네이션", url = "https://toon.at/widget/alertbox/x")
    private val goal = OverlaySettings.DEFAULT_SOURCE.copy(name = "목표", url = "https://example.com/goal")

    @Test
    fun slotsAreChatAlertThenSources() {
        val settings = OverlaySettings(sources = listOf(toonation, goal))
        val slots = BrowserSources.slots(settings)
        assertEquals(WidgetHost.COUNT, slots.size)
        assertSame(settings.chat, slots[WidgetHost.CHAT])
        assertSame(settings.alert, slots[WidgetHost.ALERT])
        assertSame(toonation, slots[WidgetHost.FIRST_SOURCE])
        assertSame(goal, slots[WidgetHost.FIRST_SOURCE + 1])
        assertNull(slots[WidgetHost.FIRST_SOURCE + 2])
    }

    @Test
    fun everySlotHasAnEncoderLayer() {
        assertTrue(WidgetHost.COUNT <= VideoEffects.MAX_LAYERS)
    }

    @Test
    fun updateTouchesOnlyThatSlot() {
        val settings = OverlaySettings(sources = listOf(toonation, goal))
        val moved = BrowserSources.update(settings, WidgetHost.FIRST_SOURCE + 1) { it.copy(x = 0.1f, zoom = 0.5f) }
        assertEquals(listOf(toonation, goal.copy(x = 0.1f, zoom = 0.5f)), moved.sources)
        assertEquals(settings.chat, moved.chat)

        val chat = BrowserSources.update(settings, WidgetHost.CHAT) { it.copy(zoom = 1.2f) }
        assertEquals(1.2f, chat.chat.zoom)
        assertEquals(settings.sources, chat.sources)
    }

    @Test
    fun updateOfMissingSourceIsIgnored() {
        val settings = OverlaySettings(sources = listOf(toonation))
        assertSame(settings, BrowserSources.update(settings, WidgetHost.FIRST_SOURCE + 3) { it.copy(x = 0f) })
    }

    @Test
    fun zoomStepsByTenPercentWithinLimits() {
        assertEquals(0.9f, BrowserSources.stepZoom(1f, -1), 1e-4f)
        assertEquals(1.1f, BrowserSources.stepZoom(1f, +1), 1e-4f)
        assertEquals(0.9f, BrowserSources.stepZoom(0.87f, 0), 1e-4f) // 10% 단위로 맞춘다
        assertEquals(BrowserSources.ZOOM_MIN, BrowserSources.stepZoom(0.3f, -1), 1e-4f)
        assertEquals(BrowserSources.ZOOM_MAX, BrowserSources.stepZoom(3f, +1), 1e-4f)
        assertEquals(80, BrowserSources.zoomPercent(BrowserSources.stepZoom(1f, -2)))
    }
}
