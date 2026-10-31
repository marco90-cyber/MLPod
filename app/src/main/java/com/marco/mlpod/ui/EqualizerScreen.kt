package com.marco.mlpod.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlpod.ui.eq.EqPanel
import com.marco.mlpod.ui.eq.Ml
import com.marco.mlpod.ui.eq.MlPalette

/** Colori dell'equalizzatore di MLPod: nero con accento ciano. */
private val PodPalette = MlPalette(
    background = Color(0xFF0C0C0E), panel = Color(0xFF17181C), chip = Color(0xFF26282E), dialog = Color(0xFF1E2026),
    primary = Color(0xFF00E5FF), onPrimary = Color(0xFF00242A), primaryContainer = Color(0xFF00434D), tertiary = Color(0xFF2979FF),
    text = Color.White, textDim = Color(0xFF9AA0AA), textFaint = Color(0xFF6E7480),
    outline = Color(0xFF6E7480), outlineVariant = Color(0xFF34363D),
)

@Composable
fun EqualizerScreen(env: AppEnv) {
    Ml.palette = PodPalette
    Box(Modifier.fillMaxSize().background(PodPalette.background).safeDrawingPadding()) {
        EqPanel(env, "MLPod", header = {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { env.overlay = Overlay.None }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Color.White)
                }
                Text("Equalizzatore", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        })
    }
}
