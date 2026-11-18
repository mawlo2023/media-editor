package de.mawlo.mediaeditor.photo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Objekt-Radierer mit dem KI-Modell „LaMa“ (läuft vollständig auf dem Handy).
 * Eingabe: Bild 0..1 und Maske 0/1 (je 512x512). Ausgabe: Bild 0..255.
 */
class Inpainter private constructor(private val session: OrtSession) {

    companion object {
        private const val MODEL = "lama_fp32.onnx"
        private const val SIZE = 512

        @Volatile private var instance: Inpainter? = null

        /** Lädt das Modell (beim ersten Mal wird es aus der App entpackt, ca. 200 MB). */
        fun get(context: Context): Inpainter {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val file = File(context.filesDir, MODEL)
                val assetLen = context.assets.openFd(MODEL).use { it.length }
                if (!file.exists() || file.length() != assetLen) {
                    val tmp = File(context.filesDir, "$MODEL.tmp")
                    context.assets.open(MODEL).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                    tmp.renameTo(file)
                }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                return Inpainter(env.createSession(file.absolutePath, opts)).also { instance = it }
            }
        }
    }

    /**
     * Entfernt alles, was in [mask] (Alpha > 0) markiert ist, und gibt ein neues Bild zurück.
     * Es wird nur ein Ausschnitt rund um die Markierung neu berechnet – der Rest bleibt in voller Qualität.
     */
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        val bbox = maskBounds(mask) ?: return src
        // Ausschnitt mit Rand um die Markierung, damit die KI genug Umgebung sieht
        val side = max(bbox.width(), bbox.height())
        val pad = max((side * 0.6f).roundToInt(), 48)
        var size = max(side + 2 * pad, min(SIZE, min(src.width, src.height)))
        size = min(size, max(src.width, src.height))
        val cx = bbox.centerX()
        val cy = bbox.centerY()
        val cw = min(size, src.width)
        val ch = min(size, src.height)
        val left = (cx - cw / 2).coerceIn(0, src.width - cw)
        val top = (cy - ch / 2).coerceIn(0, src.height - ch)
        val region = Rect(left, top, left + cw, top + ch)

        val srcCrop = Bitmap.createBitmap(src, region.left, region.top, cw, ch)
        val maskCrop = Bitmap.createBitmap(mask, region.left, region.top, cw, ch)
        val src512 = Bitmap.createScaledBitmap(srcCrop, SIZE, SIZE, true)
        val mask512 = dilate(Bitmap.createScaledBitmap(maskCrop, SIZE, SIZE, true), 9)

        val n = SIZE * SIZE
        val img = FloatArray(3 * n)
        val px = IntArray(n)
        src512.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (i in 0 until n) {
            val p = px[i]
            img[i] = ((p shr 16) and 0xff) / 255f
            img[n + i] = ((p shr 8) and 0xff) / 255f
            img[2 * n + i] = (p and 0xff) / 255f
        }
        val m = FloatArray(n) { if (mask512[it]) 1f else 0f }

        val env = OrtEnvironment.getEnvironment()
        val shapeImg = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        val shapeMask = longArrayOf(1, 1, SIZE.toLong(), SIZE.toLong())
        val outPx = IntArray(n)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(img), shapeImg).use { tImg ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(m), shapeMask).use { tMask ->
                session.run(mapOf("image" to tImg, "mask" to tMask)).use { res ->
                    val out = (res[0] as OnnxTensor).floatBuffer
                    for (i in 0 until n) {
                        val r = out.get(i).roundToInt().coerceIn(0, 255)
                        val g = out.get(n + i).roundToInt().coerceIn(0, 255)
                        val b = out.get(2 * n + i).roundToInt().coerceIn(0, 255)
                        outPx[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
        val out512 = Bitmap.createBitmap(outPx, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val outCrop = Bitmap.createScaledBitmap(out512, cw, ch, true)

        // Weiche Maske für den Übergang (Ergebnis nur im markierten Bereich einsetzen)
        val blendPx = IntArray(n) { if (mask512[it]) -0x1 else 0x00FFFFFF }
        val blend512 = Bitmap.createBitmap(blendPx, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val blend = BitmapUtils.blur(blend512, 0.05f).let { Bitmap.createScaledBitmap(it, cw, ch, true) }
        val patch = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        Canvas(patch).apply {
            drawBitmap(outCrop, 0f, 0f, null)
            drawBitmap(blend, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }

        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(patch, region.left.toFloat(), region.top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    private fun maskBounds(mask: Bitmap): Rect? {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 20) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** Maske vergrößern – sonst bleibt ein „Geist“ des Objekts zurück. */
    private fun dilate(mask: Bitmap, radius: Int): BooleanArray {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val src = BooleanArray(w * h) { (px[it] ushr 24) > 20 }
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            var prev = -10_000
            for (x in 0 until w) {
                if (src[y * w + x]) prev = x
                tmp[y * w + x] = x - prev <= radius
            }
            prev = 10_000
            for (x in w - 1 downTo 0) {
                if (src[y * w + x]) prev = x
                if (prev - x <= radius) tmp[y * w + x] = true
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var prev = -10_000
            for (y in 0 until h) {
                if (tmp[y * w + x]) prev = y
                out[y * w + x] = y - prev <= radius
            }
            prev = 10_000
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) prev = y
                if (prev - y <= radius) out[y * w + x] = true
            }
        }
        return out
    }
}
