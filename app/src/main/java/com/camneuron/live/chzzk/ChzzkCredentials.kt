package com.camneuron.live.chzzk

/** 붙여 넣은 Client ID/Secret 정리와, 저장된 값을 보여 주지 않고 확인할 수 있는 요약. */
object ChzzkCredentials {
    /**
     * 복사·붙여넣기로 섞이기 쉬운 글자를 없앤다: 공백·줄바꿈·탭, 폭 없는 공백(U+200B~U+200D, U+2060, U+FEFF),
     * 줄 바꿈 없는 공백(U+00A0). ID와 Secret에는 이런 글자가 들어가지 않는다.
     * 전각 영숫자·기호(키보드가 바꿔 넣는 것)는 보통 글자로 되돌린다.
     */
    fun clean(text: String): String = buildString {
        for (c in text) {
            when {
                c.isWhitespace() || c == ' ' || c in '​'..'‍' || c == '⁠' || c == '﻿' -> Unit
                c in '！'..'～' -> append((c.code - 0xFF01 + 0x21).toChar())
                else -> append(c)
            }
        }
    }

    /** 예: "43자 · 끝 4자리 Pdcs". 비어 있으면 null. */
    fun summary(value: String): String? {
        if (value.isEmpty()) return null
        return "${value.length}자 · 끝 4자리 ${value.takeLast(4)}"
    }
}
