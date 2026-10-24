package com.marco.mlpod.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlpod.audio.Analyzer
import com.marco.mlpod.audio.Fft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val AnBg = Color(0xFF0C0C0E)
private val AnPanel = Color(0xFF17181C)
private val AnText = Color(0xFFB8BCC6)
private val AnCyan = Color(0xFF00E5FF)
private val AnGreen = Color(0xFF00C853)
private val AnYellow = Color(0xFFFFEB3B)
private val AnRed = Color(0xFFFF1744)

private const val FLOOR = -90f
private const val NB = 31
private const val NFFT = 8192
private const val NG = 256

private fun toDb(v: Float): Float = if (v <= 1e-5f) FLOOR else max(FLOOR, 20f * log10(v))

private fun fdb(v: Float): String =
    if (v <= -89f) "-∞" else String.format(Locale.ITALY, "%.1f", v)

private fun bandFreq(i: Int): Float = 1000f * 2f.pow((i - 17) / 3f)

private fun fmtHz(f: Float): String =
    if (f >= 1000f) String.format(Locale.ITALY, "%.2f kHz", f / 1000f) else "${f.roundToInt()} Hz"

/** Stato e calcoli dell'analizzatore (livelli, PPM, goniometro, 1/3 d'ottava). */
private class AnalyzerState {
    var tick by mutableIntStateOf(0)

    val barDb = FloatArray(2) { FLOOR }
    val barHold = FloatArray(2) { FLOOR }
    private val barHoldT = IntArray(2)
    val tpCur = FloatArray(2) { FLOOR }
    val tpMax = FloatArray(2) { FLOOR }
    val rmsCur = FloatArray(2) { FLOOR }
    val rmsMax = FloatArray(2) { FLOOR }
    val ppm = FloatArray(2) { FLOOR }
    val ppmMax = FloatArray(2) { FLOOR }

    val bands = FloatArray(NB) { FLOOR }
    val bandPeak = FloatArray(NB) { FLOOR }

    val gx = FloatArray(NG)
    val gy = FloatArray(NG)
    var gCount = 0
    var corr = 0f

    private val pk = FloatArray(2)
    private val l = FloatArray(NFFT)
    private val r = FloatArray(NFFT)
    private val re = FloatArray(NFFT)
    private val im = FloatArray(NFFT)
    private val fft = Fft(NFFT)
    private val win = FloatArray(NFFT) { (0.5 - 0.5 * cos(2.0 * PI * it / (NFFT - 1))).toFloat() }
    private var lastPub = 0L

    fun reset() {
        for (c in 0..1) {
            tpMax[c] = FLOOR; rmsMax[c] = FLOOR; ppmMax[c] = FLOOR; barHold[c] = FLOOR
        }
        for (i in 0 until NB) bandPeak[i] = FLOOR
    }

