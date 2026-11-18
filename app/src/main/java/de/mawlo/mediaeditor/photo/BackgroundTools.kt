package de.mawlo.mediaeditor.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max

enum class BackgroundMode(val label: String) {
    NONE("Original"), REMOVE("Entfernen"), BLUR("Unschärfe"), COLOR("Farbe"), IMAGE("Bild")
}

/**
 * Personen freistellen – komplett offline mit dem in der App enthaltenen ML-Kit-Modell.
 */
object BackgroundTools {

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    /**
     * Liefert eine Maske in Größe von [src]: Alpha = 255 bei der Person, 0 im Hintergrund.
     * Muss im Hintergrund-Thread laufen.
     */
    fun segment(src: Bitmap): Bitmap {
        val small = BitmapUtils.scaleToMax(src, 1024)
        val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
        val w = mask.width
        val h = mask.height
        val buf = mask.buffer
        buf.rewind()
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            // Kanten etwas schärfen (Smoothstep zwischen 0.3 und 0.7)
            val v = buf.float
            val t = ((v - 0.3f) / 0.4f).coerceIn(0f, 1f)
            val s = t * t * (3 - 2 * t)
            px[i] = ((s * 255).toInt() shl 24) or 0xFFFFFF
        }
        val m = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(m, src.width, src.height, true)
    }

    /** Prüft, ob überhaupt eine Person gefunden wurde. */
    fun maskCoverage(mask: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(mask, 64, 64, true)
        var sum = 0f
        for (y in 0 until 64) for (x in 0 until 64) sum += (small.getPixel(x, y) ushr 24) / 255f
        return sum / (64 * 64)
    }

    fun composite(
        src: Bitmap,
        mask: Bitmap,
        mode: BackgroundMode,
        blurStrength: Float = 0.6f,
        color: Int = 0xFFFFFFFF.toInt(),
        bgImage: Bitmap? = null,
    ): Bitmap {
        if (mode == BackgroundMode.NONE) return src
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        when (mode) {
            BackgroundMode.BLUR -> c.drawBitmap(BitmapUtils.blur(src, blurStrength), 0f, 0f, p)
            BackgroundMode.COLOR -> c.drawColor(color)
            BackgroundMode.IMAGE -> bgImage?.let { drawCenterCrop(c, it, w, h, p) }
            else -> Unit
        }

        // Vordergrund (Person) über Maske ausschneiden
        val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fc = Canvas(fg)
        fc.drawBitmap(src, 0f, 0f, null)
        fc.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.drawBitmap(fg, 0f, 0f, p)
        fg.recycle()
        return out
    }

    private fun drawCenterCrop(c: Canvas, img: Bitmap, w: Int, h: Int, p: Paint) {
        val s = max(w.toFloat() / img.width, h.toFloat() / img.height)
        val dw = img.width * s
        val dh = img.height * s
        c.save()
        c.translate((w - dw) / 2f, (h - dh) / 2f)
        c.scale(s, s)
        c.drawBitmap(img, 0f, 0f, p)
        c.restore()
    }
}
