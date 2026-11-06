package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings
import com.camneuron.live.widget.WidgetLayout.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutTest {
    private val source = OverlaySettings.DEFAULT_SOURCE
    private val toonationAlert = source.copy(url = "https://toon.at/widget/alertbox/abc")
    private val twipAlert = source.copy(url = "https://twip.kr/widgets/alertbox/abc")
    private val goal = source.copy(name = "목표", url = "https://toon.at/widget/goal/abc")
    private val toonationChat = source.copy(url = "https://toon.at/widget/chatbox/abc")
    private val chzzkChat = OverlaySettings.DEFAULT_CHAT.copy(url = "https://chzzk.example/chat")
    private val chzzkAlert = OverlaySettings.DEFAULT_ALERT.copy(url = "https://chzzk.example/donation")

    private fun OverlayItem.spot() = WidgetLayout.Spot(x, y, w, h, zoom)

    @Test
    fun guessesKindFromNameThenUrl() {
        val first = WidgetHost.FIRST_SOURCE
        assertEquals(Kind.CHAT, WidgetLayout.kind(WidgetHost.CHAT, chzzkChat))
        assertEquals(Kind.ALERT, WidgetLayout.kind(WidgetHost.ALERT, chzzkAlert))
        assertEquals(Kind.ALERT, WidgetLayout.kind(first, toonationAlert))
        assertEquals(Kind.ALERT, WidgetLayout.kind(first, twipAlert))
        assertEquals(Kind.CHAT, WidgetLayout.kind(first, toonationChat))
        assertEquals(Kind.OTHER, WidgetLayout.kind(first, goal))
        assertEquals(Kind.ALERT, WidgetLayout.kind(first, source.copy(name = "룰렛", url = "https://example.com/x")))
        assertEquals(Kind.ALERT, WidgetLayout.kind(first, source.copy(name = "후원 알림", url = "https://example.com/chat")))
        assertEquals(Kind.CHAT, WidgetLayout.kind(first, source.copy(name = "Chat", url = "https://example.com/x")))
    }

    @Test
    fun arrangesTypicalCamLayout() {
        val settings = OverlaySettings(
            chat = chzzkChat.copy(x = 0.7f, zoom = 1.5f),
            alert = chzzkAlert,
            sources = listOf(toonationAlert, goal, toonationChat),
        )
        val out = WidgetLayout.arrange(settings)
        assertEquals(WidgetLayout.CHAT, out.chat.spot())
        assertEquals(WidgetLayout.ALERT, out.alert.spot())
        assertEquals(WidgetLayout.ALERT, out.sources[0].spot()) // 알림은 한 자리를 같이 쓴다
        assertEquals(WidgetLayout.OTHERS[0], out.sources[1].spot())
        assertEquals(WidgetLayout.CHAT_SECOND, out.sources[2].spot()) // 두 번째 채팅은 오른쪽
        // 주소·방송 넣기·이름은 그대로
        assertEquals(settings.sources.map { it.url to it.name }, out.sources.map { it.url to it.name })
        assertEquals(settings.chat.stream, out.chat.stream)
    }

    @Test
    fun leavesEmptyWidgetsAlone() {
        val settings = OverlaySettings(chat = chzzkChat)
        val out = WidgetLayout.arrange(settings)
        assertEquals(settings.alert, out.alert)
        assertEquals(WidgetLayout.CHAT, out.chat.spot())
    }

    @Test
    fun spotsStayInsideTheVideoAndKeepTheCentreClear() {
        val all = listOf(WidgetLayout.CHAT, WidgetLayout.CHAT_SECOND, WidgetLayout.ALERT) + WidgetLayout.OTHERS
        for (s in all) {
            assertTrue(s.x >= 0f && s.y >= 0f && s.x + s.w <= 1f && s.y + s.h <= 1f)
        }
        // 화면 한가운데 (사람 얼굴 자리) 는 비운다
        val centre = all.filter { it.x < 0.5f && it.x + it.w > 0.5f && it.y < 0.5f && it.y + it.h > 0.5f }
        assertTrue(centre.isEmpty())
        // 왼쪽 채팅 칸과 아래쪽 칸은 겹치지 않는다
        for (o in WidgetLayout.OTHERS) assertTrue(o.x >= WidgetLayout.CHAT.x + WidgetLayout.CHAT.w)
    }

    @Test
    fun placeNewMovesOnlyNewWidgets() {
        val moved = toonationAlert.copy(x = 0.05f, y = 0.05f)
        val old = OverlaySettings(sources = listOf(moved))
        val next = old.copy(
            chat = old.chat.copy(url = chzzkChat.url),
            sources = listOf(moved, goal),
        )
        val out = WidgetLayout.placeNew(old, next, newSlots = setOf(WidgetHost.FIRST_SOURCE + 1))
        assertEquals(moved, out.sources[0]) // 사람이 옮긴 것은 그대로
        assertEquals(WidgetLayout.OTHERS[0], out.sources[1].spot())
        assertEquals(WidgetLayout.CHAT, out.chat.spot()) // 처음 넣은 채팅창 (core 기본 자리였음)
        assertEquals(old.alert, out.alert) // 주소 없음
    }

    @Test
    fun placeNewKeepsChatTheUserAlreadyPlaced() {
        val placed = OverlaySettings.DEFAULT_CHAT.copy(x = 0.5f)
        val old = OverlaySettings(chat = placed)
        val next = old.copy(chat = placed.copy(url = chzzkChat.url))
        assertEquals(next, WidgetLayout.placeNew(old, next, emptySet()))

        val active = OverlaySettings(chat = chzzkChat)
        val changedUrl = active.copy(chat = chzzkChat.copy(url = "https://chzzk.example/new"))
        assertEquals(changedUrl, WidgetLayout.placeNew(active, changedUrl, emptySet()))
    }

    @Test
    fun restoreBringsBackPositionsOnly() {
        val before = OverlaySettings(chat = chzzkChat.copy(x = 0.6f, zoom = 1.3f), sources = listOf(goal.copy(y = 0.1f)))
        val arranged = WidgetLayout.arrange(before)
        assertFalse(arranged == before)
        val renamed = arranged.copy(sources = listOf(arranged.sources[0].copy(name = "새 이름")))
        val back = WidgetLayout.restore(renamed, before)
        assertEquals(before.chat, back.chat)
        assertEquals(before.sources[0].copy(name = "새 이름"), back.sources[0])
    }
}
