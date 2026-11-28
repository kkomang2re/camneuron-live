package com.camneuron.core.overlay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 치지직 채팅창·후원 알림·브라우저 소스 같은 웹 위젯 창 하나, 또는 글자 소스 하나의 설정.
 * 위치와 크기(x, y, w, h)는 송출 영상 기준 0~1이라 폰 화면과 OBS 합성에 똑같이 쓴다.
 */
data class OverlayItem(
    val url: String = "",
    /**
     * 켜 둔 것인지. 끄면 (false) 주소·문구·자리는 그대로 두고 화면과 방송에서만 뺀다: 웹 위젯은 페이지도 닫아 소리가 나지
     * 않고 폰도 덜 데운다. 방송 중에도 하나씩 켜고 끌 수 있다.
     */
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
    /** 화면에 보일 이름 (브라우저 소스). 채팅창·후원 알림은 비워 둔다. */
    val name: String = "",
    /** 글자 소스: 방송 화면에 넣을 문구 (줄을 바꿀 수 있다). 웹 위젯은 비워 둔다. */
    val text: String = "",
    /** 글자 색 (ARGB). */
    val textColor: Int = DEFAULT_TEXT_COLOR,
    /** 글자 뒤에 반투명한 어두운 바탕을 깐다. */
    val textBackground: Boolean = false,
) {
    /** 쓰는 칸인지: 웹 위젯은 주소가, 글자 소스는 문구가 있을 때. */
    val active: Boolean get() = url.isNotBlank() || text.isNotBlank()

    /** 지금 켜져 있는지: 쓰는 칸이고 꺼 두지 않았을 때. */
    val on: Boolean get() = active && visible

    fun toJson(): JSONObject = JSONObject()
        .put("url", url).put("visible", visible).put("stream", stream)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("zoom", zoom.toDouble()).put("opacity", opacity.toDouble())
        .put("name", name)
        .put("text", text).put("text_color", textColor).put("text_background", textBackground)

    companion object {
        const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()

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
            name = o.optString("name", default.name),
            text = o.optString("text", default.text),
            textColor = o.optInt("text_color", default.textColor),
            textBackground = o.optBoolean("text_background", default.textBackground),
        )
    }
}

data class OverlaySettings(
    val chat: OverlayItem = DEFAULT_CHAT,
    val alert: OverlayItem = DEFAULT_ALERT,
    /** 사람이 더한 브라우저 소스 (투네이션·트윕 알림, 목표 막대 등). 그리는 순서대로. */
    val sources: List<OverlayItem> = emptyList(),
    /** 폰 화면에서 영상이 그려지는 폭(dp = CSS px). OBS가 같은 모양으로 그리도록 함께 보낸다. */
    val referenceWidth: Float = DEFAULT_REFERENCE_WIDTH,
    /** 사람이 넣은 글자 소스 ("룰렛 있음" 같은 문구). 웹 위젯들 위에 그리는 순서대로. */
    val texts: List<OverlayItem> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("chat", chat.toJson())
        .put("alert", alert.toJson())
        .put("sources", JSONArray(sources.map { it.toJson() }))
        .put("texts", JSONArray(texts.map { it.toJson() }))
        .put("reference_width", referenceWidth.toDouble())

    companion object {
        /** 저장해 둔 것을 읽는다. 없는 값은 기본값으로 (옛 버전이 저장한 것에는 글자 소스가 없다). */
        fun fromJson(o: JSONObject): OverlaySettings = OverlaySettings(
            chat = OverlayItem.fromJson(o.optJSONObject("chat"), DEFAULT_CHAT),
            alert = OverlayItem.fromJson(o.optJSONObject("alert"), DEFAULT_ALERT),
            sources = items(o.optJSONArray("sources"), DEFAULT_SOURCE),
            referenceWidth = o.optDouble("reference_width", DEFAULT_REFERENCE_WIDTH.toDouble()).toFloat(),
            texts = items(o.optJSONArray("texts"), DEFAULT_TEXT),
        )

        private fun items(array: JSONArray?, default: OverlayItem): List<OverlayItem> =
            if (array == null) emptyList() else (0 until array.length()).map { OverlayItem.fromJson(array.optJSONObject(it), default) }

        val DEFAULT_CHAT = OverlayItem(x = 0.70f, y = 0.05f, w = 0.28f, h = 0.60f)
        val DEFAULT_ALERT = OverlayItem(x = 0.25f, y = 0.05f, w = 0.50f, h = 0.35f)
        const val DEFAULT_REFERENCE_WIDTH = 720f

        /** 새 브라우저 소스의 자리 (OBS 알림 위젯에 흔한 800x600 정도, 가운데). */
        val DEFAULT_SOURCE = OverlayItem(stream = true, x = 0.29f, y = 0.22f, w = 0.42f, h = 0.56f, zoom = 1f, opacity = 1f)

        /** 새 글자 소스의 자리 (왼쪽 위의 가로로 긴 칸). 글자는 이 칸에 꽉 차게 그려진다. */
        val DEFAULT_TEXT = OverlayItem(stream = true, x = 0.02f, y = 0.04f, w = 0.24f, h = 0.10f, zoom = 1f, opacity = 1f)
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
            OverlaySettings.fromJson(JSONObject(p.getString(KEY, null) ?: return@runCatching OverlaySettings()))
        }.getOrDefault(OverlaySettings())
    }

    fun update(transform: (OverlaySettings) -> OverlaySettings) {
        val next = synchronized(this) {
            val next = transform(current)
            if (next == current) return
            current = next
            prefs?.edit()?.putString(KEY, next.toJson().toString())?.apply()
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
