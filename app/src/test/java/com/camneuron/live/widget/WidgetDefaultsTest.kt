package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetDefaultsTest {
    private val coreDefault = OverlaySettings.DEFAULT_CHAT

    @Test
    fun firstUrlMovesCoreDefaultToTheLeft() {
        val placed = WidgetDefaults.placeChat(coreDefault, "https://example.com/chat")
        assertEquals(WidgetDefaults.CHAT.x, placed.x)
        assertEquals(WidgetDefaults.CHAT.y, placed.y)
        assertEquals(WidgetDefaults.CHAT.w, placed.w)
        assertEquals(WidgetDefaults.CHAT.h, placed.h)
    }

    @Test
    fun keepsAPositionTheUserChose() {
        val moved = coreDefault.copy(x = 0.5f)
        assertEquals(moved, WidgetDefaults.placeChat(moved, "https://example.com/chat"))
    }

    @Test
    fun keepsPositionWhenUrlWasAlreadySetOrStaysEmpty() {
        val active = coreDefault.copy(url = "https://example.com/old")
        assertEquals(active, WidgetDefaults.placeChat(active, "https://example.com/new"))
        assertEquals(coreDefault, WidgetDefaults.placeChat(coreDefault, ""))
    }
}
