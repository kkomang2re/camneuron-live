package com.camneuron.core.overlay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 치지직 채팅창·후원 알림 같은 웹 위젯 창 하나의 설정.
 * 위치와 크기(x, y, w, h)는 송출 영상 기준 0~1이라 폰 화면과 OBS 합성에 똑같이 쓴다.
 */
data class OverlayItem(
    val url: String = "",
    val visible: Boolean = true,
    /** true면 OBS가 같은 URL을 캠 화면 위에 합성해 송출한다. false면 폰에서만 보인다. */
    val stream: Boolean = false,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** 내용 배율 (CSS zoom) */
    val zoom: Float = 1f,
    val opacity: Float = 0.9f,
) {
    val active: Boolean get() = url.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("url", url).put("visible", visible).put("stream", stream)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("zoom", zoom.toDouble()).put("opacity", opacity.toDouble())

    companion object {
        fun fromJson(o: JSONObject?, default: OverlayItem): OverlayItem = if (o == null) default else OverlayItem(
            url = o.optString("url", default.url),
            visible = o.optBoolean("visible", default.visible),
            stream = o.optBoolean("stream", default.stream),
            x = o.optDouble("x", default.x.toDouble()).toFloat(),
            y = o.optDouble("y", default.y.toDouble()).toFloat(),
            w = o.optDouble("w", default.w.toDouble()).toFloat(),
            h = o.optDouble("h", default.h.toDouble()).toFloat(),
            zoom = o.optDouble("zoom", default.zoom.toDouble()).toFloat(),
            opacity = o.optDouble("opacity", default.opacity.toDouble()).toFloat(),
        )
    }
}

data class OverlaySettings(
    val chat: OverlayItem = DEFAULT_CHAT,
    val alert: OverlayItem = DEFAULT_ALERT,
    /** 폰 화면에서 영상이 그려지는 폭(dp = CSS px). OBS가 같은 모양으로 그리도록 함께 보낸다. */
    val referenceWidth: Float = DEFAULT_REFERENCE_WIDTH,
) {
    companion object {
        val DEFAULT_CHAT = OverlayItem(x = 0.70f, y = 0.05f, w = 0.28f, h = 0.60f)
        val DEFAULT_ALERT = OverlayItem(x = 0.25f, y = 0.05f, w = 0.50f, h = 0.35f)
        const val DEFAULT_REFERENCE_WIDTH = 720f
    }
}

/** 오버레이 설정을 저장하고 화면·세션에 알린다. 리스너는 바꾼 쪽 스레드에서 불린다. */
object OverlayStore {
    private const val PREFS = "overlays"
    private const val KEY = "settings"

    @Volatile
    var current = OverlaySettings()
        private set

    private val listeners = CopyOnWriteArraySet<(OverlaySettings) -> Unit>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        current = runCatching {
            val o = JSONObject(p.getString(KEY, null) ?: return@runCatching OverlaySettings())
            OverlaySettings(
                chat = OverlayItem.fromJson(o.optJSONObject("chat"), OverlaySettings.DEFAULT_CHAT),
                alert = OverlayItem.fromJson(o.optJSONObject("alert"), OverlaySettings.DEFAULT_ALERT),
                referenceWidth = o.optDouble("reference_width", OverlaySettings.DEFAULT_REFERENCE_WIDTH.toDouble()).toFloat(),
            )
        }.getOrDefault(OverlaySettings())
    }

    fun update(transform: (OverlaySettings) -> OverlaySettings) {
        val next = synchronized(this) {
            val next = transform(current)
            if (next == current) return
            current = next
            prefs?.edit()?.putString(
                KEY,
                JSONObject()
                    .put("chat", next.chat.toJson())
                    .put("alert", next.alert.toJson())
                    .put("reference_width", next.referenceWidth.toDouble())
                    .toString(),
            )?.apply()
            next
        }
        listeners.forEach { it(next) }
    }

    fun addListener(listener: (OverlaySettings) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (OverlaySettings) -> Unit) {
        listeners.remove(listener)
    }
}
