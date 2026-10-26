package com.marco.mlpod.audio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Import/export dei preset nel formato testo di Poweramp / AutoEQ:
 *
 *   Preamp: -6.4 dB
 *   Filter 1: ON LS Fc 105 Hz Gain 6.5 dB Q 0.70
 *   Filter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
 *
 * Supporta anche la riga "GraphicEQ: 20 0; 25 1.5; ..." e, in lettura, il separatore decimale con virgola.
 */
object PresetText {
    private val filterRe = Regex(
        """Filter\s*\d*\s*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+([-\d.,]+)\s*(?:Hz)?\s+Gain\s+([-\d.,]+)\s*(?:dB)?(?:\s+Q\s+([-\d.,]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val preampRe = Regex("""Preamp\s*:\s*([-+\d.,]+)\s*(?:dB)?""", RegexOption.IGNORE_CASE)

    private fun num(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

    /** Restituisce la configurazione letta (partendo da [base]) oppure null se il testo non è riconoscibile. */
    fun parse(text: String, base: EqConfig): EqConfig? {
        val lines = text.lines()
        var preamp = 0f
        val pk = ArrayList<Triple<Float, Float, Float>>() // freq, gain, q
        var bass = 0
        var bassF = base.bassFreq
        var treble = 0
        var trebleF = base.trebleFreq
        var graphic: List<Pair<Float, Float>>? = null
        var found = false
        for (raw in lines) {
            val line = raw.trim()
            preampRe.find(line)?.let { m -> num(m.groupValues[1])?.let { preamp = it; found = true } }
            if (line.startsWith("GraphicEQ", ignoreCase = true)) {
                val pts = line.substringAfter(':').split(';').mapNotNull { seg ->
                    val p = seg.trim().split(Regex("\\s+"))
                    if (p.size >= 2) {
                        val f = num(p[0]); val g = num(p[1])
                        if (f != null && g != null) f to g else null
                    } else null
                }
                if (pts.size >= 2) { graphic = pts; found = true }
                continue
            }
            val m = filterRe.find(line) ?: continue
            found = true
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
            val f = num(m.groupValues[3]) ?: continue
            val g = num(m.groupValues[4]) ?: continue
            val q = m.groupValues[5].let { if (it.isEmpty()) 0.707f else num(it) ?: 0.707f }
            when (m.groupValues[2].uppercase(Locale.ROOT)) {
                "LS", "LSC", "LSQ" ->
                    if (g > 0f && bass == 0) { bass = (g * 10f).roundToInt().coerceIn(0, 100); bassF = f.coerceIn(20f, 500f) }
                    else pk.add(Triple(f, g, 0.7f))
                "HS", "HSC", "HSQ" ->
                    if (g > 0f && treble == 0) { treble = (g * 10f).roundToInt().coerceIn(0, 100); trebleF = f.coerceIn(1000f, 20000f) }
                    else pk.add(Triple(f, g, 0.7f))
                "PK", "PEQ", "PEAK" -> pk.add(Triple(f, g, q))
                else -> {}
            }
        }
        if (!found) return null
        var cfg = base.copy(
            enabled = true,
            preampDb = preamp.coerceIn(-24f, 12f),
            bassPercent = bass, bassFreq = bassF,
            treblePercent = treble, trebleFreq = trebleF,
        )
        val g = graphic
        if (g != null) {
            return cfg.copy(gains = List(cfg.bandCount) { 0f }).withCurve(g)
        }
        if (pk.isNotEmpty()) {
            // ordina e rispetta la distanza minima fra le bande
            val sorted = pk.sortedBy { it.first }.map { Triple(it.first.coerceIn(EqConfig.MIN_FREQ, EqConfig.MAX_FREQ), it.second, it.third) }
            val kept = ArrayList<Triple<Float, Float, Float>>()
            for (t in sorted) {
                if (kept.isEmpty() || t.first >= kept.last().first * 1.05f) kept.add(t)
                else kept[kept.lastIndex] = Triple(kept.last().first, kept.last().second + t.second, kept.last().third)
            }
            val list = kept.take(EqConfig.MAX_BANDS)
            val n = list.size.coerceAtLeast(EqConfig.MIN_BANDS)
            val freqs = ArrayList(list.map { it.first })
            val gains = ArrayList(list.map { it.second.coerceIn(-EqConfig.GAIN_RANGE, EqConfig.GAIN_RANGE) })
            val qs = ArrayList(list.map { it.third.coerceIn(EqConfig.MIN_Q, EqConfig.MAX_Q) })
            // completa fino al minimo di bande con bande neutre
            var f = freqs.last()
            while (freqs.size < n) { f = (f * 1.3f).coerceAtMost(EqConfig.MAX_FREQ); freqs.add(f); gains.add(0f); qs.add(1f) }
            cfg = cfg.copy(bandCount = n, freqs = freqs, gains = gains, qs = qs)
        } else {
            cfg = cfg.copy(gains = List(cfg.bandCount) { 0f })
        }
        return cfg
    }

    fun export(cfg: EqConfig): String {
        fun f(v: Float, d: Int) = String.format(Locale.US, "%.${d}f", v)
        val sb = StringBuilder()
        sb.append("Preamp: ").append(f(cfg.preampDb, 1)).append(" dB\n")
        var n = 1
        if (cfg.bassPercent != 0)
            sb.append("Filter ${n++}: ON LS Fc ${f(cfg.bassFreq, 0)} Hz Gain ${f(EqMath.toneDb(cfg.bassPercent).toFloat(), 1)} dB Q 0.70\n")
        for (i in 0 until cfg.bandCount) {
            if (abs(cfg.gains[i]) < 0.05f) continue
            sb.append("Filter ${n++}: ON PK Fc ${f(cfg.freqs[i], 0)} Hz Gain ${f(cfg.gains[i], 1)} dB Q ${f(cfg.qs.getOrElse(i) { 1f }, 2)}\n")
        }
        if (cfg.treblePercent != 0)
            sb.append("Filter ${n++}: ON HS Fc ${f(cfg.trebleFreq, 0)} Hz Gain ${f(EqMath.toneDb(cfg.treblePercent).toFloat(), 1)} dB Q 0.70\n")
        return sb.toString()
    }
}