    fun update(an: Analyzer) {
        val pub = an.published
        val active = pub != lastPub && pub > NFFT
        lastPub = pub
        an.takePeaks(pk)

        for (c in 0..1) {
            val p = if (active) toDb(pk[c]) else FLOOR
            tpCur[c] = p
            if (p > tpMax[c]) tpMax[c] = p
            barDb[c] = if (p > barDb[c]) p else max(p, barDb[c] - 1.6f)
            if (barDb[c] >= barHold[c]) {
                barHold[c] = barDb[c]; barHoldT[c] = 0
            } else if (++barHoldT[c] > 40) {
                barHold[c] = max(FLOOR, barHold[c] - 0.6f)
            }
            // PPM: attacco rapido, ritorno lento
            ppm[c] = if (p > ppm[c]) ppm[c] + (p - ppm[c]) * 0.6f else max(p, ppm[c] - 0.45f)
            if (ppm[c] > ppmMax[c]) ppmMax[c] = ppm[c]
        }

        if (!active) {
            for (c in 0..1) rmsCur[c] = FLOOR
            gCount = 0
            for (i in 0 until NB) bands[i] = max(FLOOR, bands[i] - 2f)
            return
        }

        an.copyLatest(l, r, NFFT)

        // RMS e correlazione sugli ultimi 2048 campioni
        val n0 = NFFT - 2048
        var sl = 0.0
        var sr = 0.0
        var slr = 0.0
        for (k in n0 until NFFT) {
            val a = l[k].toDouble()
            val b = r[k].toDouble()
            sl += a * a; sr += b * b; slr += a * b
        }
        val rl = sqrt(sl / 2048.0).toFloat()
        val rr = sqrt(sr / 2048.0).toFloat()
        rmsCur[0] = toDb(rl); rmsCur[1] = toDb(rr)
        for (c in 0..1) if (rmsCur[c] > rmsMax[c]) rmsMax[c] = rmsCur[c]
        val cc = (slr / sqrt(sl * sr + 1e-12)).toFloat().coerceIn(-1f, 1f)
        corr += (cc - corr) * 0.25f

        // Goniometro (ultimi 512 campioni, passo 2)
        var g = 0
        var k = NFFT - 512
        while (k < NFFT && g < NG) {
            gx[g] = (l[k] - r[k]) * 0.7071f
            gy[g] = (l[k] + r[k]) * 0.7071f
            g++; k += 2
        }
        gCount = g

        // FFT 1/3 d'ottava (mono)
        for (i in 0 until NFFT) {
            re[i] = (l[i] + r[i]) * 0.5f * win[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        val binHz = an.sampleRate.toFloat() / NFFT
        val half = NFFT / 2 - 1
        val norm = 16f / (NFFT.toFloat() * NFFT) / 1.5f
        for (i in 0 until NB) {
            val fc = bandFreq(i)
            var lo = Math.ceil((fc / 1.1225f / binHz).toDouble()).toInt()
            var hi = Math.floor((fc * 1.1225f / binHz).toDouble()).toInt()
            lo = lo.coerceIn(1, half)
            hi = hi.coerceIn(1, half)
            if (hi < lo) {
                val b = (fc / binHz).roundToInt().coerceIn(1, half)
                lo = b; hi = b
            }
            var p = 0f
            for (b in lo..hi) p += re[b] * re[b] + im[b] * im[b]
            val db = if (fc > an.sampleRate / 2.1f) FLOOR else toDb(sqrt(p * norm))
            bands[i] = if (db > bands[i]) db else max(db, bands[i] - 1.3f)
            if (bands[i] > bandPeak[i]) bandPeak[i] = bands[i]
        }
    }
}

private fun dbFrac(db: Float, min: Float = -60f): Float = ((db - min) / -min).coerceIn(0f, 1f)

@Composable
fun AnalyzerView(env: AppEnv) {
    val an = env.engine.analyzer
    val st = remember { AnalyzerState() }

    DisposableEffect(Unit) {
        an.enabled = true
        onDispose { an.enabled = false }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            withContext(Dispatchers.Default) { st.update(an) }
            st.tick++
            delay(33)
        }
    }
    LaunchedEffect(env.analyzerResetToken) { st.reset() }

    @Suppress("UNUSED_VARIABLE")
    val frame = st.tick

    Column(
        Modifier
            .fillMaxSize()
            .background(AnBg)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        LevelSection(st)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().height(118.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GonioPanel(st, Modifier.weight(1f))
            PpmDial("L", st.ppm[0], st.ppmMax[0], env.settings.meterRef.toFloat(), Modifier.weight(0.9f))
            PpmDial("R", st.ppm[1], st.ppmMax[1], env.settings.meterRef.toFloat(), Modifier.weight(0.9f))
        }
        Spacer(Modifier.height(4.dp))
        RtaPanel(env, st, Modifier.weight(1f))
    }
}

@Composable
private fun Mono(text: String, color: Color = AnText, size: Int = 10, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace, modifier = modifier, maxLines = 1)
}

