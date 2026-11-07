package com.marco.mlpod.ui.eq

import com.marco.mlpod.ui.AppEnv
import com.marco.mlpod.ui.Knob

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlpod.audio.DspSettings
import com.marco.mlpod.audio.EqPart
import com.marco.mlpod.audio.GraphicPresets
import com.marco.mlpod.audio.Modes
import com.marco.mlpod.audio.PresetText
import com.marco.mlpod.audio.ReplayGain
import com.marco.mlpod.util.AppLog
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Equalizzatore (stesso motore e stesse schede di MLEqualizer). [header] = barra in alto dell'app
 * (indietro, titolo); l'interruttore generale è nella prima riga.
 */
@Composable
fun EqPanel(env: AppEnv, appName: String, header: @Composable () -> Unit = {}) {
    val settings = env.settings
    val s = settings.dsp
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var saveDialog by remember { mutableStateOf(false) }

    fun readText(uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        AppLog.e("App", "Lettura file non riuscita", e); null
    }

    fun writeText(uri: Uri, text: String): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
        true
    } catch (e: Exception) {
        AppLog.e("App", "Scrittura file non riuscita", e); false
    }

    val importPreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readText(uri) ?: return@rememberLauncherForActivityResult
        val list = PresetText.parseMulti(text, "Importato")
        if (list.isEmpty()) {
            message = "Il file non contiene un preset riconoscibile (AutoEQ, JSON o GraphicEQ)."
        } else {
            for ((name, part) in list) settings.saveUserPreset(name, part)
            settings.updateDsp { it.withEqPart(list.first().second) }
            message = if (list.size == 1) "Preset \"${list[0].first}\" importato e applicato." else "${list.size} preset importati; applicato \"${list[0].first}\"."
        }
    }
    val exportPreset = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) message = if (writeText(uri, PresetText.export(settings.dsp))) "Preset esportato (formato AutoEQ)." else "Esportazione non riuscita."
    }
    val importPeq = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readText(uri) ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.removeSuffix(" ParametricEQ")?.take(40) ?: "Importato"
        val list = PresetText.parseParametric(text, name)
        if (list.isNotEmpty()) {
            val p = list.first()
            settings.updateDsp { it.copy(eq = true, master = true, eqMode = DspSettings.EQ_PARAMETRIC, peq = p.bands, peqPreamp = p.preampDb, autoEqName = "") }
            message = "Preset \"${p.name}\" applicato al Parametrico: ${p.bands.size} filtri" + (if (list.size > 1) " (il file ne conteneva ${list.size}, applicato il primo)." else ".") +
                (if (p.notes.isNotEmpty()) "\n\nNota: " + p.notes.joinToString("; ") + "." else "")
        } else {
            val g = PresetText.parseMulti(text, name)
            if (g.isEmpty()) message = "Il file non contiene filtri riconoscibili (AutoEQ, Equalizer APO o JSON)."
            else {
                for ((n2, part) in g) settings.saveUserPreset(n2, part)
                settings.updateDsp { it.copy(eqMode = DspSettings.EQ_GRAPHIC).withEqPart(g.first().second) }
                message = "Il file è un preset grafico: importato e applicato nel Grafico."
            }
        }
    }
    val exportPeq = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) message = if (writeText(uri, PresetText.exportParametric(settings.dsp))) "Parametrico esportato (formato AutoEQ / Equalizer APO)." else "Esportazione non riuscita."
    }
    val exportPoweramp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val n = settings.dsp.autoEqName.ifBlank { appName }
            val skipped = PresetText.powerampSkipped(settings.dsp)
            message = if (writeText(uri, PresetText.exportPoweramp(n, settings.dsp)))
                "Parametrico esportato in JSON." + (if (skipped > 0) " $skipped filtri passa-alto/passa-basso/notch non esportati: nel formato JSON quei tipi funzionano in un altro modo." else "")
            else "Esportazione non riuscita."
        }
    }
    Column(Modifier.fillMaxSize().background(Ml.background)) {
        header()
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DSP", color = Ml.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (s.master) "Attivo" else "Spento · audio intatto", color = Ml.textDim, fontSize = 12.sp)
            }
            MlSwitch(s.master) { on -> env.feedback.tapScreen(); settings.updateDsp { it.copy(master = on) } }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "curve") { CurveCard(env, s) }
            // ogni modulo resta sempre al suo posto: spento, la scheda si richiude
            item(key = "eq") {
                EqCard(
                    env, s, mod("eq", s),
                    onSave = { saveDialog = true },
                    onImport = { importPreset.launch(arrayOf("text/*", "application/json", "*/*")) },
                    onExport = { exportPreset.launch("$appName-preset.txt") },
                    onImportPeq = { importPeq.launch(arrayOf("text/*", "application/json", "*/*")) },
                    onExportPeq = { exportPeq.launch("${s.autoEqName.ifBlank { appName }} ParametricEQ.txt") },
                    onExportPoweramp = { exportPoweramp.launch("${s.autoEqName.ifBlank { appName }}.json") },
                    onMessage = { message = it },
                )
            }
            item(key = "pre") { PreampCard(env, s, mod("pre", s)) }
            item(key = "tone") { ToneCard(env, s, mod("tone", s)) }
            item(key = "abass") { AnalogBassCard(env, s, mod("abass", s)) }
            item(key = "air") { AirCard(env, s, mod("air", s)) }
            item(key = "clarity") { ClarityCard(env, s, mod("clarity", s)) }
            item(key = "stereo") { StereoCard(env, s, mod("stereo", s)) }
            item(key = "dbfb") { DbfbCard(env, s, mod("dbfb", s)) }
            item(key = "loud") { LoudnessCard(env, s, mod("loud", s)) }
            item(key = "rg") { ReplayGainCard(env, s, mod("rg", s)) }
            item(key = "headroom") { SimpleCard(env, mod("headroom", s)) }
            item(key = "dc") { SimpleCard(env, mod("dc", s)) }
            item(key = "soft") { SimpleCard(env, mod("soft", s)) }
            item(key = "lim") { LimiterCard(env, s, mod("lim", s)) }
        }
    }

    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.text,
            title = { Text("Salva preset") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Nome") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ml.text, unfocusedTextColor = Ml.text, focusedBorderColor = Ml.primary,
                        unfocusedBorderColor = Ml.outlineVariant, focusedLabelColor = Ml.primary, unfocusedLabelColor = Ml.textDim, cursorColor = Ml.primary,
                    )
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { settings.saveUserPreset(name, s.eqPart()); saveDialog = false }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Annulla") } },
        )
    }
    message?.let { m -> InfoDialog(appName, m) { message = null } }
}


