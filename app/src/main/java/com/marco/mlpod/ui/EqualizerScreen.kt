package com.marco.mlpod.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlpod.audio.EqConfig
import com.marco.mlpod.audio.EqPresets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val BgColor = Color(0xFF0C0C0E)
private val PanelColor = Color(0xFF17181C)
private val ChipColor = Color(0xFF26282E)
private val Accent = Color(0xFF00E5FF)
private val BassColor = Color(0xFF2979FF)

private sealed class FreqTarget {
    data class Band(val index: Int) : FreqTarget()
    data object Bass : FreqTarget()
    data object Treble : FreqTarget()
}

private fun fmtDb(v: Float): String = String.format(Locale.ITALY, "%.1f", v)

private fun fmtFreqShort(f: Float): String =
    if (f >= 1000f) {
        val k = f / 1000f
        (if (abs(k - k.roundToInt()) < 0.05f) "${k.roundToInt()}" else String.format(Locale.ITALY, "%.1f", k)) + "k"
    } else "${f.roundToInt()}"

private fun fmtFreqLong(f: Float): String =
    if (f >= 1000f) String.format(Locale.ITALY, "%.2f kHz", f / 1000f) else "${f.roundToInt()} Hz"

@Composable
fun EqualizerScreen(env: AppEnv) {
    val settings = env.settings
    val eq = settings.eq
    var target by remember { mutableStateOf<FreqTarget?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgColor)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp)
    ) {
        // barra superiore
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { env.overlay = Overlay.None }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Color.White)
            }
            Text(
                "Equalizzatore", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(if (eq.enabled) "Attivo" else "Spento", color = Color(0xFF9AA0AA), fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = eq.enabled,
                onCheckedChange = { on ->
                    env.feedback.tapScreen()
                    settings.updateEq { it.copy(enabled = on) }
                }
            )
            Spacer(Modifier.width(4.dp))
        }

        // numero di bande (menu a tendina)
        var bandsMenu by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bande", color = Color(0xFF9AA0AA), fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(ChipColor)
                        .clickable { env.feedback.tapScreen(); bandsMenu = true }
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${eq.bandCount} bande", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(
                    expanded = bandsMenu,
                    onDismissRequest = { bandsMenu = false },
                    modifier = Modifier.background(PanelColor)
                ) {
                    for (n in EqConfig.MIN_BANDS..EqConfig.MAX_BANDS) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "$n bande",
                                    color = if (n == eq.bandCount) Accent else Color.White,
                                    fontWeight = if (n == eq.bandCount) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                env.feedback.tapScreen()
                                settings.updateEq { it.withBandCount(n) }
                                bandsMenu = false
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // curva di risposta
        ResponseGraph(eq, Modifier.fillMaxWidth().height(74.dp))
        Spacer(Modifier.height(6.dp))

        // cursori
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(PanelColor)
                .padding(vertical = 8.dp, horizontal = 2.dp),
        ) {
            BandColumn(
                label = "Pre", value = eq.preampDb, freqText = "dB",
                color = Color(0xFFB0B4BE), modifier = Modifier.weight(1f),
                onChange = { v -> settings.updateEq { it.copy(preampDb = v) } },
                onReset = { settings.updateEq { it.copy(preampDb = 0f) } },
                onFreqClick = null,
            )
            for (i in 0 until eq.bandCount) {
                val hue = if (eq.bandCount > 1) 350f * i / (eq.bandCount - 1) else 0f
                BandColumn(
                    label = fmtFreqShort(eq.freqs[i]), value = eq.gains[i], freqText = "Hz",
                    color = Color.hsv((hue + 355f) % 360f, 0.85f, 1f), modifier = Modifier.weight(1f),
                    onChange = { v -> settings.updateEq { it.withGain(i, v) } },
                    onReset = { settings.updateEq { it.withGain(i, 0f) } },
                    onFreqClick = { target = FreqTarget.Band(i) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // preset
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Preset", color = Color(0xFF9AA0AA), fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
            for (p in EqPresets.all) {
                Chip(p.name, selected = false) {
                    env.feedback.tapScreen()
                    settings.updateEq { it.withCurve(p.points).copy(enabled = true) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // manopole Bassi / Alti
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(PanelColor)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToneKnob(
                title = "Bassi", percent = eq.bassPercent, freq = eq.bassFreq, color = BassColor,
                onTick = { env.feedback.tapScreen() },
                onChange = { v -> settings.updateEq { it.copy(bassPercent = v) } },
                onFreqClick = { target = FreqTarget.Bass },
            )
            ToneKnob(
                title = "Alti", percent = eq.treblePercent, freq = eq.trebleFreq, color = Accent,
                onTick = { env.feedback.tapScreen() },
                onChange = { v -> settings.updateEq { it.copy(treblePercent = v) } },
                onFreqClick = { target = FreqTarget.Treble },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    target?.let { t ->
        FreqDialog(target = t, eq = eq, onDismiss = { target = null }) { newEq -> settings.eq = newEq }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else ChipColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (selected) Color.Black else Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ToneKnob(
    title: String,
    percent: Int,
    freq: Float,
    color: Color,
    onTick: () -> Unit,
    onChange: (Int) -> Unit,
    onFreqClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("$percent%", color = Color(0xFF9AA0AA), fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Knob(value = percent, color = color, size = 110.dp, onTick = onTick, onChange = onChange)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ChipColor)
                .clickable(onClick = onFreqClick)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text("Freq ${fmtFreqLong(freq)}", color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BandColumn(
    label: String,
    value: Float,
    freqText: String,
    color: Color,
    modifier: Modifier,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    onFreqClick: (() -> Unit)?,
) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        VerticalGainSlider(
            value = value, range = EqConfig.GAIN_RANGE, color = color,
            onChange = onChange, onReset = onReset,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Text(
            fmtDb(value), color = Color(0xFFDDE1EA), fontSize = 10.sp, maxLines = 1,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
        )
        val labelMod = if (onFreqClick != null) {
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ChipColor)
                .clickable(onClick = onFreqClick)
                .padding(horizontal = 3.dp, vertical = 3.dp)
        } else Modifier.padding(horizontal = 3.dp, vertical = 3.dp)
        Box(Modifier.padding(top = 2.dp)) {
            Text(
                label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, textAlign = TextAlign.Center, modifier = labelMod
            )
        }
        Text(freqText, color = Color(0xFF7C828C), fontSize = 9.sp)
    }
}

/** Cursore verticale in stile Poweramp: linea neon dal centro (0 dB) fino alla maniglia. */
@Composable
private fun VerticalGainSlider(
    value: Float,
    range: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val change = rememberUpdatedState(onChange)
    val reset = rememberUpdatedState(onReset)

    fun valueAt(y: Float, h: Float, pad: Float): Float {
        val usable = (h - 2 * pad).coerceAtLeast(1f)
        val frac = ((y - pad) / usable).coerceIn(0f, 1f)
        val v = range - frac * 2f * range
        return (v * 10f).roundToInt() / 10f
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { reset.value() },
                    onTap = { o -> change.value(valueAt(o.y, this.size.height.toFloat(), 20.dp.toPx())) }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { c, _ ->
                    c.consume()
                    change.value(valueAt(c.position.y, this.size.height.toFloat(), 20.dp.toPx()))
                }
            }
    ) {
        val pad = 20.dp.toPx()
        val cx = this.size.width / 2f
        val usable = this.size.height - 2 * pad
        fun yOf(v: Float): Float = pad + (range - v) / (2f * range) * usable

        // guida
        drawLine(
            Color(0xFF3A3D45), Offset(cx, pad), Offset(cx, this.size.height - pad),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
        )
        // tacche +12 / 0 / -12
        for (v in listOf(range, 0f, -range)) {
            val y = yOf(v)
            drawLine(Color(0xFF5A5E68), Offset(cx - 7.dp.toPx(), y), Offset(cx - 3.dp.toPx(), y), 1.dp.toPx())
            drawLine(Color(0xFF5A5E68), Offset(cx + 3.dp.toPx(), y), Offset(cx + 7.dp.toPx(), y), 1.dp.toPx())
        }
        // linea neon
        val y0 = yOf(0f)
        val yv = yOf(value.coerceIn(-range, range))
        if (abs(yv - y0) > 1f) {
            drawLine(color.copy(alpha = 0.25f), Offset(cx, y0), Offset(cx, yv), 9.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(cx, y0), Offset(cx, yv), 3.5.dp.toPx(), StrokeCap.Round)
        }
        // maniglia
        val tw = minOf(this.size.width - 4.dp.toPx(), 30.dp.toPx())
        val th = 38.dp.toPx()
        drawRoundRect(
            Color(0xFF26282E), Offset(cx - tw / 2f, yv - th / 2f), Size(tw, th),
            CornerRadius(10.dp.toPx())
        )
        drawRoundRect(
            Color(0xFF4A4E58), Offset(cx - tw / 2f, yv - th / 2f), Size(tw, th),
            CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx())
        )
        drawLine(
            Color(0xFFE8EAF0), Offset(cx - tw * 0.28f, yv), Offset(cx + tw * 0.28f, yv),
            3.dp.toPx(), StrokeCap.Round
        )
    }
}

/** Grafico della curva di risposta complessiva (bande + bassi + alti). */
@Composable
private fun ResponseGraph(eq: EqConfig, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelColor)
    ) {
        val w = this.size.width
        val h = this.size.height
        val rangeDb = 15f
        fun yOf(db: Float): Float = h / 2f - (db.coerceIn(-rangeDb, rangeDb) / rangeDb) * (h / 2f - 6.dp.toPx())
        // linee guida
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            drawLine(
                if (db == 0f) Color(0xFF4A4E58) else Color(0xFF26282E),
                Offset(0f, yOf(db)), Offset(w, yOf(db)), 1.dp.toPx()
            )
        }
        val points = 160
        val fMin = 20.0
        val fMax = 20000.0
        val path = Path()
        for (i in 0 until points) {
            val t = i / (points - 1.0)
            val f = fMin * (fMax / fMin).pow(t)
            val db = eq.responseDb(f).toFloat()
            val x = (t * w).toFloat()
            val y = yOf(db)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        // marcatori delle bande
        if (eq.enabled) {
            for (i in 0 until eq.bandCount) {
                val t = (ln(eq.freqs[i] / fMin.toFloat()) / ln((fMax / fMin).toFloat())).coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = 0.7f), 2.5.dp.toPx(), Offset(t * w, yOf(eq.gains[i])))
            }
        }
    }
}

/** Dialogo per scegliere la frequenza di una banda o di Bassi / Alti, con anteprima in tempo reale. */
@Composable
private fun FreqDialog(
    target: FreqTarget,
    eq: EqConfig,
    onDismiss: () -> Unit,
    onEq: (EqConfig) -> Unit,
) {
    val title: String
    val min: Float
    val max: Float
    val current: Float
    when (target) {
        is FreqTarget.Band -> {
            title = "Frequenza banda ${target.index + 1}"
            min = EqConfig.MIN_FREQ
            max = EqConfig.MAX_FREQ
            current = eq.freqs.getOrElse(target.index) { 1000f }
        }
        FreqTarget.Bass -> {
            title = "Frequenza dei bassi"
            min = 30f
            max = 500f
            current = eq.bassFreq
        }
        FreqTarget.Treble -> {
            title = "Frequenza degli alti"
            min = 2000f
            max = 16000f
            current = eq.trebleFreq
        }
    }
    val pos = (ln(current / min) / ln(max / min)).coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ok") } },
        containerColor = Color(0xFF1E1F24),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(title) },
        text = {
            Column {
                Text(fmtFreqLong(current), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Accent)
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = pos,
                    onValueChange = { p ->
                        val raw = min * (max / min).pow(p)
                        val step = when {
                            raw < 100f -> 1f
                            raw < 1000f -> 5f
                            raw < 10000f -> 50f
                            else -> 100f
                        }
                        val f = ((raw / step).roundToInt() * step).coerceIn(min, max)
                        val newEq = when (target) {
                            is FreqTarget.Band -> eq.withFreq(target.index, f)
                            FreqTarget.Bass -> eq.copy(bassFreq = f)
                            FreqTarget.Treble -> eq.copy(trebleFreq = f)
                        }
                        onEq(newEq)
                    },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtFreqLong(min), fontSize = 12.sp, color = Color(0xFF9AA0AA))
                    Text(fmtFreqLong(max), fontSize = 12.sp, color = Color(0xFF9AA0AA))
                }
            }
        }
    )
}
