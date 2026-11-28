package com.camneuron.live.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.camneuron.core.overlay.OverlayItem

/**
 * 글자 소스를 그림으로 그린다 ([TextSources]). 그림의 크기는 그 칸의 픽셀 크기([WidgetCanvas.pixelRect])와 같고,
 * 웹 위젯의 그림과 똑같이 미리보기와 방송 화면에 얹힌다. 문구가 바뀔 때만 그리므로 폰에 부담이 없다.
 */
object TextRenderer {
    /** 줄의 폭을 재는 글자 크기. 폭은 글자 크기에 비례한다고 보고 나눠 쓴다. */
    private const val MEASURE_SIZE = 100f
    private const val BACKGROUND = 0xB3000000.toInt()
    private const val CORNER = 0.3f

    /** 그릴 것이 없으면 (문구가 비었으면) null. */
    fun render(item: OverlayItem): Bitmap? {
        val lines = TextSources.lines(item.text)
        if (lines.isEmpty()) return null
        val rect = WidgetCanvas.pixelRect(item)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = MEASURE_SIZE
        }
        val widest = lines.maxOf { paint.measureText(it) } / MEASURE_SIZE
        val fit = TextSources.fit(rect.width, rect.height, lines.size, widest, item.textBackground)
        paint.textSize = fit.size
        // 폭이 글자 크기에 딱 비례하지는 않는다. 재 보고 넘치면 그만큼 줄인다.
        val measured = lines.maxOf { paint.measureText(it) }
        if (measured > fit.textWidth && measured > 0f) paint.textSize = fit.size * fit.textWidth / measured

        val bitmap = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerX = rect.width / 2f
        val top = (rect.height - fit.blockHeight) / 2f
        if (item.textBackground) {
            paint.style = Paint.Style.FILL
            paint.color = BACKGROUND
            val radius = fit.size * CORNER
            canvas.drawRoundRect(
                centerX - fit.blockWidth / 2f, top, centerX + fit.blockWidth / 2f, top + fit.blockHeight, radius, radius, paint,
            )
        }
        val metrics = paint.fontMetrics
        lines.forEachIndexed { i, line ->
            val lineTop = top + fit.padY + i * fit.lineHeight
            val baseline = lineTop + (fit.lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            // 어떤 영상 위에서도 읽히게 검은 테두리를 먼저 그린다.
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = fit.outline
            paint.color = Color.BLACK
            canvas.drawText(line, centerX, baseline, paint)
            paint.style = Paint.Style.FILL
            paint.color = item.textColor
            canvas.drawText(line, centerX, baseline, paint)
        }
        return bitmap
    }

    /** 같은 그림이 나오는지 (자리만 다른지). 그러면 다시 그리지 않는다. */
    fun sameLook(a: OverlayItem, b: OverlayItem): Boolean {
        val ra = WidgetCanvas.pixelRect(a)
        val rb = WidgetCanvas.pixelRect(b)
        return a.text == b.text && a.textColor == b.textColor && a.textBackground == b.textBackground &&
            ra.width == rb.width && ra.height == rb.height
    }
}
