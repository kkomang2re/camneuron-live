package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomStepsTest {
    @Test
    fun ultraBackCameraDropsTwoTimes() {
        assertEquals(listOf(0.6f, 1f, 3f, 5f, 10f), ZoomSteps.steps(0.6f, 100f))
    }

    @Test
    fun keepsTwoTimesWhenFewSteps() {
        assertEquals(listOf(1f, 2f, 3f, 5f), ZoomSteps.steps(1f, 8f))
    }

    @Test
    fun allowsSmallRoundingAtEdges() {
        assertEquals(listOf(0.6f, 1f, 2f), ZoomSteps.steps(0.61f, 2f))
    }

    @Test
    fun singleStepMeansNoBar() {
        assertEquals(emptyList<Float>(), ZoomSteps.steps(1f, 1f))
    }

    @Test
    fun selectedIsNearestWithinFivePercent() {
        val steps = listOf(0.6f, 1f, 3f, 5f)
        assertEquals(3f, ZoomSteps.selected(steps, 3.1f))
        assertEquals(1f, ZoomSteps.selected(steps, 1f))
        assertNull(ZoomSteps.selected(steps, 2f))
    }

    @Test
    fun labels() {
        assertEquals("0.6×", ZoomSteps.label(0.6f))
        assertEquals("1×", ZoomSteps.label(1f))
        assertEquals("10×", ZoomSteps.label(10f))
    }
}