// ------------------------------------------------------------------ schede

/** Intestazione e interruttore comuni: la scheda resta al suo posto e, spenta, si richiude. */
@Composable
private fun ModCard(env: AppEnv, m: ModInfo, subtitle: String = m.sub, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ModuleCard(
        m.title, subtitle, m.info, enabled = m.on,
        onToggle = { on -> env.feedback.tapScreen(); env.settings.updateDsp { m.setOn(it, on) } },
        content = content,
    )
}

@Composable
private fun SimpleCard(env: AppEnv, m: ModInfo) = ModCard(env, m) {}

@Composable
private fun EqCard(
    env: AppEnv, s: DspSettings, m: ModInfo, onSave: () -> Unit, onImport: () -> Unit, onExport: () -> Unit,
    onImportPeq: () -> Unit, onExportPeq: () -> Unit, onExportPoweramp: () -> Unit, onMessage: (String) -> Unit,
) {
    val st = env.settings
    // ordine della tendina: Parametrico, AutoEQ, Grafico
    val modes = listOf(DspSettings.EQ_PARAMETRIC, DspSettings.EQ_AUTOEQ, DspSettings.EQ_GRAPHIC)
    val sub = when (s.eqMode) {
        DspSettings.EQ_PARAMETRIC -> "Parametrico · ${s.peq.count { it.on }} filtri"
        DspSettings.EQ_AUTOEQ -> "AutoEQ · ${s.autoEqName.ifBlank { "nessuna cuffia" }}"
        else -> "Grafico · ${s.bands} bande · ${if (s.faithful) "curva fedele" else "bande indipendenti"}"
    }
    ModCard(env, m, sub) {
        Choices(listOf("Parametrico", "AutoEQ", "Grafico"), modes.indexOf(s.eqMode).coerceAtLeast(0)) { i ->
            env.feedback.tapScreen()
            st.updateDsp { it.copy(eqMode = modes[i]) }
        }
        when (s.eqMode) {
            DspSettings.EQ_PARAMETRIC -> ParametricContent(env, s, onImportPeq, onExportPeq, onExportPoweramp, onMessage)
            DspSettings.EQ_AUTOEQ -> AutoEqContent(env, s, onMessage)
            else -> GraphicContent(env, s, onSave, onImport, onExport)
        }
    }
}

