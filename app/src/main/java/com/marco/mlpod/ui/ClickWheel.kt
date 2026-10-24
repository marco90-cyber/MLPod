package com.marco.mlpod.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2

private const val STEP_DEGREES = 20f
private const val TAP_MAX_DEGREES = 14f
private const val LONG_PRESS_MS = 450L
private const val HOLD_REPEAT_MS = 180L

private enum class Zone { Center, Menu, Next, PlayPause, Prev }

/**
 * Ghiera circolare come quella dell'iPod:
 *  - si scorre con il dito lungo l'anello (un "tic" a ogni passo)
 *  - MENU in alto, avanti a destra, play/pausa in basso, indietro a sinistra
 *  - pulsante centrale per selezionare
 */
@Composable
fun ClickWheel(env: AppEnv, style: BodyStyle, size: Dp, modifier: Modifier = Modifier) {
    val pressedCenter = remember { mutableStateOf(false) }
    val envState = rememberUpdatedState(env)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(style.wheel, lerp(style.wheel, Color.Black, 0.10f))
                ),
                radius = r,
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.18f),
                radius = r - 0.75.dp.toPx(),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            val rc = r * 0.40f
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(
                        lerp(style.center, Color.White, 0.25f),
                        lerp(style.center, Color.Black, if (pressedCenter.value) 0.22f else 0.08f)
                    )
                ),
                radius = rc,
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = rc,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Text(
            "MENU",
            color = style.wheelLabel,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = size * 0.085f)
        )
        Icon(
            Icons.Filled.SkipPrevious, contentDescription = "Indietro", tint = style.wheelLabel,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = size * 0.075f)
                .size(size * 0.12f)
        )
        Icon(
            Icons.Filled.SkipNext, contentDescription = "Avanti", tint = style.wheelLabel,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = -size * 0.075f)
                .size(size * 0.12f)
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -size * 0.075f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlayArrow, contentDescription = "Play", tint = style.wheelLabel,
                modifier = Modifier.size(size * 0.10f)
            )
            Icon(
                Icons.Filled.Pause, contentDescription = "Pausa", tint = style.wheelLabel,
                modifier = Modifier.size(size * 0.10f)
            )
        }

        // Gestione dei tocchi sulla ghiera
        Box(
            Modifier
                .size(size)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(this.size.width / 2f, this.size.height / 2f)
                        val radius = this.size.width / 2f
                        val d0 = (down.position - center).getDistance()
                        if (d0 > radius) return@awaitEachGesture
                        down.consume()

                        val e = envState.value
                        val zone = zoneOf(down.position, center, d0, radius)
                        var lastAngle = angleOf(down.position, center)
                        var acc = 0f
                        var travelled = 0f
                        var rotating = false
                        var longHandled = false
                        val startTime = SystemClock.uptimeMillis()
                        if (zone == Zone.Center) pressedCenter.value = true

                        try {
                            while (true) {
                                val elapsed = SystemClock.uptimeMillis() - startTime
                                val hasLong = zone == Zone.Menu || zone == Zone.Next || zone == Zone.Prev
                                val timeout = when {
                                    !hasLong -> 5000L
                                    !longHandled -> (LONG_PRESS_MS - elapsed).coerceAtLeast(1L)
                                    else -> HOLD_REPEAT_MS
                                }
                                val event = withTimeoutOrNull(timeout) { awaitPointerEvent() }
                                if (event == null) {
                                    // Tempo scaduto: pressione lunga o ripetizione
                                    if (hasLong && !rotating) {
                                        if (zone == Zone.Menu) {
                                            if (!longHandled) {
                                                e.feedback.wheelPress()
                                                e.wheel.onMenuLong()
                                            }
                                        } else {
                                            e.wheel.onSeekHold(
                                                if (zone == Zone.Next) 1 else -1,
                                                SystemClock.uptimeMillis() - startTime
                                            )
                                            e.feedback.wheelStep()
                                        }
                                    }
                                    if (hasLong) longHandled = true
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) {
                                    change.consume()
                                    if (!rotating && !longHandled) {
                                        e.feedback.wheelPress()
                                        when (zone) {
                                            Zone.Center -> e.wheel.onCenter()
                                            Zone.Menu -> e.wheel.onMenu()
                                            Zone.Next -> e.wheel.onNext()
                                            Zone.Prev -> e.wheel.onPrev()
                                            Zone.PlayPause -> e.wheel.onPlayPause()
                                        }
                                    }
                                    break
                                }
                                if (zone != Zone.Center && change.positionChange() != Offset.Zero) {
                                    val a = angleOf(change.position, center)
                                    var delta = a - lastAngle
                                    if (delta > 180f) delta -= 360f
                                    if (delta < -180f) delta += 360f
                                    lastAngle = a
                                    travelled += abs(delta)
                                    if (travelled > TAP_MAX_DEGREES) rotating = true
                                    if (rotating) {
                                        acc += delta
                                        while (acc >= STEP_DEGREES) {
                                            acc -= STEP_DEGREES
                                            if (e.wheel.onStep(1)) e.feedback.wheelStep()
                                        }
                                        while (acc <= -STEP_DEGREES) {
                                            acc += STEP_DEGREES
                                            if (e.wheel.onStep(-1)) e.feedback.wheelStep()
                                        }
                                    }
                                }
                                change.consume()
                            }
                        } finally {
                            pressedCenter.value = false
                        }
                    }
                }
        )
    }
}

private fun angleOf(p: Offset, c: Offset): Float =
    Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()

private fun zoneOf(p: Offset, c: Offset, dist: Float, radius: Float): Zone {
    if (dist <= radius * 0.40f) return Zone.Center
    val a = angleOf(p, c) // 0 = destra, 90 = basso, -90 = alto
    return when {
        a > -135f && a <= -45f -> Zone.Menu
        a > -45f && a <= 45f -> Zone.Next
        a > 45f && a <= 135f -> Zone.PlayPause
        else -> Zone.Prev
    }
}
