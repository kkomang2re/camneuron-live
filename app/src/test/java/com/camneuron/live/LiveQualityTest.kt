package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveQualityTest {
    @Test
    fun defaultIs1080p30At4_5Mbps() {
        val q = LiveQuality()
        assertEquals(1920, q.width)
        assertEquals(1080, q.height)
        assertEquals(30, q.fps)
        assertEquals(4_500_000, q.bitrate)
        assertEquals(2, q.keyFrameS)
        assertEquals("1080p30", q.label)
    }

    @Test
    fun widthIs16By9() {
        assertEquals(1280, LiveQuality(height = 720).width)
    }

    @Test
    fun presetSetsRecommendedBitrate() {
        val base = LiveQuality(bitrateKbps = 2000, keyFrameS = 1)
        assertEquals(LiveQuality(1080, 60, 6000, 1), base.withPreset(1080, 60))
        assertEquals(LiveQuality(720, 60, 3500, 1), base.withPreset(720, 60))
        assertEquals(LiveQuality(720, 30, 2500, 1), base.withPreset(720, 30))
    }

    @Test
    fun sanitizedClampsBadStoredValues() {
        assertEquals(LiveQuality(1080, 60, 8000, 2), LiveQuality(1440, 50, 20_000, 5).sanitized())
        assertEquals(LiveQuality(720, 30, 1000, 1), LiveQuality(480, 24, 100, 1).sanitized())
    }

    @Test
    fun sanitizedRoundsBitrateToStep() {
        assertEquals(4500, LiveQuality(bitrateKbps = 4321).sanitized().bitrateKbps)
        assertEquals(4000, LiveQuality(bitrateKbps = 4100).sanitized().bitrateKbps)
    }

    @Test
    fun validValuesAreUnchanged() {
        val q = LiveQuality(720, 60, 3500, 1)
        assertEquals(q, q.sanitized())
    }
}