@Composable
private fun GraphicContent(env: AppEnv, s: DspSettings, onSave: () -> Unit, onImport: () -> Unit, onExport: () -> Unit) {
    val st = env.settings
    val res = remember(s) { env.preview.compute(s) }
    run {
        Text("Numero di bande", color = Ml.textDim, fontSize = 12.sp)
        Choices(DspSettings.BAND_OPTIONS.map { "$it" }, DspSettings.BAND_OPTIONS.indexOf(s.bands)) { i ->
            env.feedback.tapScreen()
            st.updateDsp { it.withBands(DspSettings.BAND_OPTIONS[i]) }
        }
        // cursori direttamente nella pagina: fino a 10 bande riempiono la larghezza, oltre si scorre
        val fr = s.freqs
        val few = s.bands <= 10
        Row(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .padding(top = 4.dp)
                .then(if (few) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (i in fr.indices) {
                val applied = if (s.faithful && s.bands > 1) res.graphicApplied.getOrNull(i)?.toFloat() else null
                GainColumn(
                    fmtHzShort(fr[i]), s.graphic.getOrElse(i) { 0f }, DspSettings.GAIN, applied, Ml.primary,
                    modifier = if (few) Modifier.weight(1f) else Modifier.width(40.dp),
                    onChange = { v -> env.feedback.softTick(); st.updateDsp { it.withGain(i, v) } },
                    onReset = { st.updateDsp { it.withGain(i, 0f) } },
                )
            }
        }
        SwitchRow("Curva fedele", "La curva totale passa esattamente dai cursori", s.faithful) { on -> st.updateDsp { it.copy(faithful = on) } }
        Text("Preset", color = Ml.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        PresetRow(env, s)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Salva", selected = false, onClick = onSave)
            Pill("Importa", selected = false, onClick = onImport)
            Pill("Esporta", selected = false, onClick = onExport)
            Pill("Azzera", selected = false) { st.updateDsp { it.copy(graphic = List(it.bands) { 0f }) } }
        }
        Text("Trascina un cursore in su o in giù; doppio tocco per azzerarlo.", color = Ml.textFaint, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(env: AppEnv, s: DspSettings) {
    val settings = env.settings
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    fun matches(p: EqPart) = s.freqs.indices.all { i -> kotlin.math.abs(s.graphic.getOrElse(i) { 0f } - p.gainAt(s.freqs[i])) < 0.05f }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (u in settings.userPresets) {
            val sel = matches(u.part)
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (sel) Ml.primary else Ml.primaryContainer)
                    .combinedClickable(onClick = { settings.updateDsp { it.withEqPart(u.part) } }, onLongClick = { confirmDelete = u.name })
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(u.name, color = if (sel) Ml.onPrimary else Ml.text, fontSize = 14.sp) }
        }
        for (p in GraphicPresets.all) {
            Pill(p.name, matches(p.part)) { settings.updateDsp { it.withEqPart(p.part) } }
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.textDim,
            title = { Text("Eliminare \"$name\"?") },
            text = { Text("Il preset salvato verrà rimosso.") },
            confirmButton = { TextButton(onClick = { settings.deleteUserPreset(name); confirmDelete = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun GainColumn(label: String, value: Float, range: Float, applied: Float?, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onChange: (Float) -> Unit, onReset: () -> Unit) {
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(fmt1(value), color = Ml.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        VerticalGain(value, range, color, onChange, onReset, Modifier.weight(1f).fillMaxWidth())
        Text(label, color = Ml.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(applied?.let { fmt1(it) } ?: " ", color = Ml.textFaint, fontSize = 8.sp, maxLines = 1)
    }
}

/** Cursore verticale: trascinamento relativo (non salta dove si tocca), passi da 0,5 dB, doppio tocco = 0. */
@Composable
private fun VerticalGain(value: Float, range: Float, color: androidx.compose.ui.graphics.Color, onChange: (Float) -> Unit, onReset: () -> Unit, modifier: Modifier) {
    val change = rememberUpdatedState(onChange)
    val reset = rememberUpdatedState(onReset)
    val cur = rememberUpdatedState(value)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset.value() }) }
            .pointerInput(Unit) {
                var startV = 0f
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { startV = cur.value; acc = 0f },
                    onVerticalDrag = { c, dy ->
                        c.consume()
                        acc += dy
                        val pad = 10.dp.toPx()
                        val h = (size.height - 2 * pad).coerceAtLeast(1f)
                        val v = startV - acc / h * (2 * range)
                        val q = ((v * 2).roundToInt() / 2f).coerceIn(-range, range)
                        if (q != cur.value) change.value(q)
                    }
                )
            }
    ) {
        val pad = 10.dp.toPx()
        val cx = size.width / 2f
        val h = size.height - 2 * pad
        fun y(v: Float) = pad + (1f - (v + range) / (2 * range)) * h
        drawLine(Ml.outlineVariant, Offset(cx, pad), Offset(cx, size.height - pad), 4.dp.toPx(), StrokeCap.Round)
        drawLine(Ml.outline, Offset(cx - 7.dp.toPx(), y(0f)), Offset(cx + 7.dp.toPx(), y(0f)), 1.dp.toPx())
        drawLine(color, Offset(cx, y(0f)), Offset(cx, y(value)), 4.dp.toPx(), StrokeCap.Round)
        val tw = 22.dp.toPx(); val th = 12.dp.toPx()
        drawRoundRect(color, Offset(cx - tw / 2, y(value) - th / 2), Size(tw, th), CornerRadius(th / 2, th / 2))
    }
}

@Composable
private fun PreampCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    ModCard(env, m, if (s.preampOn) "Guadagno d'ingresso ${fmtDb(s.preampDb)}" else m.sub) {
        LabeledSlider("Preamp", s.preampDb, -12f..12f, fmtDb(s.preampDb)) { v -> st.updateDsp { it.copy(preampDb = (v * 2).roundToInt() / 2f) } }
        if (s.preampDb > 0f && s.limiter) Text("Sopra 0 dB il limiter può dover ridurre i picchi.", color = Ml.textFaint, fontSize = 11.sp)
    }
}

@Composable
private fun ToneCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val settings = env.settings
    var dialog by remember { mutableStateOf<Boolean?>(null) }  // true = bassi, false = alti
    ModCard(env, m) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToneKnob(env, "Bassi", s.bassDb, s.bassFreq, s.bassQ, Ml.tertiary,
                onChange = { v -> settings.updateDsp { it.copy(bassDb = v) } }, onEdit = { dialog = true })
            ToneKnob(env, "Alti", s.trebleDb, s.trebleFreq, s.trebleQ, Ml.primary,
                onChange = { v -> settings.updateDsp { it.copy(trebleDb = v) } }, onEdit = { dialog = false })
        }
    }
    dialog?.let { bass -> ToneDialog(env, s, bass) { dialog = null } }
}

