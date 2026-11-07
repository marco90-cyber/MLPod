package com.marco.mlpod.ui.eq

import com.marco.mlpod.ui.AppEnv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlpod.audio.AutoEqDb
import com.marco.mlpod.audio.DspSettings
import com.marco.mlpod.audio.PeqBand
import com.marco.mlpod.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

// ------------------------------------------------------------------ anteprime dei filtri

/** Forma tipica di ogni tipo di filtro (dB) a [x] 0..1 = 20 Hz..20 kHz. */
private fun shapeDb(type: Int, x: Float): Float {
    val f = 20f * 1000f.pow(x)
    val fc = 632f
    val r = (f / fc).toDouble()
    fun db(m: Double) = (20 * log10(m.coerceAtLeast(1e-4))).toFloat().coerceAtLeast(-30f)
    return when (type) {
        PeqBand.LS -> DspSettings.filterDb("LS", fc, 9f, 0.7f, f)
        PeqBand.HS -> DspSettings.filterDb("HS", fc, 9f, 0.7f, f)
        PeqBand.LP -> db(1.0 / hypot(1 - r * r, r / 0.9))
        PeqBand.HP -> db(r * r / hypot(1 - r * r, r / 0.9))
        PeqBand.BP -> db((r / 1.2) / hypot(1 - r * r, r / 1.2))
        PeqBand.NOTCH -> db(kotlin.math.abs(1 - r * r) / hypot(1 - r * r, r / 1.5))
        else -> DspSettings.filterDb("PK", fc, 9f, 1.6f, f)
    }
}

