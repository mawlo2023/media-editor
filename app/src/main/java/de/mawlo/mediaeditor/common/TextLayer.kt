package de.mawlo.mediaeditor.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

enum class TextStyle(val label: String) { SHADOW("Schatten"), OUTLINE("Kontur"), BOX("Box") }

/** Ein Text auf dem Bild/Video. Position (x, y) = Mittelpunkt relativ zur Breite/Höhe (0..1). */
data class TextItem(
    val id: Long = System.nanoTime(),
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Schriftgröße relativ zur Bildbreite. */
    val size: Float = 0.08f,
    val color: Int = Color.WHITE,
    val style: TextStyle = TextStyle.SHADOW,
    val bold: Boolean = true,
)

object TextRenderer {

    /** Zeichnet einen Text in ein Zielrechteck der Größe [w] x [h]. */
    fun draw(canvas: Canvas, item: TextItem, w: Float, h: Float) {
        val lines = item.text.split("\n")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = item.size * w
            typeface = Typeface.create(Typeface.SANS_SERIF, if (item.bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            color = item.color
        }
        val lineH = paint.fontSpacing
        val totalH = lineH * lines.size
        val cx = item.x * w
        var top = item.y * h - totalH / 2f

        if (item.style == TextStyle.BOX) {
            val maxW = lines.maxOf { paint.measureText(it) }
            val pad = paint.textSize * 0.35f
            val box = RectF(cx - maxW / 2 - pad, top - pad * 0.6f, cx + maxW / 2 + pad, top + totalH + pad * 0.6f)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (luminance(item.color) > 0.5f) Color.argb(200, 0, 0, 0) else Color.argb(230, 255, 255, 255)
            }
            canvas.drawRoundRect(box, pad, pad, bg)
        }

        for (line in lines) {
            val baseline = top - paint.ascent()
            when (item.style) {
                TextStyle.SHADOW -> paint.setShadowLayer(paint.textSize * 0.12f, 0f, paint.textSize * 0.04f, Color.argb(170, 0, 0, 0))
                TextStyle.OUTLINE -> {
                    val stroke = Paint(paint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = paint.textSize * 0.12f
                        strokeJoin = Paint.Join.ROUND
                        color = if (luminance(item.color) > 0.5f) Color.BLACK else Color.WHITE
                    }
                    canvas.drawText(line, cx, baseline, stroke)
                }
                TextStyle.BOX -> Unit
            }
            canvas.drawText(line, cx, baseline, paint)
            top += lineH
        }
    }

    /** Ungefähre Trefferfläche eines Textes (für das Antippen/Verschieben). */
    fun bounds(item: TextItem, w: Float, h: Float): RectF {
        val paint = Paint().apply { textSize = item.size * w; typeface = Typeface.DEFAULT_BOLD }
        val lines = item.text.split("\n")
        val tw = lines.maxOf { paint.measureText(it) }
        val th = paint.fontSpacing * lines.size
        val cx = item.x * w
        val cy = item.y * h
        return RectF(cx - tw / 2 - 20, cy - th / 2 - 20, cx + tw / 2 + 20, cy + th / 2 + 20)
    }

    private fun luminance(c: Int) =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** Vignette (dunkle Ränder) über die ganze Fläche. */
    fun drawVignette(canvas: Canvas, w: Float, h: Float, strength: Float) {
        if (strength <= 0f) return
        val r = kotlin.math.hypot(w / 2f, h / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h / 2f, r,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((strength * 230).toInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    /** Ein ganzes Overlay-Bild (Vignette + Texte) in Videogröße – für den Video-Export. */
    fun renderOverlay(width: Int, height: Int, texts: List<TextItem>, vignette: Float): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawVignette(c, width.toFloat(), height.toFloat(), vignette)
        texts.forEach { draw(c, it, width.toFloat(), height.toFloat()) }
        return bmp
    }
}