@Composable
private fun AnalogBassCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    ModCard(env, m) {
        LabeledSlider("Calore (Pultec)", s.analogWarmth, 0f..1f, String.format(Locale.ITALY, "+%.1f dB", 8f * s.analogWarmth)) { v -> st.updateDsp { it.copy(analogWarmth = v) } }
        LabeledSlider("Drive", s.analogDrive, 0f..1f, fmtPct(s.analogDrive)) { v -> st.updateDsp { it.copy(analogDrive = v) } }
        val freqs = listOf(20f, 30f, 60f, 100f)
        Text("Frequenza Pultec", color = Ml.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Choices(freqs.map { "${it.roundToInt()} Hz" }, freqs.indexOfFirst { kotlin.math.abs(it - s.analogFreq) < 0.5f }) { i -> st.updateDsp { it.copy(analogFreq = freqs[i]) } }
    }
}

@Composable
private fun AirCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    ModCard(env, m) {
        LabeledSlider("Quantità", s.airAmount, 0f..1f, fmtPct(s.airAmount)) { v -> st.updateDsp { it.copy(airAmount = v) } }
    }
}

@Composable
private fun ClarityCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    ModCard(env, m) {
        Text("Uscita", color = Ml.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Choices(Modes.clarity, s.clarityPreset) { i -> st.updateDsp { it.copy(clarityPreset = i) } }
        LabeledSlider("Quantità", s.clarityAmount, 0f..1f, fmtPct(s.clarityAmount)) { v -> st.updateDsp { it.copy(clarityAmount = v) } }
    }
}

@Composable
private fun StereoCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    ModCard(env, m) {
        Text("Surround+", color = Ml.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Choices(Modes.surround, s.surroundMode) { i -> st.updateDsp { it.copy(surroundMode = i) } }
        val bal = s.balance
        LabeledSlider("Bilanciamento", bal, -1f..1f, when { kotlin.math.abs(bal) < 0.01f -> "Centro"; bal < 0 -> "S ${(-bal * 100).roundToInt()}%"; else -> "D ${(bal * 100).roundToInt()}%" }) { v ->
            st.updateDsp { it.copy(balance = if (kotlin.math.abs(v) < 0.03f) 0f else v) }
        }
        LabeledSlider("Espansione", s.expand, 0f..1f, fmtPct(s.expand)) { v -> st.updateDsp { it.copy(expand = v, mono = false) } }
        LabeledSlider("Crossfeed (cuffie)", s.crossfeed, 0f..1f, if (s.crossfeed < 0.01f) "Spento" else fmtPct(s.crossfeed)) { v -> st.updateDsp { it.copy(crossfeed = v) } }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Stereo", !s.mono) { st.updateDsp { it.copy(mono = false) } }
            Pill("Mono", s.mono) { st.updateDsp { it.copy(mono = true) } }
        }
    }
}

