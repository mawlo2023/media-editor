package de.mawlo.mediaeditor.photo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.mawlo.mediaeditor.common.Adjustments
import de.mawlo.mediaeditor.common.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

enum class PhotoTool(val label: String) {
    FILTER("Filter"), ADJUST("Anpassen"), CROP("Zuschnitt"), BACKGROUND("Hintergrund"),
    ERASER("Radierer"), TEXT("Text")
}

/** Seitenverhältnis für den Zuschnitt (null = wie Original). */
enum class CropAspect(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null), SQUARE("1:1", 1f), PORTRAIT("4:5", 4f / 5f),
    STORY("9:16", 9f / 16f), WIDE("16:9", 16f / 9f), CLASSIC("4:3", 4f / 3f)
}

/** Ein Pinselstrich des Radierers in relativen Koordinaten (0..1). */
data class Stroke(val points: List<Pair<Float, Float>>, val width: Float)

class PhotoEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    // ---------- Grundzustand ----------
    var base by mutableStateOf<Bitmap?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    private val undoStack = ArrayDeque<Bitmap>()
    var canUndo by mutableStateOf(false); private set

    var tool by mutableStateOf(PhotoTool.FILTER)
    var adj by mutableStateOf(Adjustments())
    var texts by mutableStateOf<List<TextItem>>(emptyList())
    var selectedTextId by mutableStateOf<Long?>(null)

    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)

    // ---------- Zuschnitt ----------
    var cropAspect by mutableStateOf(CropAspect.ORIGINAL)
    var cropScale by mutableStateOf(1f)
    var cropCx by mutableStateOf(0.5f)
    var cropCy by mutableStateOf(0.5f)

    // ---------- Hintergrund ----------
    private var maskFull: Bitmap? = null
    private var maskPreview: Bitmap? = null
    var bgMode by mutableStateOf(BackgroundMode.NONE); private set
    var bgBlur by mutableStateOf(0.6f); private set
    var bgColor by mutableStateOf(Color.WHITE); private set
    private var bgImage: Bitmap? = null
    var bgPreview by mutableStateOf<Bitmap?>(null); private set
    private var bgJob: Job? = null

    // ---------- Radierer ----------
    var strokes by mutableStateOf<List<Stroke>>(emptyList())
    var brushSize by mutableStateOf(0.06f)

    fun load(uri: Uri) {
        runBusy("Bild wird geladen …") {
            val bmp = withContext(Dispatchers.IO) { BitmapUtils.load(ctx, uri) }
            undoStack.clear()
            setBase(bmp, pushUndo = false)
            adj = Adjustments()
            texts = emptyList()
        }
    }

    private fun setBase(bmp: Bitmap, pushUndo: Boolean = true) {
        base?.let { if (pushUndo) { undoStack.addLast(it); if (undoStack.size > 4) undoStack.removeFirst() } }
        canUndo = undoStack.isNotEmpty()
        base = bmp
        preview = BitmapUtils.scaleToMax(bmp, 1440)
        maskFull = null
        maskPreview = null
        bgMode = BackgroundMode.NONE
        bgPreview = null
        strokes = emptyList()
        cropScale = 1f; cropCx = 0.5f; cropCy = 0.5f
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        setBase(prev, pushUndo = false)
    }

    private fun runBusy(msg: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = msg
        viewModelScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                message = "Fehler: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    fun selectTool(t: PhotoTool) {
        tool = t
        if (t == PhotoTool.BACKGROUND) ensureMask()
    }

    // ---------- Zuschnitt / Drehen ----------
    fun rotate(deg: Float) = runBusy("Drehen …") {
        val b = base ?: return@runBusy
        setBase(withContext(Dispatchers.Default) { BitmapUtils.rotate(b, deg) })
    }

    fun flip() = runBusy("Spiegeln …") {
        val b = base ?: return@runBusy
        setBase(withContext(Dispatchers.Default) { BitmapUtils.flipHorizontal(b) })
    }

    /** Zuschnitt-Rechteck in relativen Koordinaten (0..1) für ein Bild der Größe w x h. */
    fun cropRectRel(w: Int, h: Int): android.graphics.RectF {
        val ratio = cropAspect.ratio ?: (w.toFloat() / h)
        val imgRatio = w.toFloat() / h
        var cw: Float
        var ch: Float
        if (ratio > imgRatio) { cw = 1f; ch = imgRatio / ratio } else { ch = 1f; cw = ratio / imgRatio }
        cw *= cropScale; ch *= cropScale
        val cx = cropCx.coerceIn(cw / 2, 1 - cw / 2)
        val cy = cropCy.coerceIn(ch / 2, 1 - ch / 2)
        return android.graphics.RectF(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
    }

    val cropActive get() = cropAspect != CropAspect.ORIGINAL || cropScale < 0.999f

    fun applyCrop() = runBusy("Zuschneiden …") {
        val b = base ?: return@runBusy
        val r = cropRectRel(b.width, b.height)
        val rect = Rect(
            (r.left * b.width).roundToInt(), (r.top * b.height).roundToInt(),
            (r.right * b.width).roundToInt(), (r.bottom * b.height).roundToInt(),
        )
        setBase(withContext(Dispatchers.Default) { BitmapUtils.crop(b, rect) })
        cropAspect = CropAspect.ORIGINAL
    }

    // ---------- Hintergrund ----------
    private fun ensureMask() {
        if (maskFull != null) return
        val b = base ?: return
        runBusy("Person wird erkannt …") {
            val m = withContext(Dispatchers.Default) { BackgroundTools.segment(b) }
            if (BackgroundTools.maskCoverage(m) < 0.01f) {
                message = "Keine Person erkannt. Die Freistellung funktioniert am besten mit Menschen im Bild."
            }
            maskFull = m
            val p = preview!!
            maskPreview = Bitmap.createScaledBitmap(m, p.width, p.height, true)
        }
    }

    fun setBackground(mode: BackgroundMode = bgMode, blur: Float = bgBlur, color: Int = bgColor) {
        bgMode = mode; bgBlur = blur; bgColor = color
        refreshBgPreview()
    }

    fun setBackgroundImage(uri: Uri) = viewModelScope.launch {
        bgImage = withContext(Dispatchers.IO) { BitmapUtils.load(ctx, uri, 3000) }
        setBackground(BackgroundMode.IMAGE)
    }

    private fun refreshBgPreview() {
        bgJob?.cancel()
        bgJob = viewModelScope.launch {
            delay(60)
            val p = preview ?: return@launch
            val m = maskPreview ?: return@launch
            bgPreview = if (bgMode == BackgroundMode.NONE || (bgMode == BackgroundMode.IMAGE && bgImage == null)) null
            else withContext(Dispatchers.Default) { BackgroundTools.composite(p, m, bgMode, bgBlur, bgColor, bgImage) }
        }
    }

    fun applyBackground() = runBusy("Hintergrund wird berechnet …") {
        val b = base ?: return@runBusy
        val m = maskFull ?: return@runBusy
        if (bgMode == BackgroundMode.NONE) return@runBusy
        val out = withContext(Dispatchers.Default) { BackgroundTools.composite(b, m, bgMode, bgBlur, bgColor, bgImage) }
        setBase(out)
    }

    // ---------- Radierer ----------
    fun addStroke(s: Stroke) { strokes = strokes + s }
    fun undoStroke() { strokes = strokes.dropLast(1) }

    fun applyEraser() {
        val b = base ?: return
        if (strokes.isEmpty()) { message = "Male zuerst über das Objekt, das verschwinden soll."; return }
        val firstRun = !java.io.File(ctx.filesDir, "lama_fp32.onnx").exists()
        runBusy(if (firstRun) "KI wird beim ersten Mal vorbereitet …" else "KI entfernt das Objekt …") {
            val out = withContext(Dispatchers.Default) {
                val mask = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(mask)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                for (s in strokes) {
                    paint.strokeWidth = s.width * b.width
                    val path = Path()
                    s.points.forEachIndexed { i, (x, y) ->
                        if (i == 0) path.moveTo(x * b.width, y * b.height) else path.lineTo(x * b.width, y * b.height)
                    }
                    if (s.points.size == 1) path.lineTo(s.points[0].first * b.width + 0.5f, s.points[0].second * b.height)
                    c.drawPath(path, paint)
                }
                Inpainter.get(ctx).inpaint(b, mask)
            }
            setBase(out)
        }
    }

    // ---------- Text ----------
    fun addText(text: String) {
        val t = TextItem(text = text)
        texts = texts + t
        selectedTextId = t.id
    }

    fun updateText(id: Long, f: (TextItem) -> TextItem) {
        texts = texts.map { if (it.id == id) f(it) else it }
    }

    fun deleteText(id: Long) {
        texts = texts.filterNot { it.id == id }
        if (selectedTextId == id) selectedTextId = null
    }

    // ---------- Speichern ----------
    fun save() = runBusy("Wird gespeichert …") {
        val b0 = base ?: return@runBusy
        // Noch nicht übernommene Änderungen automatisch mitnehmen
        val mask = maskFull
        val pendingBg = tool == PhotoTool.BACKGROUND && bgMode != BackgroundMode.NONE && mask != null
        val pendingCrop = tool == PhotoTool.CROP && cropActive
        val b = withContext(Dispatchers.Default) {
            var x = b0
            if (pendingBg) x = BackgroundTools.composite(x, mask!!, bgMode, bgBlur, bgColor, bgImage)
            if (pendingCrop) {
                val r = cropRectRel(x.width, x.height)
                x = BitmapUtils.crop(x, Rect((r.left * x.width).roundToInt(), (r.top * x.height).roundToInt(),
                    (r.right * x.width).roundToInt(), (r.bottom * x.height).roundToInt()))
            }
            x
        }
        withContext(Dispatchers.IO) {
            val out = BitmapUtils.render(b, adj, texts)
            BitmapUtils.saveToGallery(ctx, out)
        }
        message = "Gespeichert in Galerie › Bilder › MediaStudio" +
            if (b.hasAlpha() && BitmapUtils.hasTransparency(b)) " (PNG mit transparentem Hintergrund)" else ""
    }

    val previewScale: Float get() {
        val b = base ?: return 1f
        val p = preview ?: return 1f
        return min(1f, p.width.toFloat() / b.width)
    }
}
