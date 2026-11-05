package com.camneuron.live.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.camneuron.core.overlay.OverlayGeometry
import com.camneuron.core.overlay.OverlayItem
import kotlin.math.roundToInt

/**
 * 미리보기 위에 위젯 그림([WidgetHost]가 뜬 것)을 시청자 화면과 같은 자리에 보인다.
 * [editable]이면 위쪽 막대를 끌어 옮기고 오른쪽 아래 모서리를 끌어 크기를 바꾼다. 손을 떼면 [onMoved]로 0~1 좌표를 알린다.
 * 편집 중이 아니면 터치를 받지 않아 아래 미리보기(초점·줌)가 그대로 동작한다.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class WidgetFrame(
    context: Context,
    private val title: String,
    private val onMoved: (x: Float, y: Float, w: Float, h: Float) -> Unit,
) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val bar = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setBackgroundColor(0xAA000000.toInt())
    }
    private val resizeHandle = View(context).apply { setBackgroundResource(com.camneuron.core.R.drawable.resize_handle) }
    private val border = View(context).apply { setBackgroundResource(com.camneuron.core.R.drawable.overlay_border) }

    /** 영상이 그려지는 영역 (부모 좌표). */
    private var imageRect = RectF()
    private var item: OverlayItem? = null

    var editable: Boolean = false
        set(value) {
            field = value
            val chrome = if (value) View.VISIBLE else View.GONE
            bar.visibility = chrome
            resizeHandle.visibility = chrome
            border.visibility = chrome
            updateVisibility()
        }

    init {
        visibility = View.GONE
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(border, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(BAR_DP)))
        addView(resizeHandle, LayoutParams(dp(HANDLE_DP), dp(HANDLE_DP), Gravity.BOTTOM or Gravity.END))
        bar.setOnTouchListener(DragListener(resize = false))
        resizeHandle.setOnTouchListener(DragListener(resize = true))
        editable = false
    }

    /** 설정이나 영상 영역이 바뀔 때. [streamed]는 막대에 보일 "방송에 나감/폰에만" 글자. */
    fun bind(item: OverlayItem, imageRect: RectF, streamed: String) {
        this.item = item
        this.imageRect = imageRect
        bar.text = "$title · $streamed"
        layoutAt(item.x, item.y, item.w, item.h)
        updateVisibility()
    }

    fun setBitmap(bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        updateVisibility()
    }

    private fun updateVisibility() {
        val active = item?.active == true && imageRect.width() > 0f
        visibility = if (active && (editable || image.drawable != null)) View.VISIBLE else View.GONE
    }

    private fun layoutAt(x: Float, y: Float, w: Float, h: Float) {
        val params = (layoutParams as? LayoutParams) ?: LayoutParams(0, 0)
        params.leftMargin = (imageRect.left + x * imageRect.width()).roundToInt()
        params.topMargin = (imageRect.top + y * imageRect.height()).roundToInt()
        params.width = (w * imageRect.width()).roundToInt().coerceAtLeast(1)
        params.height = (h * imageRect.height()).roundToInt().coerceAtLeast(1)
        layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private inner class DragListener(private val resize: Boolean) : OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var start = OverlayGeometry.Box(0f, 0f, 0f, 0f)

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val current = item ?: return false
            if (!editable || imageRect.width() <= 0f) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    start = OverlayGeometry.Box(current.x, current.y, current.w, current.h)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val dx = (event.rawX - startX) / imageRect.width()
                    val dy = (event.rawY - startY) / imageRect.height()
                    val box = OverlayGeometry.dragged(start, dx, dy, resize)
                    layoutAt(box.x, box.y, box.w, box.h)
                    if (event.actionMasked == MotionEvent.ACTION_UP) onMoved(box.x, box.y, box.w, box.h)
                }
            }
            return true
        }
    }

    private companion object {
        const val BAR_DP = 22
        const val HANDLE_DP = 26
    }
}
