package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Test

class ChzzkTagsTest {
    @Test
    fun splitsAndCleans() {
        assertEquals(listOf("야외", "서울산책", "IRL"), ChzzkTags.parse("야외, 서울 산책, #IRL!, 야외,, "))
        assertEquals(emptyList<String>(), ChzzkTags.parse(" , #, "))
        assertEquals("야외, 서울", ChzzkTags.format(listOf("야외", "서울")))
    }
}