@Composable
private fun DbfbCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    ModCard(env, m) {
        Choices(listOf("Normale", "Alto"), (s.dbfbMode - 1).coerceIn(0, 1)) { i -> st.updateDsp { it.copy(dbfbMode = i + 1) } }
    }
}

@Composable
private fun LoudnessCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    val vol = env.engine.eqProcessor.listenVolume
    val volDb = if (vol > 0) 20 * kotlin.math.log10(vol) else -80.0
    ModCard(env, m) {
        LabeledSlider("Quantità", s.loudnessAmount, 0f..1f, fmtPct(s.loudnessAmount)) { v -> st.updateDsp { it.copy(loudnessAmount = v) } }
        Text(String.format(Locale.ITALY, "Volume attuale: %.0f dB", volDb), color = Ml.textFaint, fontSize = 12.sp)
    }
}

@Composable
private fun ReplayGainCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    val info = env.engine.eqProcessor.info
    val rg = info.replayGainDb
    val sub = when {
        !m.on -> m.sub
        rg == null && env.engine.scanningCurrent -> "Nessun tag: sto misurando il brano…"
        rg == null -> "Nessun valore per il brano attuale"
        else -> String.format(Locale.ITALY, "Brano attuale: %+.1f dB", rg) + if (info.replayGainSource == "analisi") " (misurato)" else " (dai tag)"
    }
    ModCard(env, m, sub) {
        Choices(listOf("Brano", "Album"), (s.replayGain - 1).coerceIn(0, 1)) { i -> st.updateDsp { it.copy(replayGain = i + 1) } }
    }
}

@Composable
private fun LimiterCard(env: AppEnv, s: DspSettings, m: ModInfo) {
    val st = env.settings
    val red by produceState(0f, s.limiter) {
        while (s.limiter) { value = env.engine.eqProcessor.meters(false).limiterDb; delay(400) }
    }
    ModCard(env, m, if (s.limiter && red < -0.05f) String.format(Locale.ITALY, "Riduzione fino a %.1f dB", -red) else m.sub) {
        LabeledSlider("Soglia", s.limiterThresholdDb, -12f..0f, "${fmt1(s.limiterThresholdDb)} dBFS") { v -> st.updateDsp { it.copy(limiterThresholdDb = (v * 10).roundToInt() / 10f) } }
        LabeledSlider("Rilascio", s.limiterReleaseMs, 5f..500f, "${s.limiterReleaseMs.roundToInt()} ms") { v -> st.updateDsp { it.copy(limiterReleaseMs = ((v / 5).roundToInt() * 5).toFloat()) } }
    }
}

// ------------------------------------------------------------------ registro dei moduli

/** Modulo: titolo, descrizione, spiegazione e interruttore. */
internal class ModInfo(
    val key: String,
    val title: String,
    val sub: String,
    val info: String,
    val on: Boolean,
    val setOn: (DspSettings, Boolean) -> DspSettings,
)