@Composable
private fun LevelSection(st: AnalyzerState) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AnPanel)
            .padding(6.dp)
    ) {
        for (c in 0..1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(if (c == 0) "L" else "R", Color.White, 11, Modifier.padding(end = 6.dp))
                Canvas(Modifier.weight(1f).height(11.dp)) {
                    drawRect(Color(0xFF262830), size = size)
                    val w = size.width
                    val brush = Brush.horizontalGradient(
                        0f to AnGreen, 0.7f to AnYellow, 1f to AnRed, startX = 0f, endX = w
                    )
                    drawRect(brush, size = Size(w * dbFrac(st.barDb[c]), size.height))
                    val hx = w * dbFrac(st.barHold[c])
                    drawRect(Color.White, topLeft = Offset(max(0f, hx - 2f), 0f), size = Size(2f, size.height))
                    for (m in listOf(-48f, -36f, -24f, -12f, -6f)) {
                        val x = w * dbFrac(m)
                        drawLine(Color(0x66000000), Offset(x, 0f), Offset(x, size.height), 1f)
                    }
                }
            }
            if (c == 0) Spacer(Modifier.height(3.dp))
        }
        Spacer(Modifier.height(3.dp))
        Mono("PICCO VERO   L ${fdb(st.tpCur[0])}/${fdb(st.tpMax[0])}  R ${fdb(st.tpCur[1])}/${fdb(st.tpMax[1])}", AnCyan)
        Mono("RMS          L ${fdb(st.rmsCur[0])}/${fdb(st.rmsMax[0])}  R ${fdb(st.rmsCur[1])}/${fdb(st.rmsMax[1])}", AnCyan)
        Mono("dBFS  corrente / massimo", AnText, 9)
    }
}

@Composable
private fun GonioPanel(st: AnalyzerState, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AnPanel)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.weight(1f).aspectRatio(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val rad = min(cx, cy) - 2f
                drawCircle(Color(0xFF30333B), rad, Offset(cx, cy), style = Stroke(1f))
                drawLine(Color(0xFF30333B), Offset(cx, cy - rad), Offset(cx, cy + rad), 1f)
                drawLine(Color(0xFF30333B), Offset(cx - rad, cy), Offset(cx + rad, cy), 1f)
                drawLine(Color(0xFF30333B), Offset(cx - rad * 0.7f, cy - rad * 0.7f), Offset(cx + rad * 0.7f, cy + rad * 0.7f), 1f)
                drawLine(Color(0xFF30333B), Offset(cx + rad * 0.7f, cy - rad * 0.7f), Offset(cx - rad * 0.7f, cy + rad * 0.7f), 1f)
                val col = Color(0x9900E5FF)
                for (k in 0 until st.gCount) {
                    val x = cx + (st.gx[k] * rad * 1.15f).coerceIn(-rad, rad)
                    val y = cy - (st.gy[k] * rad * 1.15f).coerceIn(-rad, rad)
                    drawCircle(col, 1.3f, Offset(x, y))
                }
            }
            Mono("L", Color.White, 9, Modifier.align(Alignment.TopStart))
            Mono("R", Color.White, 9, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(2.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(Color(0xFF262830), size = size)
            val mid = size.width / 2f
            val x = mid + st.corr * mid
            val colr = if (st.corr >= 0f) AnGreen else AnRed
            drawRect(colr, topLeft = Offset(min(mid, x), 0f), size = Size(abs(x - mid), size.height))
            drawLine(Color.White, Offset(mid, 0f), Offset(mid, size.height), 1.5f)
        }
        Mono("Fase " + String.format(Locale.ITALY, "%+.2f", st.corr), AnText, 9)
    }
}

