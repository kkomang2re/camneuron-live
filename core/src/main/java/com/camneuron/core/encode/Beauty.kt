package com.camneuron.core.encode

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 뷰티 보정 값, 각각 0~100 (프로 기능). [smooth]는 피부 보정(피부색인 곳만 경계를 살려 매끄럽게),
 * [bright]는 밝게(어두운 곳을 더 많이 올림). 0이면 끔.
 */
data class BeautySettings(val smooth: Int = 0, val bright: Int = 0) {
    fun clamped() = BeautySettings(smooth.coerceIn(0, MAX), bright.coerceIn(0, MAX))

    companion object {
        const val MAX = 100
        val OFF = BeautySettings()
    }
}

/** 뷰티 보정 설정을 저장하고 화면·세션에 알린다. 리스너는 바꾼 쪽 스레드에서 불린다. */
object BeautyStore {
    private const val PREFS = "beauty"
    private const val KEY_SMOOTH = "smooth"
    private const val KEY_BRIGHT = "bright"

    @Volatile
    var current = BeautySettings.OFF
        private set

    private val listeners = CopyOnWriteArraySet<(BeautySettings) -> Unit>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        current = BeautySettings(p.getInt(KEY_SMOOTH, 0), p.getInt(KEY_BRIGHT, 0)).clamped()
    }

    fun update(transform: (BeautySettings) -> BeautySettings) {
        val next = synchronized(this) {
            val next = transform(current).clamped()
            if (next == current) return
            current = next
            prefs?.edit()?.putInt(KEY_SMOOTH, next.smooth)?.putInt(KEY_BRIGHT, next.bright)?.apply()
            next
        }
        listeners.forEach { it(next) }
    }

    fun addListener(listener: (BeautySettings) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (BeautySettings) -> Unit) {
        listeners.remove(listener)
    }
}
