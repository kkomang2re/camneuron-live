package com.camneuron.live

import kotlin.math.abs

/** 줌 빠른 버튼 (0.6×, 1×, 3× …). 카메라가 지원하는 범위 안의 값만 보인다. */
object ZoomSteps {
    private val CANDIDATES = listOf(0.6f, 1f, 2f, 3f, 5f, 10f)
    private const val MAX_STEPS = 5
    private const val TOLERANCE = 0.02f

    /** [min]~[max] 범위에 드는 단계. 많으면 2×를 뺀다 (갤럭시 울트라는 3×·5×·10× 렌즈가 따로 있다). 하나뿐이면 빈 목록. */
    fun steps(min: Float, max: Float): List<Float> {
        var list = CANDIDATES.filter { it >= min - TOLERANCE && it <= max + TOLERANCE }
        if (list.size > MAX_STEPS) list = list - 2f
        return if (list.size < 2) emptyList() else list.take(MAX_STEPS)
    }

    /** 지금 배율과 가장 가까운 단계. 어느 단계와도 5% 넘게 다르면 null (핀치로 맞춘 배율). */
    fun selected(steps: List<Float>, zoom: Float): Float? =
        steps.minByOrNull { abs(it - zoom) }?.takeIf { abs(it - zoom) <= it * 0.05f }

    /** 버튼 글자. 1 이상은 정수로: "0.6×", "1×", "10×". */
    fun label(step: Float): String =
        if (step < 1f) "${(step * 10).toInt() / 10f}×" else "${step.toInt()}×"
}