/** Piccola anteprima della curva di un tipo di filtro. */
@Composable
internal fun FilterIcon(type: Int, color: Color, modifier: Modifier = Modifier.size(width = 34.dp, height = 22.dp)) {
    val pts = remember(type) { FloatArray(48) { i -> shapeDb(type, i / 47f) } }
    Canvas(modifier) {
        val lo = pts.min(); val hi = pts.max()
        val span = (hi - lo).coerceAtLeast(1f)
        val pad = size.height * 0.12f
        val path = Path()
        for (i in pts.indices) {
            val x = size.width * i / (pts.size - 1)
            val y = pad + (size.height - 2 * pad) * (1f - (pts[i] - lo) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Scelta del tipo di filtro: tendina con anteprima e nome. */
@Composable
private fun FilterTypePicker(type: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ml.chip).clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterIcon(type, Ml.primary)
            Text(PeqBand.label(type), color = Ml.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("▾", color = Ml.textDim, fontSize = 14.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Ml.dialog)) {
            for (t in PeqBand.TYPES) {
                DropdownMenuItem(
                    leadingIcon = { FilterIcon(t, if (t == type) Ml.primary else Ml.text) },
                    text = { Text(PeqBand.label(t), color = Ml.text, fontSize = 15.sp, fontWeight = if (t == type) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = { if (t == type) Text("✓", color = Ml.primary, fontSize = 15.sp) },
                    onClick = { open = false; onSelect(t) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ parametrico

private fun logPos(v: Float, lo: Float, hi: Float) = (ln(v / lo) / ln(hi / lo)).coerceIn(0f, 1f)
private fun logVal(p: Float, lo: Float, hi: Float) = lo * exp(p * ln(hi / lo))
private fun fmtQ(q: Float) = String.format(Locale.ITALY, "%.2f", q)

@Composable
internal fun ParametricContent(
    env: AppEnv, s: DspSettings,
    onImport: () -> Unit, onExport: () -> Unit, onExportPoweramp: () -> Unit, onMessage: (String) -> Unit,
) {
    val st = env.settings
    val ctx = LocalContext.current
    var edit by remember { mutableIntStateOf(-1) }
    var saveDialog by remember { mutableStateOf(false) }

    fun update(block: (DspSettings) -> DspSettings) = st.updateDsp { d -> block(d).copy(eqMode = DspSettings.EQ_PARAMETRIC) }

    if (s.autoEqName.isNotBlank()) {
        Text("Basato su AutoEQ: ${s.autoEqName}", color = Ml.textDim, fontSize = 12.sp)
    }
    LabeledSlider("Preamp del preset", s.peqPreamp, -20f..0f, fmtDb(s.peqPreamp)) { v ->
        update { it.copy(peqPreamp = (Math.round(v * 10f) / 10f)) }
    }
    if (s.peq.isEmpty()) {
        Text("Nessun filtro. Aggiungine uno o importa un file AutoEQ o JSON.", color = Ml.textFaint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
    s.peq.forEachIndexed { i, b ->
        PeqRow(i, b, onToggle = { on ->
            env.feedback.tapScreen()
            update { d -> d.copy(peq = d.peq.toMutableList().also { l -> l[i] = l[i].copy(on = on) }) }
        }, onClick = { edit = i })
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (s.peq.size < DspSettings.PEQ_MAX) Pill("+ Filtro", selected = false, accent = true) {
            update { d -> d.copy(eq = true, master = true, peq = d.peq + PeqBand(true, PeqBand.PK, 1000f, 0f, 1f)) }
            edit = s.peq.size
        }
        Pill("Importa", selected = false, onClick = onImport)
        Pill("Esporta", selected = false, onClick = onExport)
        Pill("JSON", selected = false, onClick = onExportPoweramp)
        Pill("Salva cuffia", selected = false) { saveDialog = true }
        Pill("Azzera", selected = false) { update { it.copy(peq = emptyList(), peqPreamp = 0f, autoEqName = "") } }
    }
    Text("Tocca un filtro per modificarlo. \"JSON\" esporta il parametrico in formato JSON.", color = Ml.textFaint, fontSize = 11.sp)

    if (edit in s.peq.indices) {
        PeqDialog(env, s.peq[edit],
            onChange = { nb -> val i = edit; update { d -> d.copy(peq = d.peq.toMutableList().also { l -> if (i in l.indices) l[i] = nb }) } },
            onDelete = { val i = edit; edit = -1; update { d -> d.copy(peq = d.peq.filterIndexed { k, _ -> k != i }) } },
            onDismiss = { edit = -1 })
    }
    if (saveDialog) {
        NameDialog("Salva come cuffia", s.autoEqName, onDismiss = { saveDialog = false }) { name ->
            AutoEqDb.saveCustom(ctx, name, st.dsp)
            st.updateDsp { it.copy(autoEqName = name.trim().take(40)) }
            saveDialog = false
            onMessage("\"${name.trim()}\" salvata: la trovi in cima all'elenco AutoEQ.")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeqRow(i: Int, b: PeqBand, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Ml.chip)
            .combinedClickable(onClick = onClick).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${i + 1}", color = Ml.textDim, fontSize = 13.sp, modifier = Modifier.width(22.dp))
        FilterIcon(b.type, if (b.on) Ml.primary else Ml.textFaint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(PeqBand.label(b.type), color = if (b.on) Ml.text else Ml.textFaint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val g = if (b.hasGain) " · ${fmtDb(b.gain)}" else ""
            Text("${fmtHz(b.freq)}$g · Q ${fmtQ(b.q)}", color = if (b.on) Ml.textDim else Ml.textFaint, fontSize = 12.sp)
        }
        MlSwitch(b.on, onToggle)
    }
}

@Composable
private fun PeqDialog(env: AppEnv, b: PeqBand, onChange: (PeqBand) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.text,
        title = { Text("Filtro") },
        text = {
            Column {
                FilterTypePicker(b.type) { t -> env.feedback.tapScreen(); onChange(b.copy(type = t)) }
                LabeledSlider("Frequenza", logPos(b.freq, 20f, 20000f), 0f..1f, fmtHz(b.freq)) { p ->
                    val f = logVal(p, 20f, 20000f)
                    val r = if (f < 100f) Math.round(f).toFloat() else if (f < 1000f) Math.round(f / 5f) * 5f else Math.round(f / 50f) * 50f
                    onChange(b.copy(freq = r.coerceIn(20f, 20000f)))
                }
                if (b.hasGain) LabeledSlider("Guadagno", b.gain, -15f..15f, fmtDb(b.gain)) { v -> onChange(b.copy(gain = Math.round(v * 10f) / 10f)) }
                LabeledSlider("Q (larghezza)", logPos(b.q, 0.1f, 10f), 0f..1f, fmtQ(b.q)) { p -> onChange(b.copy(q = Math.round(logVal(p, 0.1f, 10f) * 100f) / 100f)) }
                Text("Q alto = filtro stretto; Q basso = filtro largo.", color = Ml.textFaint, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fine") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Elimina") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.text,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Nome della cuffia") },
                colors = fieldColors(),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ml.text, unfocusedTextColor = Ml.text, focusedBorderColor = Ml.primary,
    unfocusedBorderColor = Ml.outlineVariant, focusedLabelColor = Ml.primary, unfocusedLabelColor = Ml.textDim, cursorColor = Ml.primary,
)

// ------------------------------------------------------------------ AutoEQ

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AutoEqContent(env: AppEnv, s: DspSettings, onMessage: (String) -> Unit) {
    val st = env.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<AutoEqDb.Entry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<AutoEqDb.Entry?>(null) }

    LaunchedEffect(reload) { all = withContext(Dispatchers.IO) { AutoEqDb.all(ctx.applicationContext) } }

    fun apply(e: AutoEqDb.Entry) {
        busy = "Scarico ${e.name}…"
        scope.launch {
            try {
                val full = withContext(Dispatchers.IO) { AutoEqDb.resolve(e) }
                st.updateDsp {
                    it.copy(eq = true, master = true, eqMode = DspSettings.EQ_AUTOEQ, peq = full.bands ?: emptyList(),
                        peqPreamp = full.preampDb, autoEqName = full.name)
                }
                env.feedback.tapScreen()
            } catch (ex: Exception) {
                AppLog.w("AutoEQ", "Profilo non scaricato", ex)
                onMessage("Non riesco a scaricare il profilo di ${e.name}: serve internet (${ex.message}).")
            }
            busy = null
        }
    }

    Text(
        if (s.autoEqName.isNotBlank()) "Cuffia: ${s.autoEqName}" else "Scegli la tua cuffia",
        color = Ml.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)
    )
    if (s.autoEqName.isNotBlank()) {
        Text("${s.peq.count { it.on }} filtri · preamp ${fmtDb(s.peqPreamp)}", color = Ml.textDim, fontSize = 12.sp)
    }
    OutlinedTextField(
        value = query, onValueChange = { query = it.take(60) }, singleLine = true,
        label = { Text("Cerca (es. Sennheiser HD 650)") }, colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    val list = all
    when {
        list == null -> Text("Carico l'elenco…", color = Ml.textFaint, fontSize = 12.sp)
        else -> {
            val found = remember(list, query) { AutoEqDb.search(list, query, 40) }
            if (found.isEmpty()) Text("Nessuna cuffia trovata. Prova \"Aggiorna elenco\" o un nome più corto.", color = Ml.textFaint, fontSize = 12.sp)
            for (e in found) {
                val selected = e.name == s.autoEqName && s.eqMode == DspSettings.EQ_AUTOEQ
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Ml.primaryContainer else Ml.chip)
                        .combinedClickable(onClick = { apply(e) }, onLongClick = { if (e.custom) delete = e })
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(e.name, color = Ml.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.label + if (e.bands == null) " · da scaricare" else "", color = Ml.textDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("${list.size} profili", color = Ml.textFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    busy?.let { Text(it, color = Ml.primary, fontSize = 12.sp) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Pill("Ritocca nel Parametrico", selected = false, accent = true) { st.updateDsp { it.copy(eqMode = DspSettings.EQ_PARAMETRIC) } }
        Pill("Aggiorna elenco", selected = false) {
            busy = "Aggiorno l'elenco da GitHub…"
            scope.launch {
                try {
                    val n = withContext(Dispatchers.IO) { AutoEqDb.refresh(ctx.applicationContext) }
                    onMessage(if (n == 0) "Elenco aggiornato: nessuna cuffia nuova." else "Elenco aggiornato: $n cuffie nuove (si scaricano quando le scegli).")
                    reload++
                } catch (ex: Exception) {
                    AppLog.w("AutoEQ", "Aggiornamento elenco non riuscito", ex)
                    onMessage("Aggiornamento non riuscito: serve internet (${ex.message}).")
                }
                busy = null
            }
        }
    }
    Text("Profili di AutoEQ (Jaakko Pasanen, licenza MIT). Tieni premuta una cuffia salvata da te per eliminarla.", color = Ml.textFaint, fontSize = 11.sp)

    delete?.let { e ->
        AlertDialog(
            onDismissRequest = { delete = null },
            containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.text,
            title = { Text("Eliminare \"${e.name}\"?") },
            confirmButton = { TextButton(onClick = { AutoEqDb.deleteCustom(ctx, e.name); delete = null; reload++ }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Annulla") } },
        )
    }
}
