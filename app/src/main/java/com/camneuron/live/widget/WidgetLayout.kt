package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings

/**
 * 캠 방송에 알맞은 위젯 자리 (자동 배치). 처음 하는 사람도 주소만 넣으면 알맞은 자리에 놓이고,
 * "자동 배치"를 누르면 한 번에 다시 놓인다. 좌표는 송출 영상 기준 0~1 (16:9).
 *
 * 화면 가운데(얼굴·사람)는 비워 둔다.
 * - 채팅창: 왼쪽 세로 칸. 방송하는 사람의 폰에서는 오른쪽이 버튼에 가려 채팅을 못 보므로 왼쪽에 둔다.
 *   폰의 상태 글자 아래, 줌 버튼 위에서 끝난다. 두 번째 채팅(투네이션 채팅 등)은 오른쪽 세로 칸.
 * - 후원 알림(룰렛 포함): 위 가운데. 잠깐 떴다 사라지므로 여러 개가 한 자리를 같이 써도 된다.
 *   OBS용 알림(보통 800x600)이 잘리지 않고 얼굴을 덜 가리게 내용을 70%로 줄인다.
 * - 그 밖(목표 막대 등): 아래쪽, 채팅 칸을 피해 오른쪽부터 차례로.
 */
object WidgetLayout {
    enum class Kind { CHAT, ALERT, OTHER }

    /** 위젯 하나의 자리와 내용 배율. */
    data class Spot(val x: Float, val y: Float, val w: Float, val h: Float, val zoom: Float = 1f)

    val CHAT = Spot(x = 0.02f, y = 0.30f, w = 0.24f, h = 0.55f)
    val CHAT_SECOND = Spot(x = 0.74f, y = 0.30f, w = 0.24f, h = 0.48f)
    val ALERT = Spot(x = 0.28f, y = 0.03f, w = 0.44f, h = 0.40f, zoom = 0.7f)
    val OTHERS = listOf(
        Spot(x = 0.64f, y = 0.80f, w = 0.34f, h = 0.17f),
        Spot(x = 0.28f, y = 0.80f, w = 0.34f, h = 0.17f),
        Spot(x = 0.64f, y = 0.62f, w = 0.34f, h = 0.17f),
        Spot(x = 0.28f, y = 0.62f, w = 0.34f, h = 0.17f),
    )

    private val ALERT_WORDS = listOf("alert", "donation", "donate", "roulette", "후원", "알림", "도네", "룰렛")
    private val CHAT_WORDS = listOf("chat", "채팅")

    /** 칸이 어떤 위젯인지. 브라우저 소스는 이름을 먼저, 없으면 주소를 본다 (예: toon.at/widget/alertbox → 후원 알림). */
    fun kind(index: Int, item: OverlayItem): Kind = when (index) {
        WidgetHost.CHAT -> Kind.CHAT
        WidgetHost.ALERT -> Kind.ALERT
        else -> guess(item.name) ?: guess(item.url) ?: Kind.OTHER
    }

    private fun guess(text: String): Kind? {
        val t = text.lowercase()
        return when {
            ALERT_WORDS.any { it in t } -> Kind.ALERT
            CHAT_WORDS.any { it in t } -> Kind.CHAT
            else -> null
        }
    }

    /** 주소가 있는 칸마다의 알맞은 자리 (칸 번호 → 자리). 같은 종류는 칸 번호 순서로 자리를 받는다. */
    fun spots(settings: OverlaySettings): Map<Int, Spot> {
        var chats = 0
        var others = 0
        val result = LinkedHashMap<Int, Spot>()
        BrowserSources.slots(settings).forEachIndexed { index, item ->
            if (item == null || !item.active) return@forEachIndexed
            result[index] = when (kind(index, item)) {
                Kind.CHAT -> if (chats++ == 0) CHAT else CHAT_SECOND
                Kind.ALERT -> ALERT
                Kind.OTHER -> OTHERS[others++ % OTHERS.size]
            }
        }
        return result
    }

    /** "자동 배치": 주소가 있는 위젯을 모두 알맞은 자리로 옮긴다. 주소·방송 넣기는 그대로. */
    fun arrange(settings: OverlaySettings): OverlaySettings =
        spots(settings).entries.fold(settings) { out, (index, spot) -> BrowserSources.update(out, index) { it.at(spot) } }

    /**
     * 설정을 저장할 때 새로 생긴 위젯만 알맞은 자리에 놓는다. 사람이 옮겨 둔 것은 건드리지 않는다.
     * 채팅창·후원 알림은 전에 주소가 없었고 자리가 core 기본값 그대로일 때, 브라우저 소스는 [newSlots]에 든 칸 번호일 때 새것이다.
     */
    fun placeNew(old: OverlaySettings, next: OverlaySettings, newSlots: Set<Int>): OverlaySettings =
        spots(next).entries.fold(next) { out, (index, spot) ->
            val fresh = when (index) {
                WidgetHost.CHAT -> untouched(old.chat, OverlaySettings.DEFAULT_CHAT)
                WidgetHost.ALERT -> untouched(old.alert, OverlaySettings.DEFAULT_ALERT)
                else -> index in newSlots
            }
            if (fresh) BrowserSources.update(out, index) { it.at(spot) } else out
        }

    /** [saved]의 자리·배율로 되돌린다 ("자동 배치" 되돌리기). 주소 등 나머지는 지금 것을 쓴다. */
    fun restore(current: OverlaySettings, saved: OverlaySettings): OverlaySettings =
        BrowserSources.slots(saved).foldIndexed(current) { index, out, item ->
            if (item == null) out else BrowserSources.update(out, index) { it.copy(x = item.x, y = item.y, w = item.w, h = item.h, zoom = item.zoom) }
        }

    private fun untouched(old: OverlayItem, default: OverlayItem): Boolean =
        !old.active && old.x == default.x && old.y == default.y && old.w == default.w && old.h == default.h

    private fun OverlayItem.at(spot: Spot) = copy(x = spot.x, y = spot.y, w = spot.w, h = spot.h, zoom = spot.zoom)
}
