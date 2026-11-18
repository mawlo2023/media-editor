package de.mawlo.mediaeditor.photo

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mawlo.mediaeditor.common.ColorFilters
import de.mawlo.mediaeditor.common.FilterPreset
import de.mawlo.mediaeditor.common.TextRenderer
import de.mawlo.mediaeditor.ui.Accent
import de.mawlo.mediaeditor.ui.Bg
import de.mawlo.mediaeditor.ui.BusyOverlay
import de.mawlo.mediaeditor.ui.Chip
import de.mawlo.mediaeditor.ui.ColorDot
import de.mawlo.mediaeditor.ui.HSpace
import de.mawlo.mediaeditor.ui.LabeledSlider
import de.mawlo.mediaeditor.ui.Panel
import de.mawlo.mediaeditor.ui.SwatchColors
import de.mawlo.mediaeditor.ui.TextDim
import de.mawlo.mediaeditor.ui.TextPanel
import de.mawlo.mediaeditor.ui.ToolButton
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

/** Rechteck, in dem ein Bild mit „Fit“ in einen Bereich gezeichnet wird. */
fun fitRect(cw: Float, ch: Float, iw: Int, ih: Int, pad: Float = 0f): Rect {
    val aw = cw - 2 * pad
    val ah = ch - 2 * pad
    val s = minOf(aw / iw, ah / ih)
    val w = iw * s
    val h = ih * s
    return Rect(Offset((cw - w) / 2, (ch - h) / 2), Size(w, h))
}

fun colorFilterOf(m: android.graphics.ColorMatrix) = ColorFilter.colorMatrix(ComposeColorMatrix(m.array))

@Composable
fun PhotoEditorScreen(onBack: () -> Unit, vm: PhotoEditorViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri) else if (vm.base == null) onBack()
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setBackgroundImage(uri)
    }
    LaunchedEffect(Unit) {
        if (vm.base == null) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    BackHandler(onBack = onBack)

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Kopfzeile
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White) }
                Text("Foto", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.Image, "Anderes Bild", tint = Color.White)
                }
                IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Rückgängig", tint = if (vm.canUndo) Color.White else TextDim)
                }
                Button(onClick = vm::save, enabled = vm.base != null, modifier = Modifier.padding(end = 8.dp)) { Text("Speichern") }
            }

            ImageStage(vm, Modifier.weight(1f).fillMaxWidth())

            // Werkzeug-Bereich
            Column(Modifier.fillMaxWidth().background(Panel).heightIn(min = 150.dp)) {
                when (vm.tool) {
                    PhotoTool.FILTER -> FilterPanel(vm)
                    PhotoTool.ADJUST -> AdjustPanel(vm)
                    PhotoTool.CROP -> CropPanel(vm)
                    PhotoTool.BACKGROUND -> BackgroundPanel(vm) {
                        bgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    PhotoTool.ERASER -> EraserPanel(vm)
                    PhotoTool.TEXT -> TextPanel(vm.texts, vm.selectedTextId, vm::addText, vm::updateText, vm::deleteText)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val icons = mapOf(
                    PhotoTool.FILTER to Icons.Default.PhotoFilter,
                    PhotoTool.ADJUST to Icons.Default.Tune,
                    PhotoTool.CROP to Icons.Default.Crop,
                    PhotoTool.BACKGROUND to Icons.Default.Layers,
                    PhotoTool.ERASER to Icons.Default.AutoFixHigh,
                    PhotoTool.TEXT to Icons.Default.TextFields,
                )
                PhotoTool.entries.forEach { t -> ToolButton(icons.getValue(t), t.label, vm.tool == t) { vm.selectTool(t) } }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp))
        vm.busy?.let { BusyOverlay(it) }
    }
}

