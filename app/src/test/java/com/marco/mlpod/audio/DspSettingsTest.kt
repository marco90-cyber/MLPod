package com.marco.mlpod.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSettingsTest {
    private fun map(s: String): Map<String, String> =
        s.split(';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun defaultsMatchPowerampToneAndAreSafe() {
        val d = DspSettings()
        assertFalse(d.master)
        assertEquals(90f, d.bassFreq, 0f); assertEquals(0.8f, d.bassQ, 0f)
        assertEquals(10000f, d.trebleFreq, 0f); assertEquals(0.6f, d.trebleQ, 0f)
        assertTrue(d.limiter); assertTrue(d.autoHeadroom); assertFalse(d.preampOn)
        assertEquals(10, d.bands); assertEquals(10, d.graphic.size)
    }

    @Test
    fun everyBandCountHasItsFrequencies() {
        for (n in DspSettings.BAND_OPTIONS) {
            val f = DspSettings.freqsFor(n)
            assertEquals(n, f.size)
            assertTrue("$n bande crescenti", f.zipWithNext().all { (a, b) -> b > a })
        }
        assertEquals(6, DspSettings.BAND_OPTIONS.size)
    }

    @Test
    fun nativeTextCarriesEveryParameter() {
        val s = DspSettings(master = true, bassDb = 6f, bassQ = 1.2f, stereo = true, surroundMode = 3, preampOn = true, preampDb = -3f)
            .withBands(5).withGain(2, 4.5f)
        val m = map(s.toNative(rgDb = -6.5, volume = 0.5))
        assertEquals("1", m["master"]); assertEquals("1", m["eq"]); assertEquals("5", m["geq.n"])
        assertEquals("60.0000,230.0000,910.0000,3600.0000,14000.0000", m["geq.f"])
        assertEquals("4.5000", m["geq.g"]!!.split(',')[2])
        assertEquals("-3.0000", m["pre"])
        assertEquals("6.0000", m["bass"]); assertEquals("1.2000", m["bass.q"])
        assertEquals("1", m["stereo"]); assertEquals("3", m["surround"])
        assertEquals("-6.500", m["rg"]); assertEquals("0.5000", m["volume"])
    }

    @Test
    fun offModulesSendNeutralValues() {
        val s = DspSettings(master = true, tone = false, bassDb = 8f, stereo = false, surroundMode = 3, preampOn = false, preampDb = 6f)
        val m = map(s.toNative())
        assertEquals("0.0000", m["bass"]); assertEquals("0", m["surround"]); assertEquals("0", m["stereo"]); assertEquals("0.0000", m["pre"])
    }

    @Test
    fun changingBandCountKeepsTheCurveShape() {
        val s = DspSettings().withBands(31)
        val bass = s.freqs.indices.map { i -> if (s.freqs[i] <= 100f) 6f else 0f }
        val b31 = s.copy(graphic = bass)
        val b10 = b31.withBands(10)
        assertEquals(10, b10.graphic.size)
        assertEquals(6f, b10.graphic[0], 1e-4f)      // 31 Hz
        assertEquals(0f, b10.graphic[9], 1e-4f)      // 16 kHz
        assertEquals(b31, b31.withBands(42))         // numero non previsto: nulla cambia
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val s = DspSettings(
            master = true, eq = false, faithful = false, preampOn = true, preampDb = -3f,
            bassDb = 4f, bassFreq = 120f, bassQ = 0.5f, trebleDb = 2f, trebleFreq = 8000f, trebleQ = 1.1f,
            tone = false, analogBass = true, analogDrive = 0.7f, analogWarmth = 0.2f, analogFreq = 30f,
            air = true, stereo = true, surroundMode = 1, balance = -0.3f, expand = 0.4f,
            crossfeed = 0.6f, dbfbMode = 2, loudness = true, autoHeadroom = false,
            limiter = false, limiterThresholdDb = -2f, replayGain = ReplayGain.ALBUM,
        ).withBands(15).withGain(0, -5f)
        assertEquals(s, DspSettings.fromJson(s.toJson().toString()))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val s = DspSettings.fromJson("""{"v":3,"bands":7,"bass":99,"bassQ":9,"limThr":5,"graphic":[50,-50]}""")
        assertEquals(DspSettings.TONE_MAX_DB, s.bassDb, 0f)
        assertEquals(2f, s.bassQ, 0f)
        assertEquals(0f, s.limiterThresholdDb, 0f)
        assertEquals(7, s.graphic.size)
        assertEquals(DspSettings.GAIN, s.graphic[0], 0f); assertEquals(-DspSettings.GAIN, s.graphic[1], 0f); assertEquals(0f, s.graphic[6], 0f)
        assertEquals(10, DspSettings.fromJson("""{"v":3,"bands":12}""").bands)
    }

    @Test
    fun version2ParametricBecomesAGraphicCurve() {
        val v2 = """{"v":2,"master":true,"eqMode":2,"pre":-2,"parametric":[{"on":true,"type":1,"f":100,"g":6,"q":0.7}],"balance":0.5}"""
        val s = DspSettings.fromJson(v2)
        assertTrue(s.eq); assertEquals(31, s.bands)
        assertEquals(6f, s.graphic[0], 0.3f)          // 20 Hz: sotto lo shelf
        assertEquals(0f, s.graphic[30], 0.1f)         // 20 kHz
        assertTrue(s.preampOn); assertEquals(-2f, s.preampDb, 0f)
        assertTrue(s.stereo)
        val g = DspSettings.fromJson("""{"v":2,"eqMode":1,"graphic":[1,2,3,4,5,6,7,8,9,10,11,12,12,12,12]}""")
        assertEquals(15, g.bands); assertEquals(5f, g.graphic[4], 0f)
    }

    @Test
    fun version1IsMigrated() {
        val old = """{"enabled":true,"bandCount":2,"freqs":[1000,5000],"gains":[6,0],"qs":[1.41,1],"toneV":3,"bass":50,"preamp":-4,"balance":-40,"replayGain":1}"""
        val s = DspSettings.fromJson(old)
        assertTrue(s.master); assertTrue(s.eq); assertEquals(31, s.bands)
        assertEquals(6f, s.graphic[DspSettings.freqsFor(31).indexOf(1000f)], 0.05f)
        assertEquals(5f, s.bassDb, 1e-4f)
        assertTrue(s.preampOn); assertEquals(-4f, s.preampDb, 0f)
        assertTrue(s.stereo); assertEquals(-0.4f, s.balance, 1e-6f)
        assertEquals(ReplayGain.TRACK, s.replayGain)
    }

    @Test
    fun brokenJsonGivesDefaults() {
        assertEquals(DspSettings(), DspSettings.fromJson("non json"))
        assertEquals(DspSettings(), DspSettings.fromJson(null))
    }

    @Test
    fun presetsAdaptToAnyBandCount() {
        val bass = GraphicPresets.all.first { it.name == "Bassi" }.part
        for (n in DspSettings.BAND_OPTIONS) {
            val s = DspSettings().withBands(n).withEqPart(bass)
            assertEquals(n, s.graphic.size)
            assertTrue("$n bande: bassi rinforzati", s.graphic.first() >= 3.5f)
            assertEquals(0f, s.graphic.last(), 0.6f)
        }
        val p = DspSettings().withGain(3, 2f).eqPart()
        assertEquals(p, EqPart.fromJson(p.toJson()))
    }
}
