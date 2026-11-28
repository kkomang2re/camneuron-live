package com.camneuron.live.widget

import com.camneuron.core.overlay.OverlayItem
import com.camneuron.core.overlay.OverlaySettings

/**
 * 글자 소스: 방송 화면에 넣는 문구 ("룰렛 있음", "후원 1,000원부터" 같은 안내). 웹 페이지 없이 앱이 바로 그린다
 * ([TextRenderer]). 여기에는 폰 없이 시험할 수 있는 계산만 둔다.
 *
 * 글자는 칸에 꽉 차게 그린다: 칸을 키우면 글자가 커진다 (글자 크기를 따로 정하지 않는다).
 * 영상 위에서도 읽히게 검은 테두리를 두르고, 원하면 반투명한 어두운 바탕을 깐다.
 */
object TextSources {
    /** 글자 소스 개수. 하나에 여러 줄을 넣을 수 있어 많이 필요하지 않다. */
    const val MAX = 3
    const val MAX_LINES = 4
    const val MAX_CHARS = 80

    /** 고를 수 있는 글자 색 (ARGB). 테두리가 검정이라 밝은 색만 둔다. 첫 번째가 기본. */
    val COLORS = listOf(
        OverlayItem.DEFAULT_TEXT_COLOR,
        0xFFFFE14D.toInt(), // 노랑
        0xFFFF7EB6.toInt(), // 분홍
        0xFF7FDBFF.toInt(), // 하늘
        0xFF8CFF98.toInt(), // 연두
    )

    /** 글자 크기 1에 대한 비율들. */
    const val LINE_HEIGHT = 1.25f
    const val OUTLINE = 0.12f
    private const val PAD_X = 0.10f
    private const val PAD_Y = 0.06f
    private const val PAD_X_BACKGROUND = 0.40f
    private const val PAD_Y_BACKGROUND = 0.15f
    private const val MIN_SIZE = 4f
    private const val LABEL_CHARS = 14

    /** 그릴 줄들: 줄마다 앞뒤 빈칸을 떼고, 빈 줄은 빼고, [MAX_LINES]줄까지. */
    fun lines(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES)

    /** 저장할 문구: 그릴 줄들만 남긴다. 비었으면 빈 글자. */
    fun clean(text: String): String = lines(text.take(MAX_CHARS)).joinToString("\n")

    /** 목록·막대에 보일 짧은 이름 (첫 줄, 길면 줄인다). */
    fun label(text: String): String {
        val first = lines(text).firstOrNull().orEmpty()
        return if (first.length > LABEL_CHARS) first.take(LABEL_CHARS - 1) + "…" else first
    }

    /** 글자 크기와 자리. [blockWidth]x[blockHeight]는 글자와 여백(바탕을 깔 자리)이고, 칸 가운데에 놓는다. */
    data class Fit(
        val size: Float,
        val lineHeight: Float,
        val padX: Float,
        val padY: Float,
        val outline: Float,
        val blockWidth: Float,
        val blockHeight: Float,
    ) {
        /** 가장 긴 줄이 차지할 수 있는 폭. */
        val textWidth: Float get() = blockWidth - 2 * padX
    }

    /**
     * [boxWidth]x[boxHeight] 픽셀 칸에 [lineCount]줄이 꽉 차는 글자 크기. [widestPerSize]는 가장 긴 줄의 폭을 글자 크기로
     * 나눈 값이다 (한글 다섯 자면 5쯤). 폭과 높이 가운데 먼저 닿는 쪽에 맞춘다.
     */
    fun fit(boxWidth: Int, boxHeight: Int, lineCount: Int, widestPerSize: Float, background: Boolean): Fit {
        val padX = if (background) PAD_X_BACKGROUND else PAD_X
        val padY = if (background) PAD_Y_BACKGROUND else PAD_Y
        val widthPerSize = widestPerSize + 2 * padX
        val heightPerSize = lineCount * LINE_HEIGHT + 2 * padY
        val size = minOf(boxWidth / widthPerSize, boxHeight / heightPerSize).coerceAtLeast(MIN_SIZE)
        return Fit(size, size * LINE_HEIGHT, size * padX, size * padY, size * OUTLINE, size * widthPerSize, size * heightPerSize)
    }

    /**
     * 새 글자 소스를 놓을 자리: [WidgetLayout.TEXTS] 가운데 이미 있는 글자([texts])와 겹치지 않는 첫 자리.
     * 다 겹치면 개수에 따라 돌려 쓴다.
     */
    fun freeSpot(texts: List<OverlayItem>): WidgetLayout.Spot =
        WidgetLayout.TEXTS.firstOrNull { spot -> texts.none { overlaps(it, spot) } }
            ?: WidgetLayout.TEXTS[texts.size % WidgetLayout.TEXTS.size]

    /** 새 글자 소스. 자리는 [freeSpot]. */
    fun create(texts: List<OverlayItem>, text: String, color: Int, background: Boolean, stream: Boolean): OverlayItem {
        val spot = freeSpot(texts)
        return OverlaySettings.DEFAULT_TEXT.copy(
            text = clean(text), textColor = color, textBackground = background, stream = stream,
            x = spot.x, y = spot.y, w = spot.w, h = spot.h,
        )
    }

    private fun overlaps(item: OverlayItem, spot: WidgetLayout.Spot): Boolean =
        item.x < spot.x + spot.w && spot.x < item.x + item.w && item.y < spot.y + spot.h && spot.y < item.y + item.h
}
