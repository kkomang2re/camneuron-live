package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings

/**
 * 야외 방송 앱의 위젯 기본 자리. core의 기본값(캠뉴런, 채팅창이 오른쪽)은 이 앱에서는 오른쪽 버튼들에 가려
 * 방송하는 사람이 채팅을 못 본다. 채팅창은 왼쪽 상태 글자 아래, 줌 버튼에 닿지 않는 자리에 둔다.
 */
object WidgetDefaults {
    val CHAT = OverlayItem(x = 0.02f, y = 0.30f, w = 0.24f, h = 0.55f)

    /**
     * 채팅창 주소를 처음 넣을 때(전에 비어 있었음), 자리가 core 기본값 그대로면 이 앱의 기본 자리로 옮긴다.
     * 사람이 옮긴 자리는 건드리지 않는다.
     */
    fun placeChat(old: OverlayItem, newUrl: String): OverlayItem {
        val first = !old.active && newUrl.isNotBlank()
        val d = OverlaySettings.DEFAULT_CHAT
        val untouched = old.x == d.x && old.y == d.y && old.w == d.w && old.h == d.h
        return if (first && untouched) old.copy(x = CHAT.x, y = CHAT.y, w = CHAT.w, h = CHAT.h) else old
    }
}