private fun mod(key: String, s: DspSettings): ModInfo = when (key) {
    "eq" -> ModInfo(
        key, "Equalizzatore", "Parametrico, AutoEQ o grafico",
        "Tre modi, uno alla volta.\n\nParametrico: fino a 16 filtri (campana, shelf, passa-alto, passa-basso, passa-banda, " +
            "notch) con frequenza, guadagno e Q. Importa i file AutoEQ / Equalizer APO e i file JSON così come " +
            "sono; esporta in testo AutoEQ o in JSON.\n\nAutoEQ: cerca la tua cuffia tra gli oltre 8800 " +
            "profili di AutoEQ (inclusi nell'app; \"Aggiorna elenco\" scarica quelli nuovi da GitHub). Il profilo finisce " +
            "nel Parametrico, dove puoi ritoccarlo e salvarlo come cuffia nuova.\n\nGrafico:" +
            " equalizzatore grafico: scegli quante bande vuoi (3, 5, 7, 10, 15 o 31) e trascina i cursori direttamente " +
            "qui. Le frequenze sono quelle standard (ISO per 10, 15 e 31 bande); la larghezza di ogni banda è calcolata " +
            "dalla distanza dalle vicine.\n\nCurva fedele: le campane vicine si sommano (con tutti i cursori a +6 dB " +
            "si arriverebbe oltre +9 dB); il motore ricalcola i guadagni perché la curva totale passi esattamente dai " +
            "cursori. Sotto ogni cursore, in piccolo, il guadagno reale.\n\nPreset: di fabbrica, salvati da te, " +
            "importati da AutoEQ o da file JSON; si adattano a qualsiasi numero di bande. Tieni premuto un tuo preset per eliminarlo.",
        s.eq,
    ) { d, on -> d.copy(eq = on, master = d.master || on) }
    "pre" -> ModInfo(
        key, "Preamp", "Guadagno all'ingresso del DSP",
        "Alza o abbassa il livello all'ingresso del DSP, da -12 a +12 dB. Spento vale 0 dB. Per evitare la " +
            "saturazione quando rinforzi c'è anche l'headroom automatico, più sotto.",
        s.preampOn,
    ) { d, on -> d.copy(preampOn = on, master = d.master || on) }
    "tone" -> ModInfo(
        key, "Toni", "Bassi e alti: shelf con frequenza e Q",
        "Due filtri a scaffale (shelf), come le classiche manopole dei lettori. La manopola dà da 0 a +10 dB; la frequenza " +
            "dice da dove parte il rinforzo e il Q quanto è ripida la salita (0,10 = molto dolce, 0,71 = netta senza " +
            "risalite, 2 = gradino con una piccola sovraelongazione). Predefiniti: bassi 90 Hz Q 0,80, alti 10 kHz Q 0,60. " +
            "Tocca la frequenza sotto una manopola per cambiarla.",
        s.tone,
    ) { d, on -> d.copy(tone = on, master = d.master || on) }
    "abass" -> ModInfo(
        key, "Analog Bass", "Bassi da console vintage: stile Pultec e saturazione",
        "Rinforzo a scaffale alla frequenza scelta e, nello stesso momento, un leggero taglio due ottave sopra (il " +
            "trucco dell'equalizzatore Pultec EQP-1A): il sub-basso si gonfia e la zona \"fangosa\" si pulisce. " +
            "Calore regola entrambi (fino a +8 / -4 dB).\n\nDrive: la banda sotto i 70 Hz passa da una saturazione " +
            "morbida che aggiunge armoniche, così il basso si sente più pieno anche su cuffie piccole. I segnali deboli " +
            "e i medi non cambiano.",
        s.analogBass,
    ) { d, on -> d.copy(analogBass = on, master = d.master || on) }
    "air" -> ModInfo(
        key, "Hi-Res Air", "Più aria e dettaglio sopra i 6 kHz",
        "Due shelf morbidi (6 kHz e 12 kHz) che alzano la zona dell'\"aria\": piatti, ambiente, respiro delle voci. " +
            "Fino a circa +4,5 dB in cima alla gamma; sotto i 2 kHz non cambia nulla.",
        s.air,
    ) { d, on -> d.copy(air = on, master = d.master || on) }
    "stereo" -> ModInfo(
        key, "Surround+ e stereo", "Immagine più ampia con le voci al centro",
        "Surround+: un \"sorriso\" uguale nelle due orecchie (bassi e acuti; i medi delle voci restano intatti) e un " +
            "allargamento del solo canale laterale sopra i 2,5 kHz. Tradizionale +4 dB, Palco frontale +2,5 dB con più " +
            "presenza delle voci e nessun allargamento, Ultra Wide +6 dB e allargamento massimo.\n\n" +
            "Bilanciamento: abbassa il lato opposto, non alza mai. Espansione: allarga il laterale sopra ~150 Hz (i bassi " +
            "restano al centro). Crossfeed (cuffie): riduce la separazione dei bassi come ascoltando dei diffusori; il " +
            "suono al centro non cambia. Mono: somma i due canali.",
        s.stereo,
    ) { d, on -> d.copy(stereo = on, master = d.master || on) }
    "dbfb" -> ModInfo(
        key, "DBFB · bassi dinamici", "Più impatto sui colpi di cassa e basso",
        "Sotto i 260 Hz confronta il livello istantaneo con quello medio: quando arriva un colpo lo rinforza (fino a " +
            "+4 dB in Normale, +7 dB in Alto), mentre i suoni costanti restano come sono.",
        s.dbfbMode > 0,
    ) { d, on -> d.copy(dbfbMode = if (on) maxOf(1, d.dbfbMode) else 0, master = d.master || on) }
    "loud" -> ModInfo(
        key, "Loudness", "Bassi e acuti compensati quando il volume è basso",
        "A volume basso l'orecchio sente meno bassi e acuti (curve isofoniche). La loudness li compensa in base al " +
            "volume del telefono: a volume pieno non fa nulla, abbassando aggiunge gradualmente bassi (fino a +12 dB) " +
            "e acuti (fino a +6 dB).",
        s.loudness,
    ) { d, on -> d.copy(loudness = on, master = d.master || on) }
    "rg" -> ModInfo(
        key, "ReplayGain", "Stesso volume per tutti i brani",
        "Livella i brani in base ai tag ReplayGain del file (FLAC, Ogg, MP3, M4A, Opus), per brano o per album. Se il " +
            "file non ha tag, l'app ne misura la loudness (ITU-R BS.1770 / EBU R128) in background e la ricorda. Il " +
            "guadagno non porta mai il brano oltre 0 dBFS di picco.",
        s.replayGain != ReplayGain.OFF,
    ) { d, on -> d.copy(replayGain = if (on) maxOf(ReplayGain.TRACK, d.replayGain) else ReplayGain.OFF, master = d.master || on) }
    "clarity" -> ModInfo(
        key, "Clarity", "Suono da studio: corpo, presenza, aria, bassi mono",
        "Catena \"Studio Master Clarity\" presa da LastWave: taglia il sub-sonico sotto i 24 Hz, dà corpo ai bassi " +
            "(+3,2 dB a 72 Hz), pulisce la zona fangosa (-3 dB a 280 Hz) e le risonanze \"a scatola\" (750 Hz), " +
            "porta avanti voci e strumenti (+3,8 dB a 3,4 kHz) e aggiunge aria sopra i 10,5 kHz. Un exciter leggero " +
            "crea armoniche sopra i 6 kHz e i bassi sotto i 130 Hz vanno al centro (più compatti), con l'immagine " +
            "stereo un po' più larga.\n\nUscita: Riferimento, Altoparlante (meno bassi e aria), Cuffie (bassi e " +
            "presenza più morbidi), DAC (acuti e exciter più gentili). Quantità dosa tutta la catena.",
        s.clarity,
    ) { d, on -> d.copy(clarity = on, master = d.master || on) }
    "dc" -> ModInfo(
        key, "Filtro anti-DC", "Toglie la componente continua (10 Hz)",
        "Alcuni file hanno una piccola componente continua (il segnale non oscilla intorno allo zero): sprecava " +
            "spazio prima del limiter e faceva \"tic\" all'avvio. Il filtro (un polo a 10 Hz, da LastWave) la toglie; " +
            "sulla musica è trasparente (-1 dB a 20 Hz).",
        s.dcBlock,
    ) { d, on -> d.copy(dcBlock = on) }
    "soft" -> ModInfo(
        key, "Saturazione morbida", "Arrotonda i picchi invece di tagliarli",
        "Prima del limiter: fino a -1,5 dBFS il segnale passa intatto, sopra viene arrotondato con una curva tanh che " +
            "si avvicina dolcemente a -0,5 dBFS senza mai superarlo, come un nastro analogico (idea di LastWave). " +
            "I picchi suonano meno duri e il limiter lavora meno.",
        s.softSat,
    ) { d, on -> d.copy(softSat = on, master = d.master || on) }
    "headroom" -> ModInfo(
        key, "Headroom automatico", "Abbassa l'ingresso di quanto rinforzi",
        "Il motore calcola il rinforzo massimo di equalizzatore, toni e moduli e abbassa l'ingresso di altrettanto: " +
            "niente saturazione e il limiter non deve lavorare. Il volume percepito scende un po' quando rinforzi; è voluto.",
        s.autoHeadroom,
    ) { d, on -> d.copy(autoHeadroom = on) }
    else -> ModInfo(
        key, "Limiter", "True-peak, look-ahead 5 ms",
        "Garantisce che l'uscita non superi la soglia: guarda avanti di 5 ms, vede anche i picchi tra un campione e " +
            "l'altro (true-peak) e non segue la forma d'onda dei bassi profondi, così non li distorce. Se lavora molto " +
            "(riduzione di diversi dB) abbassa il preamp o lascia acceso l'headroom automatico.",
        s.limiter,
    ) { d, on -> d.copy(limiter = on) }
}

