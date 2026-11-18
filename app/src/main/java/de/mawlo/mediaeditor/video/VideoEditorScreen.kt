package de.mawlo.mediaeditor.video

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import de.mawlo.mediaeditor.common.ColorFilters
import de.mawlo.mediaeditor.common.FilterPreset
import de.mawlo.mediaeditor.common.TextRenderer
import de.mawlo.mediaeditor.photo.AdjustControls
import de.mawlo.mediaeditor.photo.colorFilterOf
import de.mawlo.mediaeditor.ui.Accent
import de.mawlo.mediaeditor.ui.Bg
import de.mawlo.mediaeditor.ui.BusyOverlay
import de.mawlo.mediaeditor.ui.Chip
import de.mawlo.mediaeditor.ui.HSpace
import de.mawlo.mediaeditor.ui.LabeledSlider
import de.mawlo.mediaeditor.ui.Panel
import de.mawlo.mediaeditor.ui.PanelLight
import de.mawlo.mediaeditor.ui.TextDim
import de.mawlo.mediaeditor.ui.TextPanel
import de.mawlo.mediaeditor.ui.ToolButton

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(onBack: () -> Unit, vm: VideoEditorViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.addClips(uris) else if (vm.clips.isEmpty()) onBack()
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.chooseMusic(uri)
    }
    fun pickVideos() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    LaunchedEffect(Unit) { if (vm.clips.isEmpty()) pickVideos() }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { onDispose { vm.player.pause() } }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White) }
                Column {
                    Text("Video", color = Color.White, fontSize = 18.sp)
                    if (vm.clips.isNotEmpty()) Text("Länge ${formatTime(vm.totalDurationMs)}", color = TextDim, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = vm::export, enabled = vm.clips.isNotEmpty(), modifier = Modifier.padding(end = 8.dp)) { Text("Exportieren") }
            }

            // Vorschau
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                if (vm.clips.isNotEmpty()) {
                    Box(Modifier.aspectRatio(vm.outputAspect, matchHeightConstraintsFirst = vm.outputAspect < 1f)) {
                        AndroidView(
                            factory = { c ->
                                PlayerView(c).apply {
                                    player = vm.player
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShowNextButton(false)
                                    setShowPreviousButton(false)
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        OverlayCanvas(vm)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(Panel).heightIn(min = 150.dp)) {
                when (vm.tool) {
                    VideoTool.CUT -> CutPanel(vm, ::pickVideos)
                    VideoTool.FILTER -> FilterPanel(vm)
                    VideoTool.ADJUST -> AdjustControls(vm.adj, vm::updateAdj)
                    VideoTool.TEXT -> TextPanel(vm.texts, vm.selectedTextId, vm::addText, vm::updateText, vm::deleteText)
                    VideoTool.AUDIO -> AudioPanel(vm) { musicPicker.launch("audio/*") }
                    VideoTool.FORMAT -> FormatPanel(vm)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val icons = mapOf(
                    VideoTool.CUT to Icons.Default.ContentCut,
                    VideoTool.FILTER to Icons.Default.PhotoFilter,
                    VideoTool.ADJUST to Icons.Default.Tune,
                    VideoTool.TEXT to Icons.Default.TextFields,
                    VideoTool.AUDIO to Icons.Default.MusicNote,
                    VideoTool.FORMAT to Icons.Default.AspectRatio,
                )
                VideoTool.entries.forEach { t -> ToolButton(icons.getValue(t), t.label, vm.tool == t) { vm.tool = t } }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp))
        vm.busy?.let { msg ->
            BusyOverlay(msg, vm.progress)
            if (vm.progress != null) {
                OutlinedButton(onClick = vm::cancelExport, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) {
                    Text("Abbrechen", color = Color.White)
                }
            }
        }
    }
}

/** Texte und Vignette über dem Video. Im Text-Werkzeug lassen sich Texte verschieben. */
@Composable
private fun OverlayCanvas(vm: VideoEditorViewModel) {
    val textMode = vm.tool == VideoTool.TEXT
    val gestures = if (!textMode) Modifier else Modifier.pointerInput(vm.texts.size) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val hit = vm.texts.lastOrNull { TextRenderer.bounds(it, w, h).contains(down.position.x, down.position.y) }
            vm.selectedTextId = hit?.id
            if (hit != null) {
                var last = down.position
                do {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull() ?: break
                    if (c.pressed) {
                        val d = c.position - last
                        last = c.position
                        vm.updateText(hit.id) { it.copy(x = (it.x + d.x / w).coerceIn(0f, 1f), y = (it.y + d.y / h).coerceIn(0f, 1f)) }
                        c.consume()
                    }
                } while (ev.changes.any { it.pressed })
            }
        }
    }
    Canvas(Modifier.fillMaxSize().then(gestures)) {
        val nc = drawContext.canvas.nativeCanvas
        TextRenderer.drawVignette(nc, size.width, size.height, vm.adj.vignette)
        vm.texts.forEach { TextRenderer.draw(nc, it, size.width, size.height) }
        if (textMode) vm.texts.firstOrNull { it.id == vm.selectedTextId }?.let {
            val b = TextRenderer.bounds(it, size.width, size.height)
            drawRect(
                Color.White, Offset(b.left, b.top), Size(b.width(), b.height()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
            )
        }
    }
}

@Composable
private fun CutPanel(vm: VideoEditorViewModel, onAdd: () -> Unit) {
    val sel = vm.clips.firstOrNull { it.id == vm.selectedClipId }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            vm.clips.forEachIndexed { i, c ->
                val selected = c.id == vm.selectedClipId
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                        .border(if (selected) 3.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .background(PanelLight)
                        .clickable { vm.selectedClipId = c.id },
                ) {
                    c.thumb?.let { Image(remember(it) { it.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    Text(
                        "${i + 1}", color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(horizontal = 4.dp),
                    )
                }
            }
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(PanelLight).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Clip hinzufügen", tint = Color.White) }
        }
        if (sel != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kürzen: ${formatTime(sel.startMs)} – ${formatTime(sel.endMs)}", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.moveClip(sel.id, -1) }) { Icon(Icons.Default.ChevronLeft, "Nach vorne", tint = Color.White) }
                IconButton(onClick = { vm.moveClip(sel.id, 1) }) { Icon(Icons.Default.ChevronRight, "Nach hinten", tint = Color.White) }
                IconButton(onClick = { vm.removeClip(sel.id) }) { Icon(Icons.Default.Delete, "Entfernen", tint = Color(0xFFFF4D8D)) }
            }
            RangeSlider(
                value = sel.startMs.toFloat()..sel.endMs.toFloat(),
                onValueChange = { r ->
                    val s = r.start.toLong()
                    val e = r.endInclusive.toLong().coerceAtLeast(s + 300)
                    vm.trimClip(sel.id, s, e.coerceAtMost(sel.durationMs))
                },
                onValueChangeFinished = vm::trimFinished,
                valueRange = 0f..sel.durationMs.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = PanelLight),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun FilterPanel(vm: VideoEditorViewModel) {
    val thumb = vm.clips.firstOrNull { it.id == vm.selectedClipId }?.thumb ?: vm.clips.firstOrNull()?.thumb
    val img = remember(thumb) { thumb?.asImageBitmap() }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterPreset.entries.forEach { p ->
                val sel = vm.adj.filter == p
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { vm.updateAdj(vm.adj.copy(filter = p, filterStrength = 1f)) },
                ) {
                    if (img != null) {
                        Image(
                            img, p.label, contentScale = ContentScale.Crop,
                            colorFilter = remember(p) { colorFilterOf(ColorFilters.presetMatrix(p)) },
                            modifier = Modifier.size(68.dp).clip(RoundedCornerShape(10.dp))
                                .border(if (sel) 3.dp else 0.dp, if (sel) Accent else Color.Transparent, RoundedCornerShape(10.dp)),
                        )
                    }
                    Text(p.label, fontSize = 11.sp, color = if (sel) Accent else TextDim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (vm.adj.filter != FilterPreset.NONE) {
            LabeledSlider("Stärke", vm.adj.filterStrength, { vm.updateAdj(vm.adj.copy(filterStrength = it)) }, 0f..1f)
        }
    }
}

@Composable
private fun AudioPanel(vm: VideoEditorViewModel, onPick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPick) { Icon(Icons.Default.MusicNote, null); HSpace(6); Text(if (vm.music == null) "Musik wählen" else "Andere Musik") }
            HSpace(12)
            vm.musicName?.let { Text(it, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
            if (vm.music != null) IconButton(onClick = { vm.chooseMusic(null) }) { Icon(Icons.Default.Delete, "Musik entfernen", tint = Color(0xFFFF4D8D)) }
        }
        LabeledSlider("Originalton", vm.originalVolume, vm::changeOriginalVolume, 0f..1f) { if (it == 0f) "stumm" else "${(it * 100).toInt()} %" }
        if (vm.music != null) LabeledSlider("Musik", vm.musicVolume, vm::changeMusicVolume, 0f..1f) { "${(it * 100).toInt()} %" }
    }
}

@Composable
private fun FormatPanel(vm: VideoEditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("Format", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { VideoFormat.entries.forEach { f -> Chip(f.label, vm.format == f) { vm.changeFormat(f) } } }
        Text("Tempo", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { SpeedOptions.forEach { s -> Chip("${s}x".replace(".0x", "x"), vm.speed == s) { vm.changeSpeed(s) } } }
    }
}
