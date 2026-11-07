package com.marco.mlpod.audio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Import/export dei preset nel formato testo di Poweramp / AutoEQ:
 *
 *   Preamp: -6.4 dB
 *   Filter 1: ON LS Fc 105 Hz Gain 6.5 dB Q 0.70
 *   Filter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
 *
 * I filtri vengono trasformati nella loro curva (calcolata su 31 punti a 1/3 d'ottava), che poi si
 * adatta al numero di bande scelto. Va bene anche la riga "GraphicEQ: 20 0; 25 1.5; ..." e il JSON
 * di Poweramp; il separatore decimale può essere la virgola.
 */
object PresetText {
    private val filterRe = Regex(
        """Filter\s*\d*\s*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+([-\d.,]+)\s*(?:Hz)?(?:\s+Gain\s+([-\d.,]+)\s*(?:dB)?)?(?:\s+Q\s+([-\d.,]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val preampRe = Regex("""Preamp\s*:\s*([-+\d.,]+)\s*(?:dB)?""", RegexOption.IGNORE_CASE)

    private fun num(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

    private fun typeOf(code: String): String? = when (code.uppercase(Locale.ROOT)) {
        "PK", "PEQ", "PEAK" -> "PK"
        "LS", "LSC", "LSQ" -> "LS"
        "HS", "HSC", "HSQ" -> "HS"
        else -> null
    }

    /** Nome + preset. Accetta il JSON di Poweramp (elenco di preset) oppure il testo AutoEQ. */
    fun parseMulti(text: String, fallbackName: String): List<Pair<String, EqPart>> {
        val t = text.trim().removePrefix("﻿")
        if (t.startsWith("[") || t.startsWith("{")) {
            return try {
                val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
                val out = ArrayList<Pair<String, EqPart>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sb = StringBuilder("Preamp: ${o.optDouble("preamp", 0.0)} dB\n")
                    val bands = o.optJSONArray("bands") ?: continue
                    for (k in 0 until bands.length()) {
                        val b = bands.getJSONObject(k)
                        // Poweramp (ordine del menu): 0 passa basso, 1 passa alto, 2 passa banda, 3 campanatura,
                        // 4 scaffale basso, 5 scaffale alto; i primi due della lista sono le manopole Bassi/Alti
                        val ty = when (b.optInt("type", 3)) { 0, 4 -> "LS"; 1, 5 -> "HS"; 2, 3 -> "PK"; else -> continue }
                        if (b.optDouble("gain", 0.0) == 0.0) continue
                        val q = b.optDouble("q", 0.0).let { if (it <= 0.0) 1.0 else it }
                        sb.append("Filter ${k + 1}: ON $ty Fc ${b.optDouble("frequency")} Hz Gain ${b.optDouble("gain")} dB Q $q\n")
                    }
                    val part = parse(sb.toString()) ?: continue
                    val name = o.optString("name", "").replace(Regex("\\s+"), " ").trim().take(24).ifEmpty { fallbackName }
                    out.add(name to part)
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
        return parse(t)?.let { listOf(fallbackName to it) } ?: emptyList()
    }

    /** Preset letto, oppure null se il testo non è riconoscibile. */
    fun parse(text: String): EqPart? {
        var preamp = 0f
        val filters = ArrayList<FilterSpec>()
        var graphic: List<Pair<Float, Float>>? = null
        var found = false
        for (raw in text.lines()) {
            val line = raw.trim()
            preampRe.find(line)?.let { m -> num(m.groupValues[1])?.let { preamp = it; found = true } }
            if (line.startsWith("GraphicEQ", ignoreCase = true)) {
                val pts = line.substringAfter(':').split(';').mapNotNull { seg ->
                    val p = seg.trim().split(Regex("\\s+"))
                    val f = p.getOrNull(0)?.let(::num); val g = p.getOrNull(1)?.let(::num)
                    if (f != null && g != null && f > 0f) f to g else null
                }
                if (pts.size >= 2) { graphic = pts.sortedBy { it.first }; found = true }
                continue
            }
            val m = filterRe.find(line) ?: continue
            found = true
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
            val type = typeOf(m.groupValues[2]) ?: continue
            val f = num(m.groupValues[3]) ?: continue
            val g = m.groupValues[4].let { if (it.isEmpty()) 0f else num(it) ?: 0f }
            val q = m.groupValues[5].let { if (it.isEmpty()) 0.707f else num(it) ?: 0.707f }
            filters.add(FilterSpec(type, f.coerceIn(20f, 20000f), g.coerceIn(-15f, 15f), q.coerceIn(0.1f, 18f)))
        }
        if (!found) return null
        val pre = preamp.coerceIn(-12f, 12f)
        val gr = graphic
        if (gr != null) return EqPart(gr.map { it.first }, gr.map { it.second.coerceIn(-DspSettings.GAIN, DspSettings.GAIN) }, pre)
        val at = DspSettings.freqsFor(31)
        val curve = at.map { f -> filters.sumOf { s -> DspSettings.filterDb(s.type, s.freq, s.gain, s.q, f).toDouble() }.toFloat().coerceIn(-DspSettings.GAIN, DspSettings.GAIN) }
        return EqPart(at, curve, pre)
    }

    /** Preset parametrico: nome, preamp e filtri così come sono. */
    class Parametric(val name: String, val preampDb: Float, val bands: List<PeqBand>, val notes: List<String> = emptyList())

    private fun peqType(code: String): Int? = when (code.uppercase(Locale.ROOT)) {
        "PK", "PEQ", "PEAK" -> PeqBand.PK
        "LS", "LSC", "LSQ", "LSF" -> PeqBand.LS
        "HS", "HSC", "HSQ", "HSF" -> PeqBand.HS
        "LP", "LPQ", "LPF" -> PeqBand.LP
        "HP", "HPQ", "HPF" -> PeqBand.HP
        "BP" -> PeqBand.BP
        "NO", "NOTCH" -> PeqBand.NOTCH
        else -> null
    }

    /**
     * Preset per il parametrico: testo AutoEQ / Equalizer APO (anche passa-alto, passa-basso, notch)
     * oppure JSON di Poweramp. Vuoto se il file non ha filtri (per esempio una riga GraphicEQ).
     */
    fun parseParametric(text: String, fallbackName: String): List<Parametric> {
        val t = text.trim().removePrefix("\uFEFF")
        if (t.startsWith("[") || t.startsWith("{")) {
            return try {
                val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
                val out = ArrayList<Parametric>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val bands = o.optJSONArray("bands") ?: continue
                    val list = ArrayList<PeqBand>()
                    var approxLpHp = 0; var approxBp = 0; var unknown = 0; var oneChannel = 0
                    for (k in 0 until bands.length()) {
                        val b = bands.getJSONObject(k)
                        val g = b.optDouble("gain", 0.0).toFloat()
                        if (g == 0f) continue  // in Poweramp tutti i filtri hanno un guadagno: 0 dB = nessun effetto
                        // Poweramp (ordine del menu): 0 passa basso, 1 passa alto, 2 passa banda, 3 campanatura,
                        // 4 scaffale basso, 5 scaffale alto. Il passa basso/alto di Poweramp ha un guadagno: si
                        // comporta come uno scaffale risonante, il passa banda come una campana più larga.
                        val t = b.optInt("type", 3)
                        val ty = when (t) {
                            0 -> { approxLpHp++; PeqBand.LS }
                            1 -> { approxLpHp++; PeqBand.HS }
                            2 -> { approxBp++; PeqBand.PK }
                            3 -> PeqBand.PK
                            4 -> PeqBand.LS
                            5 -> PeqBand.HS
                            else -> { unknown++; continue }
                        }
                        if (b.optInt("channels", 0) != 0) oneChannel++
                        val q = b.optDouble("q", 0.0).let { if (it <= 0.0) 0.707 else it }.toFloat()
                        list.add(PeqBand(true, ty, b.optDouble("frequency", 1000.0).toFloat().coerceIn(20f, 20000f), g.coerceIn(-24f, 24f), q.coerceIn(0.1f, 20f)))
                    }
                    if (list.isEmpty()) continue
                    val notes = ArrayList<String>()
                    if (approxLpHp > 0) notes.add("$approxLpHp passa basso/alto con guadagno resi come scaffali")
                    if (approxBp > 0) notes.add("$approxBp passa banda reso come campana")
                    if (oneChannel > 0) notes.add("$oneChannel filtri di un solo canale applicati a entrambi")
                    if (unknown > 0) notes.add("$unknown filtri di tipo sconosciuto saltati")
                    if (list.size > DspSettings.PEQ_MAX) notes.add("${list.size - DspSettings.PEQ_MAX} filtri oltre il ${DspSettings.PEQ_MAX}° non importati")
                    val name = o.optString("name", "").replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { fallbackName }
                    out.add(Parametric(name, o.optDouble("preamp", 0.0).toFloat().coerceIn(-30f, 0f), list.take(DspSettings.PEQ_MAX), notes))
                }
                out
            } catch (_: Exception) { emptyList() }
        }
        var preamp = 0f
        val list = ArrayList<PeqBand>()
        for (raw in t.lines()) {
            val line = raw.trim()
            preampRe.find(line)?.let { m -> num(m.groupValues[1])?.let { preamp = it } }
            val m = filterRe.find(line) ?: continue
            val type = peqType(m.groupValues[2]) ?: continue
            val f = num(m.groupValues[3]) ?: continue
            val g = m.groupValues[4].let { if (it.isEmpty()) 0f else num(it) ?: 0f }
            val q = m.groupValues[5].let { if (it.isEmpty()) 0.707f else num(it) ?: 0.707f }
            list.add(PeqBand(m.groupValues[1].equals("ON", true), type, f.coerceIn(20f, 20000f), g.coerceIn(-24f, 24f), q.coerceIn(0.1f, 20f)))
        }
        if (list.isEmpty()) return emptyList()
        val notes = if (list.size > DspSettings.PEQ_MAX) listOf("${list.size - DspSettings.PEQ_MAX} filtri oltre il ${DspSettings.PEQ_MAX}° non importati") else emptyList()
        return listOf(Parametric(fallbackName, preamp.coerceIn(-30f, 0f), list.take(DspSettings.PEQ_MAX), notes))
    }

    /** Testo AutoEQ / Equalizer APO del parametrico. */
    fun exportParametric(s: DspSettings): String {
        fun f(v: Float, d: Int) = String.format(Locale.US, "%.${d}f", v)
        val sb = StringBuilder("Preamp: ${f(s.peqPreamp, 1)} dB\n")
        s.peq.forEachIndexed { i, b ->
            sb.append("Filter ${i + 1}: ${if (b.on) "ON" else "OFF"} ${PeqBand.code(b.type)} Fc ${f(b.freq, 0)} Hz")
            if (b.hasGain) sb.append(" Gain ${f(b.gain, 1)} dB")
            sb.append(" Q ${f(b.q, 2)}\n")
        }
        return sb.toString()
    }

    /** Filtri che Poweramp non ha (i suoi passa basso/alto/banda hanno un guadagno, non tagliano). */
    fun powerampSkipped(s: DspSettings): Int = s.peq.count { it.on && !it.hasGain }

    /** JSON per l'equalizzatore parametrico di Poweramp: campanatura (3), scaffale basso (4), scaffale alto (5). */
    fun exportPoweramp(name: String, s: DspSettings): String {
        val bands = JSONArray()
        bands.put(JSONObject().put("type", 0).put("channels", 0).put("frequency", 90).put("q", 0.8).put("gain", 0.0).put("color", 0))
        bands.put(JSONObject().put("type", 1).put("channels", 0).put("frequency", 10000).put("q", 0.6).put("gain", 0.0).put("color", 0))
        for (b in s.peq) {
            if (!b.on) continue
            val t = when (b.type) { PeqBand.PK -> 3; PeqBand.LS -> 4; PeqBand.HS -> 5; else -> continue }
            bands.put(JSONObject().put("type", t).put("channels", 0).put("frequency", b.freq.toDouble()).put("q", b.q.toDouble()).put("gain", b.gain.toDouble()).put("color", -15649895))
        }
        val o = JSONObject().put("name", name).put("preamp", s.peqPreamp.toDouble()).put("parametric", true).put("bands", bands)
        return JSONArray().put(o).toString(2)
    }

    /** Q delle bande come nel motore: le campane si toccano a metà strada tra le vicine. */
    fun bandQ(freqs: List<Float>, i: Int): Float {
        val n = freqs.size
        val oct = when {
            n < 2 -> 2.0
            i == 0 -> log2(freqs[1] / freqs[0]).toDouble()
            i == n - 1 -> log2(freqs[n - 1] / freqs[n - 2]).toDouble()
            else -> 0.5 * log2(freqs[i + 1] / freqs[i - 1]).toDouble()
        }.coerceIn(0.2, 3.0)
        val r = 2.0.pow(oct)
        return (sqrt(r) / (r - 1)).toFloat()
    }

    /** Testo AutoEQ/Poweramp delle impostazioni attuali (una campana per banda). */
    fun export(s: DspSettings): String {
        fun f(v: Float, d: Int) = String.format(Locale.US, "%.${d}f", v)
        val sb = StringBuilder()
        sb.append("Preamp: ").append(f(if (s.preampOn) s.preampDb else 0f, 1)).append(" dB\n")
        val fr = s.freqs
        var n = 1
        for (i in fr.indices) {
            val g = s.graphic.getOrElse(i) { 0f }
            sb.append("Filter ${n++}: ON PK Fc ${f(fr[i], 0)} Hz Gain ${f(g, 1)} dB Q ${f(bandQ(fr, i), 2)}\n")
        }
        return sb.toString()
    }
}
