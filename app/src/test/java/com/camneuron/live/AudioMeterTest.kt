package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMeterTest {
    @Test
    fun barIsDecibelScaled() {
        assertEquals(0f, AudioMeter.bar(0f), 0f)
        assertEquals(0f, AudioMeter.bar(0.0005f), 0f) // -66 dB: 바닥 아래
        assertEquals(1f, AudioMeter.bar(1f), 1e-4f)
        assertEquals(0.5f, AudioMeter.bar(0.0316f), 0.01f) // -30 dB
        assertEquals(0.9f, AudioMeter.bar(0.5f), 0.01f) // -6 dB
        assertEquals(1f, AudioMeter.bar(2f), 0f)
    }

    @Test
    fun takeGivesThePeakSinceLastTake() {
        AudioMeter.take()
        AudioMeter.push(0.2f)
        AudioMeter.push(0.7f)
        AudioMeter.push(0.4f)
        assertEquals(0.7f, AudioMeter.take(), 0f)
        assertEquals(0f, AudioMeter.take(), 0f)
        assertTrue(AudioMeter.bar(0.7f) > AudioMeter.bar(0.2f))
    }
}
