package com.camneuron.live

import java.util.concurrent.CopyOnWriteArraySet

/**
 * 방송 중에 바로 바꾸는 것: 마이크 끄기와 화면 가리기. 다시 연결해도 유지되게 세션 밖에 둔다.
 * 화면 버튼이 메인 스레드에서 바꾸고, 세션이 [addListener]로 받아 지금 인코더에 적용한다.
 */
object LiveControls {
    @Volatile
    var micMuted = false
        private set

    /** 시청자에게 "잠시 자리 비움" 화면을 보내고 소리도 끈다. */
    @Volatile
    var covered = false
        private set

    /** 실제로 무음을 보낼지: 마이크를 껐거나 화면을 가렸을 때. */
    val audioMuted: Boolean get() = micMuted || covered

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun setMicMuted(muted: Boolean) {
        if (micMuted == muted) return
        micMuted = muted
        listeners.forEach { it() }
    }

    fun setCovered(covered: Boolean) {
        if (this.covered == covered) return
        this.covered = covered
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
