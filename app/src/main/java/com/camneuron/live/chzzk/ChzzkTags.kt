package com.camneuron.live.chzzk

/**
 * 방송 태그 입력 글자를 치지직에 보낼 태그 목록으로. 치지직은 태그에 띄어쓰기와 특수문자를 받지 않는다 (공식 문서).
 * 개수·길이 제한은 문서에 없어 여기서 자르지 않는다. 넘으면 치지직이 거부하고 그 이유가 화면에 보인다.
 */
object ChzzkTags {
    /** 쉼표·#·줄바꿈으로 나누고, 글자와 숫자만 남기고, 같은 태그는 한 번만. */
    fun parse(text: String): List<String> =
        text.split(',', '#', '\n')
            .map { part -> part.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .distinct()

    fun format(tags: List<String>): String = tags.joinToString(", ")
}
