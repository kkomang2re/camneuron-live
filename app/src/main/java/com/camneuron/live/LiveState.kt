package com.camneuron.live

import android.os.Handler
import android.os.Looper
import com.camneuron.core.camera.StreamFormat
import java.util.concurrent.CopyOnWriteArraySet

/** 방송 상태. 세션 스레드가 바꾸고 화면·알림이 메인 스레드에서 받는다. */
object LiveState {
    enum class Phase { IDLE, CONNECTING, LIVE, RECONNECTING }

    data class Status(
        val phase: Phase = Phase.IDLE,
        /** 마지막 오류나 끊긴 이유 (화면에 그대로 보인다). */
        val message: String? = null,
        /** 방송을 시작한 시각 (SystemClock.elapsedRealtime). 다시 연결해도 유지한다. */
        val startedAtMs: Long = 0L,
        val format: StreamFormat? = null,
        val sendKbps: Int = 0,
        val targetKbps: Int = 0,
        val droppedFrames: Int = 0,
        val heatLevel: Int = 0,
        val netLevel: Int = 0,
        val micError: String? = null,
        /** 이 폰은 GPU 단계가 없어 화면 가리기를 할 수 없음. */
        val coverUnavailable: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()

    @Volatile
    var current = Status()
        private set

    @Synchronized
    fun update(transform: (Status) -> Status) {
        val next = transform(current)
        if (next == current) return
        current = next
        main.post { listeners.forEach { it(current) } }
    }

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }
}
