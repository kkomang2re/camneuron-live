package com.camneuron.live.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUrlsTest {
    @Test
    fun emptyMeansOff() {
        assertTrue(WidgetUrls.valid(""))
        assertTrue(WidgetUrls.valid("   "))
    }

    @Test
    fun httpAndHttps() {
        assertTrue(WidgetUrls.valid("https://example.com/widget/chat?k=abc"))
        assertTrue(WidgetUrls.valid("HTTP://127.0.0.1:8000/chat.html"))
    }

    @Test
    fun rejectsOtherSchemesAndBrokenUrls() {
        assertFalse(WidgetUrls.valid("javascript:alert(1)"))
        assertFalse(WidgetUrls.valid("file:///sdcard/x.html"))
        assertFalse(WidgetUrls.valid("example.com/chat"))
        assertFalse(WidgetUrls.valid("https://"))
        assertFalse(WidgetUrls.valid("https:///path"))
        assertFalse(WidgetUrls.valid("https://exa mple.com"))
    }
}
