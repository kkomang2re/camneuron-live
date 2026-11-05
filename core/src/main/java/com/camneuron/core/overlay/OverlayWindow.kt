package com.camneuron.core.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.camneuron.core.R
import kotlin.math.roundToInt

/**
 * 웹 위젯(치지직 채팅창, 후원 알림)을 띄우는 떠 있는 창.
 * 편집 모드에서는 위쪽 막대를 끌어 옮기고 오른쪽 아래 모서리를 끌어 크기를 바꾼다.
 * 위치·크기는 영상 영역 기준 0~1로 [onMoved]에 알린다.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled", "ClickableViewAccessibility")
class OverlayWindow(
    context: Context,
    title: String,
    private val onMoved: (x: Float, y: Float, w: Float, h: Float) -> Unit,
) : FrameLayout(context) {
    private val webView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) = applyZoom()
        }
    }
    private val bar = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setBackgroundColor(0xAA000000.toInt())
    }
    private val resizeHandle = View(context).apply { setBackgroundResource(R.drawable.resize_handle) }
    private val border = View(context).apply { setBackgroundResource(R.drawable.overlay_border) }

    /** 영상이 그려지는 영역 (부모 좌표). 위치를 0~1로 바꾸는 기준. */
    private var image = RectF()
    private var item: OverlayItem? = null
    private var loadedUrl: String? = null
    private var zoom = 1f

    var editable: Boolean = true
        set(value) {
            field = value
            val visibility = if (value) View.VISIBLE else View.GONE
            bar.visibility = visibility
            resizeHandle.visibility = visibility
            border.visibility = visibility
        }

    init {
        visibility = View.GONE // URL과 영상 영역이 정해진 뒤 bind()에서 보인다
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(border, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(BAR_DP)))
        addView(resizeHandle, LayoutParams(dp(HANDLE_DP), dp(HANDLE_DP), Gravity.BOTTOM or Gravity.END))
        bar.setOnTouchListener(DragListener(resize = false))
        resizeHandle.setOnTouchListener(DragListener(resize = true))
    }

    /** 설정과 영상 영역이 바뀔 때마다 부른다. */
    fun bind(item: OverlayItem, image: RectF, streamed: String) {
        this.item = item
        this.image = image
        bar.text = streamed
        visibility = if (item.active && item.visible) View.VISIBLE else View.GONE
        alpha = item.opacity
        if (item.url != loadedUrl && item.active) {
            loadedUrl = item.url
            webView.loadUrl(item.url)
        }
        if (item.zoom != zoom) {
            zoom = item.zoom
            applyZoom()
        }
        layoutAt(item.x, item.y, item.w, item.h)
    }

    fun reload() {
        loadedUrl?.let { webView.loadUrl(it) }
    }

    fun destroy() {
        webView.destroy()
    }

    private fun applyZoom() {
        webView.evaluateJavascript("document.documentElement.style.zoom='$zoom'", null)
    }

    private fun layoutAt(x: Float, y: Float, w: Float, h: Float) {
        val params = (layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
        params.leftMargin = (image.left + x * image.width()).roundToInt()
        params.topMargin = (image.top + y * image.height()).roundToInt()
        params.width = (w * image.width()).roundToInt().coerceAtLeast(dp(MIN_DP))
        params.height = (h * image.height()).roundToInt().coerceAtLeast(dp(MIN_DP))
        layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /** 막대를 끌면 이동, 모서리를 끌면 크기 변경. 손을 떼면 0~1 좌표로 알린다. */
    private inner class DragListener(private val resize: Boolean) : OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var start = RectF()

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val current = item ?: return false
            if (image.width() <= 0f) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    start = RectF(current.x, current.y, current.x + current.w, current.y + current.h)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val dx = (event.rawX - startX) / image.width()
                    val dy = (event.rawY - startY) / image.height()
                    val rect = if (resize) {
                        RectF(start.left, start.top, start.right + dx, start.bottom + dy)
                    } else {
                        RectF(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
                    }
                    val w = rect.width().coerceIn(MIN_FRACTION, 1f)
                    val h = rect.height().coerceIn(MIN_FRACTION, 1f)
                    val x = rect.left.coerceIn(0f, 1f - w)
                    val y = rect.top.coerceIn(0f, 1f - h)
                    layoutAt(x, y, w, h)
                    if (event.actionMasked == MotionEvent.ACTION_UP) onMoved(x, y, w, h)
                }
            }
            return true
        }
    }

    private companion object {
        const val BAR_DP = 22
        const val HANDLE_DP = 26
        const val MIN_DP = 60
        const val MIN_FRACTION = 0.08f
    }
}
