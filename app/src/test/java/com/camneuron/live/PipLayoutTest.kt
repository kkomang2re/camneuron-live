package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipLayoutTest {
    // Surface.ROTATION_0, 90, 180, 270
    private val portrait = 0
    private val landscape = 1
    private val upsideDown = 2
    private val landscapeReversed = 3

    @Test
    fun sameRotationNeedsNoTurn() {
        assertEquals(0, PipLayout.quarterTurns(landscape, landscape))
        assertEquals(0, PipLayout.quarterTurns(landscapeReversed, landscapeReversed))
    }

    @Test
    fun goingHomeFromLandscapeTurnsTheFrameBack() {
        // 가로(시계 방향 90도로 그리는 방향)로 방송하다 세로 홈 화면으로: 틀을 시계 방향으로 90도 돌려 놓는다.
        assertEquals(1, PipLayout.quarterTurns(landscape, portrait))
        assertEquals(3, PipLayout.quarterTurns(landscapeReversed, portrait))
    }

    @Test
    fun otherRotationsWrapAround() {
        assertEquals(2, PipLayout.quarterTurns(landscape, landscapeReversed))
        assertEquals(3, PipLayout.quarterTurns(landscape, upsideDown))
        assertEquals(1, PipLayout.quarterTurns(landscapeReversed, upsideDown))
    }

    @Test
    fun windowIsTallOnlyWhenTheFrameIsTurnedSideways() {
        assertEquals(16 to 9, PipLayout.aspect(0))
        assertEquals(9 to 16, PipLayout.aspect(1))
        assertEquals(16 to 9, PipLayout.aspect(2))
        assertEquals(9 to 16, PipLayout.aspect(3))
        assertFalse(PipLayout.swapsSides(2))
        assertTrue(PipLayout.swapsSides(3))
    }
}
