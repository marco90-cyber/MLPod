package com.marco.mlpod.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mlpod.data.Track
import com.marco.mlpod.data.rememberCover
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private object LcdColors {
    val bg = Color(0xFFF2F4F8)
    val rowText = Color(0xFF111418)
    val subText = Color(0xFF6B7078)
    val headerTop = Color(0xFFF7F8FB)
    val headerBottom = Color(0xFFC9CED8)
    val selTop = Color(0xFF5B9BF0)
    val selBottom = Color(0xFF2F6FD6)
    val paneTop = Color(0xFF05070D)
    val paneBottom = Color(0xFF111A2C)
    val gold = Color(0xFFE6B53A)
    val green = Color(0xFF2FB44A)
}

@Composable
fun Lcd(env: AppEnv) {
    val stack = env.nav.stack
    val entry = stack.last()
    Column(Modifier.fillMaxSize().background(LcdColors.bg)) {
        LcdHeader(env, entry)
        AnimatedContent(
            targetState = entry,
            transitionSpec = {
                val ti = stack.indexOf(targetState)
                val ii = stack.indexOf(initialState)
                val forward = ii >= 0 && ti > ii
                if (forward) {
                    (slideInHorizontally(tween(200)) { it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(200)))
                } else {
                    (slideInHorizontally(tween(200)) { -it / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)))
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "lcd"
        ) { e ->
            when (val s = e.screen) {
                is MenuScreen -> MenuView(env, e, s)
                NowPlayingScreen -> NowPlayingView(env)
                AnalyzerScreen -> AnalyzerView(env)
            }
        }
    }
}

// ---------------------------------------------------------------- intestazione

@Composable
private fun LcdHeader(env: AppEnv, entry: Entry) {
    val title = when (val s = entry.screen) {
        is MenuScreen -> s.title
        NowPlayingScreen -> "In riproduzione"
        AnalyzerScreen -> "Analizzatore"
    }
    val canBack = env.nav.stack.size > 1
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Brush.verticalGradient(listOf(LcdColors.headerTop, LcdColors.headerBottom)))
    ) {
        if (canBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Indietro",
                tint = Color(0xFF3A4150),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(30.dp)
                    .clickable {
                        env.feedback.tapScreen()
                        env.wheel.onMenu()
                    }
                    .padding(2.dp)
            )
        }
        Text(
            title,
            color = LcdColors.rowText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 64.dp)
        )
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (env.engine.isPlaying) {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = null,
                    tint = Color(0xFF3A4150), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            BatteryIcon()
        }
    }
}

@Composable
private fun BatteryIcon() {
    val context = LocalContext.current
    var level by remember { mutableFloatStateOf(1f) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && s > 0) level = l.toFloat() / s
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) receiver.onReceive(context, sticky)
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }
    val fill = when {
        charging -> Color(0xFF3DBE4F)
        level <= 0.2f -> Color(0xFFD9382F)
        else -> Color(0xFF3DBE4F)
    }
    Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
        val bodyW = size.width - 3.dp.toPx()
        drawRoundRect(
            color = Color(0xFF4A505C), size = Size(bodyW, size.height),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx())
        )
        drawRect(
            color = Color(0xFF4A505C),
            topLeft = Offset(bodyW + 0.5.dp.toPx(), size.height * 0.3f),
            size = Size(2.2.dp.toPx(), size.height * 0.4f)
        )
        val pad = 2.dp.toPx()
        drawRoundRect(
            color = fill,
            topLeft = Offset(pad, pad),
            size = Size((bodyW - 2 * pad) * level.coerceIn(0.05f, 1f), size.height - 2 * pad),
            cornerRadius = CornerRadius(1.dp.toPx())
        )
    }
}

// ---------------------------------------------------------------- menu

