package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSourcesTest {
    @Test
    fun linesAreTrimmedAndBlankOnesDropped() {
        assertEquals(listOf("룰렛 있음", "1,000원부터"), TextSources.lines("  룰렛 있음 \n\n 1,000원부터\n"))
        assertTrue(TextSources.lines("  \n ").isEmpty())
        assertEquals(TextSources.MAX_LINES, TextSources.lines("1\n2\n3\n4\n5\n6").size)
    }

    @Test
    fun cleanKeepsOnlyWhatIsDrawn() {
        assertEquals("룰렛 있음\n1,000원부터", TextSources.clean(" 룰렛 있음 \n\n1,000원부터 \n"))
        assertEquals("", TextSources.clean(" \n "))
        assertEquals(TextSources.MAX_CHARS, TextSources.clean("가".repeat(200)).length)
    }

    @Test
    fun labelIsTheFirstLineShortened() {
        assertEquals("룰렛 있음", TextSources.label("룰렛 있음\n1,000원부터"))
        assertEquals("가나다라마바사아자차카타파…", TextSources.label("가나다라마바사아자차카타파하거너더"))
        assertEquals("", TextSources.label(""))
    }

    @Test
    fun textFillsTheBoxByWhicheverSideIsReachedFirst() {
        // 가로로 긴 칸(기본 자리: 461x108)에 한글 다섯 자쯤: 높이에 먼저 닿는다.
        val wide = TextSources.fit(461, 108, lineCount = 1, widestPerSize = 4.2f, background = false)
        assertEquals(108f, wide.blockHeight, 0.01f)
        assertTrue(wide.blockWidth < 461f)
        assertEquals(wide.size * TextSources.LINE_HEIGHT, wide.lineHeight, 0.001f)
        assertTrue(wide.size in 70f..85f)

        // 긴 문구: 폭에 먼저 닿는다.
        val long = TextSources.fit(461, 108, lineCount = 1, widestPerSize = 12f, background = false)
        assertEquals(461f, long.blockWidth, 0.01f)
        assertTrue(long.blockHeight < 108f)
        assertEquals(12f * long.size, long.textWidth, 0.01f)
    }

    @Test
    fun boxTwiceAsBigGivesTextTwiceAsBig() {
        val small = TextSources.fit(400, 100, 2, 5f, background = true)
        val big = TextSources.fit(800, 200, 2, 5f, background = true)
        assertEquals(small.size * 2, big.size, 0.01f)
        assertEquals(small.outline * 2, big.outline, 0.01f)
    }

    @Test
    fun moreLinesAndABackgroundMakeTextSmaller() {
        val one = TextSources.fit(461, 108, 1, 4f, background = false)
        val two = TextSources.fit(461, 108, 2, 4f, background = false)
        val backed = TextSources.fit(461, 108, 1, 4f, background = true)
        assertTrue(two.size < one.size)
        assertTrue(backed.size < one.size)
        assertTrue(backed.padX > one.padX)
        // 어느 쪽이든 칸을 넘지 않는다.
        for (fit in listOf(one, two, backed)) {
            assertTrue(fit.blockWidth <= 461.01f && fit.blockHeight <= 108.01f)
        }
    }

    @Test
    fun colorsStartWithTheDefaultAndAreOpaque() {
        assertEquals(OverlayItem.DEFAULT_TEXT_COLOR, TextSources.COLORS.first())
        assertTrue(TextSources.COLORS.all { it ushr 24 == 0xFF })
        assertEquals(TextSources.COLORS.size, TextSources.COLORS.toSet().size)
    }

    @Test
    fun newTextGoesToTheFirstSpotNotTakenByAnotherText() {
        val first = TextSources.create(emptyList(), " 룰렛 있음 ", TextSources.COLORS[1], background = true, stream = true)
        assertEquals("룰렛 있음", first.text)
        assertEquals(TextSources.COLORS[1], first.textColor)
        assertTrue(first.textBackground && first.stream && first.active)
        assertEquals(WidgetLayout.TEXTS[0], WidgetLayout.Spot(first.x, first.y, first.w, first.h))
        assertEquals("", first.url)

        val second = TextSources.create(listOf(first), "후원 감사", TextSources.COLORS[0], background = false, stream = false)
        assertEquals(WidgetLayout.TEXTS[1], WidgetLayout.Spot(second.x, second.y, second.w, second.h))
        assertFalse(second.stream)

        // 첫 글자를 다른 데로 옮겼으면 첫 자리가 다시 빈다.
        val moved = first.copy(x = 0.4f, y = 0.5f)
        assertEquals(WidgetLayout.TEXTS[0], TextSources.freeSpot(listOf(moved)))
        // 자리가 모두 찼으면 돌려 쓴다.
        val taken = WidgetLayout.TEXTS.map { OverlaySettings.DEFAULT_TEXT.copy(text = "가", x = it.x, y = it.y, w = it.w, h = it.h) }
        assertEquals(WidgetLayout.TEXTS[0], TextSources.freeSpot(taken))
    }
}
