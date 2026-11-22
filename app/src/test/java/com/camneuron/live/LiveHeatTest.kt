package com.camneuron.live

import com.camneuron.core.net.NetGovernor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHeatTest {
    @Test
    fun coolKeepsEverything() {
        assertEquals(60, LiveHeat.fps(60, 0))
        assertEquals(4_500_000, LiveHeat.bitrate(4_500_000, 0))
        assertEquals(1f, LiveHeat.widgetSlowdown(0))
    }

    @Test
    fun hotCapsFpsAt30ButNeverRaisesIt() {
        assertEquals(30, LiveHeat.fps(60, 1))
        assertEquals(30, LiveHeat.fps(60, 3))
        assertEquals(30, LiveHeat.fps(30, 3))
        assertEquals(24, LiveHeat.fps(24, 2))
    }

    @Test
    fun bitrateDropsOnlyAtLevel2AndAbove() {
        assertEquals(4_500_000, LiveHeat.bitrate(4_500_000, 1))
        assertEquals(4_050_000, LiveHeat.bitrate(4_500_000, 2))
        assertEquals(3_375_000, LiveHeat.bitrate(4_500_000, 3))
    }

    @Test
    fun hottestDefaultStreamStaysWellAboveTheOldMosaicBitrate() {
        // 예전에는 1080p30 기본값(4.5 Mbps)이 가장 뜨거울 때 1.8 Mbps(40%)까지 내려갔다.
        val hottest = LiveHeat.bitrate(LiveQuality().bitrate, 3)
        assertTrue(hottest >= 3_000_000)
    }

    @Test
    fun maxBitrateDoesNotOverflow() {
        assertEquals(6_000_000, LiveHeat.bitrate(LiveQuality.MAX_KBPS * 1000, 3))
    }

    @Test
    fun widgetsSlowDownAsItGetsHotter() {
        assertEquals(1.5f, LiveHeat.widgetSlowdown(1))
        assertEquals(2f, LiveHeat.widgetSlowdown(2))
        assertEquals(3f, LiveHeat.widgetSlowdown(3))
        assertEquals(3f, LiveHeat.widgetSlowdown(9))
    }

    @Test
    fun networkCutStillAppliesOnTopOfHeat() {
        val heat = LiveHeat.bitrate(4_500_000, 2)
        assertEquals(2_025_000, NetGovernor.limitBitrate(heat, 2))
    }
}
