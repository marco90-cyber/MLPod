package com.marco.mlpod.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
fun IpodApp(env: AppEnv) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        IpodContent(env)
    }
}

@Composable
private fun IpodContent(env: AppEnv) {
    val activity = LocalContext.current as? Activity
    val style = BodyStyles.byId(env.settings.bodyColorId)

    val lightBody = style.top.luminance() > 0.5f
    SideEffect {
        val w = activity?.window
        if (w != null) {
            val controller = WindowCompat.getInsetsController(w, w.decorView)
            controller.isAppearanceLightStatusBars = lightBody
            controller.isAppearanceLightNavigationBars = lightBody
        }
    }

    LaunchedEffect(env.settings.keepScreenOn) {
        val w = activity?.window ?: return@LaunchedEffect
        if (env.settings.keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Il tasto indietro di Android equivale al tasto MENU della ghiera
    BackHandler {
        if (env.overlay != Overlay.None) {
            env.overlay = Overlay.None
        } else if (!env.nav.pop()) {
            activity?.moveTaskToBack(true)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(style.top, style.bottom)))
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val wheelSize = minOf(maxWidth - 24.dp, maxHeight * 0.40f).coerceAtLeast(200.dp)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Schermo LCD con cornice nera
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .shadow(6.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A0A0C))
                        .padding(4.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        Lcd(env)
                    }
                }
                Spacer(Modifier.height(22.dp))
                ClickWheel(env, style, wheelSize)
            }
        }

        AnimatedVisibility(
            visible = env.overlay == Overlay.Equalizer,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            OverlayHost { EqualizerScreen(env) }
        }
        AnimatedVisibility(
            visible = env.overlay == Overlay.Output,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            OverlayHost { OutputScreen(env) }
        }
        AnimatedVisibility(
            visible = env.overlay == Overlay.Log,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            OverlayHost { LogScreen(env) }
        }
    }
}

/**
 * Contenitore delle schermate a tutto schermo: intercetta i tocchi, così non arrivano
 * alla ghiera e allo schermo LCD che stanno sotto (altrimenti le zone vuote azionano la ghiera).
 */
@Composable
private fun OverlayHost(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
    ) { content() }
}