// ------------------------------------------------------------------ curva e toni

@Composable
private fun CurveCard(env: AppEnv, s: DspSettings) {
    val res = remember(s) { env.preview.compute(s) }
    val info = env.engine.eqProcessor.info
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ml.panel).padding(16.dp)
    ) {
        Text("Curva", color = Ml.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (s.master) "Risposta di EQ, toni e moduli, calcolata dal motore" else "DSP spento: la curva è un'anteprima",
            color = Ml.textDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        ResponseCurve(env.preview.freqs, res.response, Modifier.fillMaxWidth().height(150.dp))
        FreqAxis()
        Spacer(Modifier.height(6.dp))
        Text(statusLine(info), color = Ml.textFaint, fontSize = 11.sp)
    }
}

private fun statusLine(i: com.marco.mlpod.audio.DspInfo): String {
    if (i.sampleRate <= 0) return "In attesa di un brano"
    val hz = if (i.sampleRate % 1000 == 0) "${i.sampleRate / 1000} kHz" else String.format(Locale.ITALY, "%.1f kHz", i.sampleRate / 1000.0)
    val state = if (i.bypass) "Bypass" else "DSP · ${i.modules.joinToString(", ").ifEmpty { "nessun modulo" }}"
    return "$state · uscita ${i.outEncoding.removePrefix("PCM ")} · $hz"
}



