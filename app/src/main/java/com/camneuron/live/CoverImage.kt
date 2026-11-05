package com.camneuron.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** 화면 가리기 때 시청자에게 보내는 그림: 어두운 바탕 가운데에 "잠시 자리를 비웠어요". */
object CoverImage {
    fun render(width: Int, height: Int, title: String, subtitle: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x16, 0x16, 0x1A))
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = height * 0.075f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xAA, 0xFF, 0xFF, 0xFF)
            textSize = height * 0.04f
            typeface = Typeface.SANS_SERIF
            textAlign = Paint.Align.CENTER
        }
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText(title, cx, cy, big)
        canvas.drawText(subtitle, cx, cy + small.textSize * 2f, small)
        return bitmap
    }
}