@Composable
private fun ImageStage(vm: PhotoEditorViewModel, modifier: Modifier) {
    val bmp = if (vm.tool == PhotoTool.BACKGROUND && vm.bgPreview != null) vm.bgPreview else vm.preview
    val img: ImageBitmap = remember(bmp) { bmp?.asImageBitmap() } ?: run {
        Box(modifier); return
    }
    val colorFilter = remember(vm.adj) { if (vm.adj.isIdentity) null else colorFilterOf(ColorFilters.build(vm.adj)) }
    val currentStroke = remember { mutableStateListOf<Pair<Float, Float>>() }

    BoxWithConstraints(modifier) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val rect = fitRect(cw, ch, img.width, img.height, pad = 24f)

        fun rel(o: Offset) = Pair(((o.x - rect.left) / rect.width), ((o.y - rect.top) / rect.height))

        val gestures = Modifier.pointerInput(vm.tool, rect, vm.cropAspect, vm.cropScale) {
            awaitEachGesture {
                val down = awaitFirstDown()
                when (vm.tool) {
                    PhotoTool.ERASER -> {
                        currentStroke.clear()
                        currentStroke.add(rel(down.position))
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { if (it.pressed) { currentStroke.add(rel(it.position)); it.consume() } }
                        } while (ev.changes.any { it.pressed })
                        vm.addStroke(Stroke(currentStroke.toList(), vm.brushSize))
                        currentStroke.clear()
                    }
                    PhotoTool.TEXT -> {
                        val hit = vm.texts.lastOrNull {
                            val b = TextRenderer.bounds(it, rect.width, rect.height)
                            b.contains(down.position.x - rect.left, down.position.y - rect.top)
                        }
                        vm.selectedTextId = hit?.id
                        if (hit != null) {
                            var last = down.position
                            do {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (c.pressed) {
                                    val d = c.position - last
                                    last = c.position
                                    vm.updateText(hit.id) {
                                        it.copy(x = (it.x + d.x / rect.width).coerceIn(0f, 1f), y = (it.y + d.y / rect.height).coerceIn(0f, 1f))
                                    }
                                    c.consume()
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }
                    PhotoTool.CROP -> {
                        var last = down.position
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (c.pressed) {
                                val d = c.position - last
                                last = c.position
                                vm.cropCx = (vm.cropCx + d.x / rect.width).coerceIn(0f, 1f)
                                vm.cropCy = (vm.cropCy + d.y / rect.height).coerceIn(0f, 1f)
                                c.consume()
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                    else -> Unit
                }
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            if (bmp!!.hasAlpha()) drawChecker(rect)
            drawImage(
                img,
                srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                colorFilter = colorFilter,
            )
            val nc = drawContext.canvas.nativeCanvas
            nc.save()
            nc.translate(rect.left, rect.top)
            nc.clipRect(0f, 0f, rect.width, rect.height)
            TextRenderer.drawVignette(nc, rect.width, rect.height, vm.adj.vignette)
            vm.texts.forEach { TextRenderer.draw(nc, it, rect.width, rect.height) }
            nc.restore()

            when (vm.tool) {
                PhotoTool.TEXT -> vm.texts.firstOrNull { it.id == vm.selectedTextId }?.let {
                    val b = TextRenderer.bounds(it, rect.width, rect.height)
                    drawRect(
                        Color.White, Offset(rect.left + b.left, rect.top + b.top), Size(b.width(), b.height()),
                        style = DrawStroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                    )
                }
                PhotoTool.ERASER -> clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                    (vm.strokes.map { it.points to it.width } + listOf(currentStroke.toList() to vm.brushSize)).forEach { (pts, w) ->
                        if (pts.isEmpty()) return@forEach
                        val path = Path()
                        pts.forEachIndexed { i, (x, y) ->
                            val px = rect.left + x * rect.width
                            val py = rect.top + y * rect.height
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        if (pts.size == 1) path.lineTo(rect.left + pts[0].first * rect.width + 0.5f, rect.top + pts[0].second * rect.height)
                        drawPath(path, Color(0x88FF3B6B), style = DrawStroke(w * rect.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                PhotoTool.CROP -> {
                    val r = vm.cropRectRel(img.width, img.height)
                    val cr = Rect(rect.left + r.left * rect.width, rect.top + r.top * rect.height, rect.left + r.right * rect.width, rect.top + r.bottom * rect.height)
                    val dim = Color(0xAA000000)
                    drawRect(dim, Offset(rect.left, rect.top), Size(rect.width, cr.top - rect.top))
                    drawRect(dim, Offset(rect.left, cr.bottom), Size(rect.width, rect.bottom - cr.bottom))
                    drawRect(dim, Offset(rect.left, cr.top), Size(cr.left - rect.left, cr.height))
                    drawRect(dim, Offset(cr.right, cr.top), Size(rect.right - cr.right, cr.height))
                    drawRect(Color.White, cr.topLeft, cr.size, style = DrawStroke(2.dp.toPx()))
                    for (i in 1..2) {
                        val x = cr.left + cr.width * i / 3
                        val y = cr.top + cr.height * i / 3
                        drawLine(Color(0x88FFFFFF), Offset(x, cr.top), Offset(x, cr.bottom), 1.dp.toPx())
                        drawLine(Color(0x88FFFFFF), Offset(cr.left, y), Offset(cr.right, y), 1.dp.toPx())
                    }
                }
                else -> Unit
            }
        }
    }
}

private fun DrawScope.drawChecker(rect: Rect) {
    val s = 16.dp.toPx()
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        drawRect(Color(0xFF3A3A3A), rect.topLeft, rect.size)
        var y = rect.top
        var row = 0
        while (y < rect.bottom) {
            var x = rect.left + if (row % 2 == 0) 0f else s
            while (x < rect.right) {
                drawRect(Color(0xFF555555), Offset(x, y), Size(s, s))
                x += 2 * s
            }
            y += s; row++
        }
    }
}

@Composable
private fun FilterPanel(vm: PhotoEditorViewModel) {
    val thumb = remember(vm.preview) { vm.preview?.let { BitmapUtilsThumb.make(it) }?.asImageBitmap() }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterPreset.entries.forEach { p ->
                val sel = vm.adj.filter == p
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { vm.adj = vm.adj.copy(filter = p, filterStrength = 1f) },
                ) {
                    if (thumb != null) {
                        val cf = remember(p) { colorFilterOf(ColorFilters.presetMatrix(p)) }
                        Image(
                            thumb, p.label, contentScale = ContentScale.Crop, colorFilter = cf,
                            modifier = Modifier.size(68.dp).clip(RoundedCornerShape(10.dp))
                                .border(if (sel) 3.dp else 0.dp, if (sel) Accent else Color.Transparent, RoundedCornerShape(10.dp)),
                        )
                    }
                    Text(p.label, fontSize = 11.sp, color = if (sel) Accent else TextDim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (vm.adj.filter != FilterPreset.NONE) {
            LabeledSlider("Stärke", vm.adj.filterStrength, { vm.adj = vm.adj.copy(filterStrength = it) }, 0f..1f)
        }
    }
}

private object BitmapUtilsThumb {
    fun make(src: android.graphics.Bitmap): android.graphics.Bitmap = BitmapUtils.scaleToMax(src, 200)
}

private enum class AdjParam(val label: String) {
    BRIGHTNESS("Helligkeit"), CONTRAST("Kontrast"), SATURATION("Sättigung"), WARMTH("Wärme"),
    FADE("Verblassen"), VIGNETTE("Vignette")
}

@Composable
fun AdjustControls(adj: de.mawlo.mediaeditor.common.Adjustments, onChange: (de.mawlo.mediaeditor.common.Adjustments) -> Unit) {
    var param by remember { mutableStateOf(AdjParam.BRIGHTNESS) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdjParam.entries.forEach { p -> Chip(p.label, param == p) { param = p } }
            Chip("Zurücksetzen", false) { onChange(adj.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f, fade = 0f, vignette = 0f)) }
        }
        when (param) {
            AdjParam.BRIGHTNESS -> LabeledSlider(param.label, adj.brightness, { onChange(adj.copy(brightness = it)) })
            AdjParam.CONTRAST -> LabeledSlider(param.label, adj.contrast, { onChange(adj.copy(contrast = it)) })
            AdjParam.SATURATION -> LabeledSlider(param.label, adj.saturation, { onChange(adj.copy(saturation = it)) })
            AdjParam.WARMTH -> LabeledSlider(param.label, adj.warmth, { onChange(adj.copy(warmth = it)) })
            AdjParam.FADE -> LabeledSlider(param.label, adj.fade, { onChange(adj.copy(fade = it)) }, 0f..1f)
            AdjParam.VIGNETTE -> LabeledSlider(param.label, adj.vignette, { onChange(adj.copy(vignette = it)) }, 0f..1f)
        }
    }
}

@Composable
private fun AdjustPanel(vm: PhotoEditorViewModel) = AdjustControls(vm.adj) { vm.adj = it }

@Composable
private fun CropPanel(vm: PhotoEditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CropAspect.entries.forEach { a -> Chip(a.label, vm.cropAspect == a) { vm.cropAspect = a } }
        }
        LabeledSlider("Ausschnitt", vm.cropScale, { vm.cropScale = it }, 0.3f..1f) { "${(it * 100).toInt()} %" }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.rotate(-90f) }) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Links drehen", tint = Color.White) }
            IconButton(onClick = { vm.rotate(90f) }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rechts drehen", tint = Color.White) }
            IconButton(onClick = vm::flip) { Icon(Icons.Default.Flip, "Spiegeln", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::applyCrop, enabled = vm.cropActive) { Icon(Icons.Default.Check, null); HSpace(4); Text("Zuschneiden") }
        }
        Text("Rahmen mit dem Finger verschieben.", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    }
}

@Composable
private fun BackgroundPanel(vm: PhotoEditorViewModel, onPickImage: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackgroundMode.entries.forEach { m ->
                Chip(m.label, vm.bgMode == m) {
                    if (m == BackgroundMode.IMAGE) onPickImage() else vm.setBackground(mode = m)
                }
            }
        }
        when (vm.bgMode) {
            BackgroundMode.BLUR -> LabeledSlider("Stärke", vm.bgBlur, { vm.setBackground(blur = it) }, 0f..1f)
            BackgroundMode.COLOR -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { SwatchColors.forEach { c -> ColorDot(Color(c), vm.bgColor == c) { vm.setBackground(color = c) } } }
            BackgroundMode.IMAGE -> OutlinedButton(onClick = onPickImage, modifier = Modifier.padding(16.dp)) { Text("Anderes Hintergrundbild") }
            BackgroundMode.REMOVE -> Text("Wird als PNG mit transparentem Hintergrund gespeichert.", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            BackgroundMode.NONE -> Text("Personen werden automatisch erkannt und freigestellt.", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        if (vm.bgMode != BackgroundMode.NONE) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = vm::applyBackground) { Icon(Icons.Default.Check, null); HSpace(4); Text("Übernehmen") }
            }
        }
    }
}

@Composable
private fun EraserPanel(vm: PhotoEditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            "Male über das Objekt, das verschwinden soll – lieber etwas großzügiger.",
            color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        LabeledSlider("Pinselgröße", vm.brushSize, { vm.brushSize = it }, 0.01f..0.15f) { "${(it * 100).toInt()}" }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::undoStroke, enabled = vm.strokes.isNotEmpty()) { Text("Strich zurück") }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::applyEraser, enabled = vm.strokes.isNotEmpty()) {
                Icon(Icons.Default.AutoFixHigh, null); HSpace(4); Text("Entfernen")
            }
        }
    }
}