@Composable
private fun ToneKnob(env: AppEnv, title: String, db: Float, freq: Float, q: Float, color: androidx.compose.ui.graphics.Color, onChange: (Float) -> Unit, onEdit: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Ml.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(fmtDb(db), color = Ml.textDim, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        val pct = (db / DspSettings.TONE_MAX_DB * 100f).roundToInt().coerceIn(0, 100)
        Knob(
            value = pct, color = color, size = 84.dp,
            onTick = { env.feedback.tapScreen() }, onDetent = { env.feedback.detent() }, zero = 0, stepSize = 5,
            onChange = { v -> onChange(v / 100f * DspSettings.TONE_MAX_DB) }
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(Ml.chip).clickable(onClick = onEdit).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("${fmtHz(freq)} · Q ${fmt2(q)}", color = Ml.text, fontSize = 12.sp, maxLines = 1)
        }
    }
}


@Composable
private fun ToneDialog(env: AppEnv, s: DspSettings, bass: Boolean, onDismiss: () -> Unit) {
    val settings = env.settings
    val freq = if (bass) s.bassFreq else s.trebleFreq
    val q = if (bass) s.bassQ else s.trebleQ
    val lo = if (bass) 20f else 1000f
    val hi = if (bass) 500f else 20000f
    fun setF(f: Float) = settings.updateDsp { if (bass) it.copy(bassFreq = f.coerceIn(lo, hi)) else it.copy(trebleFreq = f.coerceIn(lo, hi)) }
    fun setQ(v: Float) = settings.updateDsp { if (bass) it.copy(bassQ = v.coerceIn(0.1f, 2f)) else it.copy(trebleQ = v.coerceIn(0.1f, 2f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ok") } },
        dismissButton = {
            TextButton(onClick = {
                settings.updateDsp { if (bass) it.copy(bassFreq = 90f, bassQ = 0.8f) else it.copy(trebleFreq = 10000f, trebleQ = 0.6f) }
            }) { Text("Predefiniti") }
        },
        containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.text,
        title = { Text(if (bass) "Bassi: frequenza e Q" else "Alti: frequenza e Q") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Frequenza", freq, 0, "Hz", Modifier.weight(1f)) { setF(it) }
                    NumField("Q", q, 2, "", Modifier.weight(1f)) { setQ(it) }
                }
                val fPos = (kotlin.math.ln(freq / lo) / kotlin.math.ln(hi / lo)).coerceIn(0f, 1f)
                LabeledSlider("Frequenza", fPos, 0f..1f, fmtHz(freq)) { p ->
                    val raw = lo * Math.pow((hi / lo).toDouble(), p.toDouble()).toFloat()
                    val step = if (raw < 100f) 1f else if (raw < 1000f) 5f else if (raw < 10000f) 50f else 100f
                    setF((raw / step).roundToInt() * step)
                }
                LabeledSlider("Q (ripidità)", q, 0.1f..2f, fmt2(q)) { v -> setQ((v * 100).roundToInt() / 100f) }
                Text("0,10 = salita molto dolce · 0,71 = netta senza risalite · 2 = gradino", color = Ml.textFaint, fontSize = 11.sp)
            }
        }
    )
}

