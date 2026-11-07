package com.marco.mlpod.audio

import android.content.Context
import com.marco.mlpod.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Profili AutoEQ (https://github.com/jaakkopasanen/AutoEq, licenza MIT).
 *
 * Tutti i profili parametrici sono inclusi nell'app (assets/autoeq.tsv.gz: nome, fonte, banco,
 * preamp, filtri). "Aggiorna elenco" scarica l'indice da GitHub: le cuffie aggiunte dopo si
 * scaricano quando le scegli. Le cuffie salvate dall'utente stanno nelle impostazioni.
 */
object AutoEqDb {
    /** [bands] null = profilo da scaricare ([path] relativo a results/ nel repository). */
    data class Entry(
        val name: String,
        val source: String,
        val rig: String,
        val preampDb: Float,
        val bands: List<PeqBand>?,
        val path: String? = null,
        val custom: Boolean = false,
    ) {
        val label: String get() = when {
            custom -> "Salvata da te"
            else -> listOf(source, rig).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val key: String get() = "$name|$source|$rig"
    }

    private const val RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private const val INDEX_FILE = "autoeq_index.md"
    private const val CUSTOM_KEY = "aeq_custom"

    @Volatile private var bundled: List<Entry>? = null

    private fun bundled(ctx: Context): List<Entry> {
        bundled?.let { return it }
        val list = ArrayList<Entry>(9000)
        try {
            GZIPInputStream(ctx.assets.open("autoeq.tsv.gz")).bufferedReader().useLines { lines ->
                for (l in lines) {
                    val p = l.split('\t')
                    if (p.size < 5) continue
                    list.add(Entry(p[0], p[1], p[2], p[3].toFloatOrNull() ?: 0f, parseCompact(p[4])))
                }
            }
        } catch (e: Exception) {
            AppLog.w("AutoEQ", "Elenco incluso non leggibile", e)
        }
        bundled = list
        return list
    }

    private fun parseCompact(s: String): List<PeqBand> = s.split(',').mapNotNull { f ->
        if (f.length < 2) return@mapNotNull null
        val t = when (f[0]) { 'L' -> PeqBand.LS; 'H' -> PeqBand.HS; else -> PeqBand.PK }
        val v = f.substring(1).split(':').mapNotNull { it.toFloatOrNull() }
        if (v.size < 3) null else PeqBand(true, t, v[0], v[1], v[2])
    }.take(DspSettings.PEQ_MAX)

    /** Profili scaricati dall'indice di GitHub che non sono nell'elenco incluso. */
    private fun downloaded(ctx: Context, have: Set<String>): List<Entry> {
        val f = File(ctx.filesDir, INDEX_FILE)
        if (!f.exists()) return emptyList()
        return parseIndex(f.readText()).filter { it.key !in have }
    }

    private val indexRe = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+?)(?: on (.+))?$""")

    private fun parseIndex(md: String): List<Entry> = md.lineSequence().mapNotNull { line ->
        val m = indexRe.find(line.trim()) ?: return@mapNotNull null
        val path = URLDecoder.decode(m.groupValues[2].replace("+", "%2B"), "UTF-8")
        val parts = path.split('/')
        if (parts.size < 3) return@mapNotNull null
        Entry(m.groupValues[1], parts[0], parts.subList(1, parts.size - 1).joinToString("/"), 0f, null, path)
    }.toList()

    fun custom(ctx: Context): List<Entry> = try {
        val a = JSONArray(prefs(ctx).getString(CUSTOM_KEY, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Entry(o.optString("name"), "", "", o.optDouble("pre", 0.0).toFloat(), PeqBand.fromJson(o.optJSONArray("peq")), custom = true)
        }
    } catch (_: Exception) { emptyList() }

    fun saveCustom(ctx: Context, name: String, s: DspSettings) {
        val n = name.trim().take(40)
        if (n.isEmpty()) return
        val keep = custom(ctx).filter { !it.name.equals(n, true) }
        val a = JSONArray()
        (keep + Entry(n, "", "", s.peqPreamp, s.peq, custom = true)).forEach { e ->
            a.put(JSONObject().put("name", e.name).put("pre", e.preampDb.toDouble()).put("peq", PeqBand.toJson(e.bands ?: emptyList())))
        }
        prefs(ctx).edit().putString(CUSTOM_KEY, a.toString()).apply()
    }

    fun deleteCustom(ctx: Context, name: String) {
        val a = JSONArray()
        custom(ctx).filter { it.name != name }.forEach { e ->
            a.put(JSONObject().put("name", e.name).put("pre", e.preampDb.toDouble()).put("peq", PeqBand.toJson(e.bands ?: emptyList())))
        }
        prefs(ctx).edit().putString(CUSTOM_KEY, a.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("autoeq", Context.MODE_PRIVATE)

    /** Elenco completo: cuffie salvate, poi incluse, poi quelle nuove dell'indice. */
    fun all(ctx: Context): List<Entry> {
        val b = bundled(ctx)
        val keys = b.mapTo(HashSet()) { it.key }
        return custom(ctx) + (b + downloaded(ctx, keys)).sortedBy { it.name.lowercase() }
    }

    fun search(list: List<Entry>, q: String, limit: Int = 200): List<Entry> {
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return list.take(limit)
        return list.asSequence().filter { e ->
            val hay = (e.name + " " + e.source + " " + e.rig).lowercase()
            words.all { hay.contains(it) }
        }.take(limit).toList()
    }

    /** Scarica l'indice aggiornato da GitHub. Ritorna quante cuffie nuove ci sono rispetto all'elenco incluso. */
    fun refresh(ctx: Context): Int {
        val md = get("${RAW}INDEX.md")
        val entries = parseIndex(md)
        if (entries.size < 100) throw IllegalStateException("indice non valido")
        File(ctx.filesDir, INDEX_FILE).writeText(md)
        val keys = bundled(ctx).mapTo(HashSet()) { it.key }
        return entries.count { it.key !in keys }
    }

    /** Profilo completo: se non è incluso lo scarica da GitHub. */
    fun resolve(e: Entry): Entry {
        if (e.bands != null) return e
        val path = e.path ?: throw IllegalStateException("percorso mancante")
        val enc = (path.split('/') + "${e.name} ParametricEQ.txt").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val text = get(RAW + enc)
        val p = PresetText.parseParametric(text, e.name).firstOrNull() ?: throw IllegalStateException("profilo non leggibile")
        return e.copy(preampDb = p.preampDb, bands = p.bands)
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "MLEqualizer (Android)")
        }
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
