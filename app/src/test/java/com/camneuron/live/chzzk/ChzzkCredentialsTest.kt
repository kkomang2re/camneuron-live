package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChzzkCredentialsTest {
    @Test
    fun removesInvisibleAndSpaceCharacters() {
        assertEquals("abc-DEF_123", ChzzkCredentials.clean(" abc-DEF_123\n"))
        assertEquals("abc-DEF_123", ChzzkCredentials.clean("abc​-DEF _12﻿3\t"))
    }

    @Test
    fun turnsFullWidthIntoNormalCharacters() {
        assertEquals("ab-12", ChzzkCredentials.clean("ａｂ－１２"))
    }

    @Test
    fun keepsANormalValueUnchanged() {
        val value = "12345678-abcd-4ef0-9a8b-000000000000"
        assertEquals(value, ChzzkCredentials.clean(value))
    }

    @Test
    fun summaryShowsLengthAndLastFour() {
        assertEquals("10자 · 끝 4자리 6789", ChzzkCredentials.summary("0123456789"))
        assertEquals("2자 · 끝 4자리 ab", ChzzkCredentials.summary("ab"))
        assertNull(ChzzkCredentials.summary(""))
    }
}