@Composable
private fun PpmDial(label: String, value: Float, max: Float, ref: Float, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE9E2C8))
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height * 0.95f
                val rad = min(size.width * 0.48f, size.height * 0.85f)
                val minDb = -40f
                fun ang(db: Float): Float {
                    val f = ((db - minDb) / -minDb).coerceIn(0f, 1f)
                    return ((-90f - 50f + 100f * f) * PI.toFloat() / 180f)
                }
                // zona rossa
                val a0 = ang(-6f)
                val a1 = ang(0f)
                val steps = 8
                for (s in 0 until steps) {
                    val aa = a0 + (a1 - a0) * s / steps
                    val ab = a0 + (a1 - a0) * (s + 1) / steps
                    drawLine(
                        Color(0xFFD32F2F),
                        Offset(cx + cos(aa) * rad * 0.92f, cy + sin(aa) * rad * 0.92f),
                        Offset(cx + cos(ab) * rad * 0.92f, cy + sin(ab) * rad * 0.92f), 4f
                    )
                }
                for (m in listOf(-40, -30, -20, -12, -6, 0)) {
                    val a = ang(m.toFloat())
                    val len = if (m == 0 || m == -12) 0.78f else 0.84f
                    drawLine(
                        Color(0xFF2B2B2B),
                        Offset(cx + cos(a) * rad * len, cy + sin(a) * rad * len),
                        Offset(cx + cos(a) * rad, cy + sin(a) * rad), 2f
                    )
                }
                // livello di riferimento
                val ar = ang(ref)
                drawLine(
                    Color(0xFF1565C0),
                    Offset(cx + cos(ar) * rad * 0.7f, cy + sin(ar) * rad * 0.7f),
                    Offset(cx + cos(ar) * rad * 1.02f, cy + sin(ar) * rad * 1.02f), 3f
                )
                // massimo
                val am = ang(max)
                drawLine(
                    Color(0x99D32F2F),
                    Offset(cx + cos(am) * rad * 0.55f, cy + sin(am) * rad * 0.55f),
                    Offset(cx + cos(am) * rad * 0.8f, cy + sin(am) * rad * 0.8f), 2f, StrokeCap.Round
                )
                // lancetta
                val an2 = ang(value)
                drawLine(
                    Color(0xFF111111),
                    Offset(cx, cy),
                    Offset(cx + cos(an2) * rad * 0.95f, cy + sin(an2) * rad * 0.95f), 3f, StrokeCap.Round
                )
                drawCircle(Color(0xFF111111), 5f, Offset(cx, cy))
            }
            Text(
                label, color = Color(0xFF222222), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Text(
                "PPM", color = Color(0xFF222222), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
        Mono("${fdb(value)}/${fdb(max)}", Color(0xFF222222), 9)
    }
}

@Composable
private fun RtaPanel(env: AppEnv, st: AnalyzerState, modifier: Modifier) {
    val sel = env.analyzerBand.coerceIn(0, NB - 1)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AnPanel)
            .padding(6.dp)
    ) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { o ->
                        env.analyzerBand = ((o.x / size.width) * NB).toInt().coerceIn(0, NB - 1)
                    })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        env.analyzerBand = ((change.position.x / size.width) * NB).toInt().coerceIn(0, NB - 1)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val bw = w / NB
            val minDb = -80f
            fun y(db: Float) = h - h * ((db - minDb) / -minDb).coerceIn(0f, 1f)
            for (m in listOf(-60f, -40f, -20f)) {
                drawLine(Color(0xFF2A2C33), Offset(0f, y(m)), Offset(w, y(m)), 1f)
            }
            for (i in 0 until NB) {
                val x = i * bw
                val top = y(st.bands[i])
                val frac = ((st.bands[i] - minDb) / -minDb).coerceIn(0f, 1f)
                val col = when {
                    frac > 0.9f -> AnRed
                    frac > 0.7f -> AnYellow
                    else -> AnGreen
                }
                drawRect(col, Offset(x + 1.5f, top), Size(bw - 3f, h - top))
                val pyy = y(st.bandPeak[i])
                drawRect(Color.White, Offset(x + 1.5f, pyy), Size(bw - 3f, 2f))
            }
            drawRect(
                AnCyan, Offset(sel * bw, 0f), Size(bw, h), style = Stroke(2f)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Mono("20", modifier = Modifier.weight(7f))
            Mono("100", modifier = Modifier.weight(10f))
            Mono("1k", modifier = Modifier.weight(10f))
            Mono("10k", modifier = Modifier.weight(4f))
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Mono("1/3 ott. ${fmtHz(bandFreq(sel))}", AnCyan, 11)
                Mono("${fdb(st.bands[sel])} / ${fdb(st.bandPeak[sel])} dBFS", Color.White, 11)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2F6FD6))
                    .clickable {
                        env.feedback.tapScreen()
                        env.analyzerResetToken++
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Azzera picchi", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
