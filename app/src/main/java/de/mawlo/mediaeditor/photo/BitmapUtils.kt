package de.mawlo.mediaeditor.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import de.mawlo.mediaeditor.common.Adjustments
import de.mawlo.mediaeditor.common.ColorFilters
import de.mawlo.mediaeditor.common.TextItem
import de.mawlo.mediaeditor.common.TextRenderer
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapUtils {

    /** Maximale Kantenlänge, mit der gearbeitet wird (≈ 12 Megapixel bei 4:3). */
    const val MAX_SIDE = 4000

    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
            }
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, true) else it }
    }

    fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees) }, true)

    fun flipHorizontal(src: Bitmap): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postScale(-1f, 1f) }, true)

    fun crop(src: Bitmap, r: Rect): Bitmap {
        val rr = Rect(r)
        rr.intersect(0, 0, src.width, src.height)
        return Bitmap.createBitmap(src, rr.left, rr.top, rr.width().coerceAtLeast(1), rr.height().coerceAtLeast(1))
    }

    /**
     * Schnelle, weiche Unschärfe: Verkleinern, mehrfacher Box-Blur, wieder vergrößern.
     * [strength] 0..1
     */
    fun blur(src: Bitmap, strength: Float): Bitmap {
        val smallSide = (220 - 170 * strength).roundToInt().coerceIn(40, 400)
        val small = scaleToMax(src, smallSide).copy(Bitmap.Config.ARGB_8888, true)
        val radius = (2 + strength * 4).roundToInt()
        repeat(3) { boxBlur(small, radius) }
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    private fun boxBlur(bmp: Bitmap, r: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val div = 2 * r + 1
        for (o in 0 until outer) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, inner - 1)
                return if (horizontal) o * w + c else c * w + o
            }
            for (i in -r..r) {
                val p = src[idx(i)]
                sa += p ushr 24; sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff
            }
            for (i in 0 until inner) {
                dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[idx(i - r)]
                val pIn = src[idx(i + r + 1)]
                sa += (pIn ushr 24) - (pOut ushr 24)
                sr += ((pIn shr 16) and 0xff) - ((pOut shr 16) and 0xff)
                sg += ((pIn shr 8) and 0xff) - ((pOut shr 8) and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }

    /** Endgültiges Bild: Basis + Farbfilter + Vignette + Texte. */
    fun render(base: Bitmap, adj: Adjustments, texts: List<TextItem>): Bitmap {
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorFilters.build(adj))
        }
        c.drawBitmap(base, 0f, 0f, paint)
        val w = base.width.toFloat()
        val h = base.height.toFloat()
        TextRenderer.drawVignette(c, w, h, adj.vignette)
        texts.forEach { TextRenderer.draw(c, it, w, h) }
        return out
    }

    fun hasTransparency(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        val step = max(1, bmp.width / 64)
        for (y in 0 until bmp.height step step) for (x in 0 until bmp.width step step) {
            if ((bmp.getPixel(x, y) ushr 24) < 250) return true
        }
        return false
    }

    /** Speichert in „Bilder/MediaStudio“. PNG bei Transparenz, sonst JPEG. */
    fun saveToGallery(context: Context, bmp: Bitmap): Uri {
        val png = hasTransparency(bmp)
        val name = "MediaStudio_${System.currentTimeMillis()}." + if (png) "png" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MediaStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Speichern nicht möglich")
        resolver.openOutputStream(uri)!!.use { os ->
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, os)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
