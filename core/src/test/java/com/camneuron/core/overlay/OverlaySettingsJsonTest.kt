package com.camneuron.core.overlay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySettingsJsonTest {
    @Test
    fun savedSettingsComeBackTheSame() {
        val settings = OverlaySettings(
            chat = OverlaySettings.DEFAULT_CHAT.copy(url = "https://chzzk.example/chat", stream = true, zoom = 0.8f),
            sources = listOf(OverlaySettings.DEFAULT_SOURCE.copy(name = "투네이션", url = "https://toon.at/widget/alertbox/x")),
            texts = listOf(
                OverlaySettings.DEFAULT_TEXT.copy(text = "룰렛 있음\n1,000원부터", textColor = 0xFFFFE14D.toInt(), textBackground = true, x = 0.5f),
                OverlaySettings.DEFAULT_TEXT.copy(text = "폰에만", stream = false),
            ),
        )
        assertEquals(settings, OverlaySettings.fromJson(JSONObject(settings.toJson().toString())))
    }

    @Test
    fun settingsSavedBeforeTextSourcesHaveNoTexts() {
        val old = JSONObject()
            .put("chat", JSONObject().put("url", "https://chzzk.example/chat").put("x", 0.1))
            .put("sources", org.json.JSONArray().put(JSONObject().put("url", "https://example.com/a").put("name", "목표")))
        val settings = OverlaySettings.fromJson(old)
        assertEquals("https://chzzk.example/chat", settings.chat.url)
        assertEquals(0.1f, settings.chat.x, 1e-6f)
        assertEquals(OverlaySettings.DEFAULT_CHAT.y, settings.chat.y, 1e-6f)
        assertEquals("목표", settings.sources.single().name)
        assertEquals("", settings.sources.single().text)
        assertEquals(OverlayItem.DEFAULT_TEXT_COLOR, settings.sources.single().textColor)
        assertTrue(settings.texts.isEmpty())
        assertEquals(OverlaySettings.DEFAULT_ALERT, settings.alert)
    }

    @Test
    fun aSlotIsActiveWithAnAddressOrAText() {
        assertFalse(OverlaySettings.DEFAULT_TEXT.active)
        assertTrue(OverlaySettings.DEFAULT_TEXT.copy(text = "룰렛 있음").active)
        assertTrue(OverlaySettings.DEFAULT_SOURCE.copy(url = "https://example.com").active)
        assertFalse(OverlaySettings.DEFAULT_TEXT.copy(text = "  ").active)
    }
}