@Composable
private fun MenuView(env: AppEnv, entry: Entry, screen: MenuScreen) {
    val library = env.library
    val items: List<MenuItem> =
        if (screen.cache) remember(entry, library.revision) { screen.build(env) } else screen.build(env)

    SideEffect { entry.items = items }

    LaunchedEffect(items.size) {
        if (entry.selected >= items.size) entry.selected = max(0, items.size - 1)
    }

    // Mantiene visibile la voce evidenziata quando si usa la ghiera
    LaunchedEffect(entry.selected, items.size) {
        val ls = entry.listState
        val sel = entry.selected
        val info = ls.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty()) {
            if (sel > 0 && items.isNotEmpty()) ls.scrollToItem(sel.coerceAtMost(items.size - 1))
        } else {
            val lastInfo = vis.last()
            val firstFull = if (vis.first().offset < 0) vis.first().index + 1 else vis.first().index
            val lastFull =
                if (lastInfo.offset + lastInfo.size > info.viewportEndOffset) lastInfo.index - 1 else lastInfo.index
            if (sel < firstFull) ls.scrollToItem(sel)
            else if (sel > lastFull) ls.scrollToItem((sel - (lastFull - firstFull)).coerceAtLeast(0))
        }
    }

    val showSplit = screen.split && env.settings.coverInMenu
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(if (showSplit) 0.57f else 1f).fillMaxHeight()) {
            if (items.isEmpty()) {
                Text(
                    "Nessun elemento",
                    color = LcdColors.subText, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            LazyColumn(state = entry.listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items) { index, item ->
                    MenuRow(
                        item = item,
                        selected = index == entry.selected,
                        onClick = {
                            entry.selected = index
                            env.feedback.tapScreen()
                            item.onClick()
                        }
                    )
                }
            }
        }
        if (showSplit) {
            val focus = items.getOrNull(entry.selected)
            val engine = env.engine
            val playing = engine.currentTrack
            val previewTrack: Track? = focus?.track ?: if (focus?.albumKey == null) playing else null
            val albumKey = focus?.albumKey ?: previewTrack?.albumKey
            val isPlayingTrack = previewTrack != null && previewTrack.id == playing?.id
            val hiRes = if (isPlayingTrack) engine.hiResNow else previewTrack?.isHiResFile == true
            val title = focus?.previewTitle ?: previewTrack?.title
            val sub = focus?.previewSub ?: previewTrack?.artist
            CoverPane(
                env = env, albumKey = albumKey, hiRes = hiRes,
                format = previewTrack?.formatLabel, title = title, sub = sub,
                modifier = Modifier.weight(0.43f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, selected: Boolean, onClick: () -> Unit) {
    val textColor = if (selected) Color.White else if (item.dimmed) Color(0xFFA0A5AE) else LcdColors.rowText
    val subColor = if (selected) Color(0xFFDCE8FF) else LcdColors.subText
    val bg = if (selected) Modifier.background(Brush.verticalGradient(listOf(LcdColors.selTop, LcdColors.selBottom)))
    else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .then(bg)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.swatch != null) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(item.swatch)
                    .border(1.dp, Color(0x55000000), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                color = textColor,
                fontSize = 17.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.sub != null) {
                Text(
                    item.sub,
                    color = subColor,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.value != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                item.value,
                color = subColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp)
            )
        }
        if (item.chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = if (selected) Color.White else Color(0xFF7C828C),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- pannello copertina

@Composable
private fun CoverPane(
    env: AppEnv,
    albumKey: String?,
    hiRes: Boolean,
    format: String?,
    title: String?,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    val coverFile = remember(albumKey, env.library.revision) { env.library.coverFile(albumKey) }
    val bmp = rememberCover(coverFile, 480)
    Column(
        modifier.background(Brush.verticalGradient(listOf(LcdColors.paneTop, LcdColors.paneBottom))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            CoverSquare(bmp, Modifier.fillMaxWidth())
            if (hiRes) {
                HiResBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
            }
            if (format != null) {
                FormatChip(format, Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
        if (title != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        if (sub != null) {
            Text(
                sub, color = Color(0xFF9AA3B5), fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun CoverSquare(bmp: androidx.compose.ui.graphics.ImageBitmap?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val side = maxWidth
        Box(
            Modifier
                .size(side)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1B2335)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp, contentDescription = "Copertina",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote, contentDescription = null,
                    tint = Color(0xFF55627F), modifier = Modifier.size(side * 0.45f)
                )
            }
        }
    }
}

@Composable
fun HiResBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFF2CB63), Color(0xFFD19A1F))))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text("HI-RES", color = Color(0xFF4A3300), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun FormatChip(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(LcdColors.green)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ---------------------------------------------------------------- in riproduzione

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "$m:${if (s < 10) "0" else ""}$s"
}

@Composable
private fun NowPlayingView(env: AppEnv) {
    val engine = env.engine
    val track = engine.currentTrack

    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nessun brano in riproduzione", color = LcdColors.subText, fontSize = 16.sp)
        }
        return
    }

    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(track.durationMs) }
    var dragFrac by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(track.id, engine.isPlaying) {
        while (true) {
            pos = engine.player.currentPosition
            val d = engine.player.duration
            dur = if (d > 0) d else track.durationMs
            delay(250)
        }
    }
    LaunchedEffect(env.volumeToken) {
        if (env.volumeToken > 0) {
            env.showVolume = true
            delay(2000)
            env.showVolume = false
        }
    }

    val coverFile = remember(track.albumKey, env.library.revision) { env.library.coverFile(track.albumKey) }
    val cover = rememberCover(coverFile, 720)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "${engine.queueIndex + 1} di ${engine.queueSize}",
            color = LcdColors.subText, fontSize = 12.sp
        )
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = min(maxWidth.value, maxHeight.value).dp
            Box(
                Modifier
                    .size(side)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            env.feedback.tapScreen()
                            engine.togglePlayPause()
                        })
                    }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -90f) { env.feedback.tapScreen(); engine.next() }
                                else if (total > 90f) { env.feedback.tapScreen(); engine.previous() }
                            },
                            onHorizontalDrag = { _, dx -> total += dx }
                        )
                    }
            ) {
                CoverSquare(cover, Modifier.fillMaxSize())
                if (engine.hiResNow) HiResBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                FormatChip(track.formatLabel, Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (!engine.isPlaying) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            track.title, color = LcdColors.rowText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Text(
            track.artist, color = Color(0xFF3A4150), fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Text(
            track.album, color = LcdColors.subText, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        if (engine.audioInfo.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                engine.audioInfo, color = LcdColors.subText, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))

        if (env.showVolume && !env.scrubMode) {
            VolumeBar(engine.volume)
        } else {
            val shownPos = if (dragFrac >= 0f && dur > 0) (dur * dragFrac).toLong() else pos
            val frac = if (dur > 0) (shownPos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(shownPos), color = LcdColors.subText, fontSize = 12.sp, modifier = Modifier.width(38.dp))
                SeekBar(
                    frac = frac,
                    highlighted = env.scrubMode,
                    modifier = Modifier.weight(1f),
                    onDrag = { dragFrac = it },
                    onCommit = { f ->
                        dragFrac = -1f
                        engine.seekToFraction(f)
                    }
                )
                Text(
                    "-" + fmtTime((dur - shownPos).coerceAtLeast(0L)), color = LcdColors.subText, fontSize = 12.sp,
                    textAlign = TextAlign.End, modifier = Modifier.width(44.dp)
                )
            }
            if (env.scrubMode) {
                Text("Scorrimento: ruota la ghiera", color = Color(0xFF2F6FD6), fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToggleIcon(Icons.Filled.Equalizer, false) {
                env.feedback.tapScreen(); env.openAnalyzer()
            }
            ToggleIcon(Icons.Filled.Shuffle, engine.shuffleOn) {
                env.feedback.tapScreen(); engine.toggleShuffle()
            }
            ToggleIcon(
                if (engine.repeatMode == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                engine.repeatMode != 0
            ) {
                env.feedback.tapScreen(); engine.cycleRepeat()
            }
            Box(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.5.dp, Color(0xFF2F6FD6), RoundedCornerShape(8.dp))
                    .clickable {
                        env.feedback.tapScreen(); env.overlay = Overlay.Equalizer
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("EQ", color = Color(0xFF2F6FD6), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (active) Color(0xFF2F6FD6) else Color(0xFF9AA0AA),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SeekBar(
    frac: Float,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    Canvas(
        modifier
            .height(28.dp)
            .padding(horizontal = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o ->
                    val f = (o.x / max(1, size.width)).coerceIn(0f, 1f)
                    onCommit(f)
                })
            }
            .pointerInput(Unit) {
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        last = (o.x / max(1, size.width)).coerceIn(0f, 1f)
                        onDrag(last)
                    },
                    onDragEnd = { onCommit(last) },
                    onDragCancel = { onCommit(last) },
                    onHorizontalDrag = { change, _ ->
                        last = (change.position.x / max(1, size.width)).coerceIn(0f, 1f)
                        onDrag(last)
                    }
                )
            }
    ) {
        val h = 8.dp.toPx()
        val y = (size.height - h) / 2f
        drawRoundRect(
            color = Color(0xFFC3C8D2), topLeft = Offset(0f, y), size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2)
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(LcdColors.selTop, LcdColors.selBottom)),
            topLeft = Offset(0f, y), size = Size(max(h, size.width * frac), h),
            cornerRadius = CornerRadius(h / 2)
        )
        if (highlighted) {
            val cx = size.width * frac
            drawCircle(color = Color.White, radius = 9.dp.toPx(), center = Offset(cx, size.height / 2))
            drawCircle(color = LcdColors.selBottom, radius = 6.dp.toPx(), center = Offset(cx, size.height / 2))
        }
    }
}

@Composable
private fun VolumeBar(volume: Float) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.VolumeDown, null, tint = LcdColors.subText, modifier = Modifier.size(20.dp))
        Canvas(Modifier.weight(1f).height(28.dp).padding(horizontal = 6.dp)) {
            val h = 8.dp.toPx()
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = Color(0xFFC3C8D2), topLeft = Offset(0f, y), size = Size(size.width, h),
                cornerRadius = CornerRadius(h / 2)
            )
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(LcdColors.selTop, LcdColors.selBottom)),
                topLeft = Offset(0f, y), size = Size(max(h, size.width * volume), h),
                cornerRadius = CornerRadius(h / 2)
            )
        }
        Icon(Icons.Filled.VolumeUp, null, tint = LcdColors.subText, modifier = Modifier.size(20.dp))
    }
}
