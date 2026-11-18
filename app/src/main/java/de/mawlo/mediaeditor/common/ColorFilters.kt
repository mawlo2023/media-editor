package de.mawlo.mediaeditor.common

import android.graphics.ColorMatrix

/** Fertige Looks, die für Fotos und Videos identisch aussehen. */
enum class FilterPreset(val label: String) {
    NONE("Original"),
    VIVID("Lebendig"),
    CINEMATIC("Kino"),
    TEAL_ORANGE("Teal & Orange"),
    WARM("Sommer"),
    COOL("Kühl"),
    VINTAGE("Vintage"),
    FADE("Matt"),
    BW("Schwarz-Weiß"),
    NOIR("Noir"),
}

/** Regler-Werte. Alle Werte -1..1 bzw. 0..1, 0 = unverändert. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val filter: FilterPreset = FilterPreset.NONE,
    val filterStrength: Float = 1f,
) {
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f &&
            fade == 0f && vignette == 0f && filter == FilterPreset.NONE
}

object ColorFilters {

    private fun matrix(vararg v: Float) = ColorMatrix(v)

    private fun scaleOffset(r: Float, g: Float, b: Float, ro: Float = 0f, go: Float = 0f, bo: Float = 0f) =
        matrix(
            r, 0f, 0f, 0f, ro,
            0f, g, 0f, 0f, go,
            0f, 0f, b, 0f, bo,
            0f, 0f, 0f, 1f, 0f,
        )

    private fun contrast(s: Float): ColorMatrix {
        val o = 128f * (1f - s)
        return scaleOffset(s, s, s, o, o, o)
    }

    private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

    private fun chain(vararg m: ColorMatrix): ColorMatrix {
        val out = ColorMatrix()
        m.forEach { out.postConcat(it) }
        return out
    }

    fun presetMatrix(p: FilterPreset): ColorMatrix = when (p) {
        FilterPreset.NONE -> ColorMatrix()
        FilterPreset.VIVID -> chain(saturation(1.4f), contrast(1.12f))
        FilterPreset.CINEMATIC -> chain(
            saturation(0.82f), contrast(1.18f),
            scaleOffset(0.96f, 1.0f, 1.06f, -4f, 0f, 6f),
        )
        FilterPreset.TEAL_ORANGE -> chain(
            matrix(
                1.12f, 0.05f, -0.08f, 0f, -6f,
                -0.02f, 1.02f, 0.02f, 0f, -2f,
                -0.08f, 0.10f, 0.92f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f,
            ),
            contrast(1.1f), saturation(1.1f),
        )
        FilterPreset.WARM -> chain(scaleOffset(1.08f, 1.02f, 0.88f, 10f, 4f, -6f), saturation(1.1f))
        FilterPreset.COOL -> chain(scaleOffset(0.92f, 1.0f, 1.1f, -4f, 2f, 10f))
        FilterPreset.VINTAGE -> chain(
            matrix(
                0.70f, 0.30f, 0.10f, 0f, 0f,
                0.20f, 0.70f, 0.10f, 0f, 0f,
                0.15f, 0.25f, 0.55f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            scaleOffset(0.88f, 0.88f, 0.88f, 24f, 20f, 14f),
        )
        FilterPreset.FADE -> chain(saturation(0.8f), scaleOffset(0.84f, 0.84f, 0.84f, 28f, 28f, 30f))
        FilterPreset.BW -> chain(saturation(0f), contrast(1.1f))
        FilterPreset.NOIR -> chain(saturation(0f), contrast(1.45f), scaleOffset(1f, 1f, 1f, -10f, -10f, -10f))
    }

    private fun lerpMatrix(a: ColorMatrix, b: ColorMatrix, t: Float): ColorMatrix {
        val x = a.array
        val y = b.array
        return ColorMatrix(FloatArray(20) { x[it] + (y[it] - x[it]) * t })
    }

    /** Gesamte Farbmatrix (Android-Format 4x5, Offsets in 0..255). */
    fun build(adj: Adjustments): ColorMatrix {
        val out = lerpMatrix(ColorMatrix(), presetMatrix(adj.filter), adj.filterStrength)
        if (adj.brightness != 0f) {
            val o = adj.brightness * 70f
            out.postConcat(scaleOffset(1f, 1f, 1f, o, o, o))
        }
        if (adj.contrast != 0f) {
            out.postConcat(contrast(1f + adj.contrast * (if (adj.contrast > 0) 0.7f else 0.6f)))
        }
        if (adj.saturation != 0f) {
            out.postConcat(saturation(1f + adj.saturation))
        }
        if (adj.warmth != 0f) {
            val w = adj.warmth
            out.postConcat(scaleOffset(1f + w * 0.12f, 1f + w * 0.02f, 1f - w * 0.14f, w * 10f, w * 2f, -w * 10f))
        }
        if (adj.fade > 0f) {
            val s = 1f - adj.fade * 0.25f
            val o = adj.fade * 40f
            out.postConcat(scaleOffset(s, s, s, o, o, o))
        }
        return out
    }

    /**
     * Wandelt die Android-Matrix (4x5, Zeilen, Offsets 0..255) in eine Media3-RgbMatrix
     * (4x4, spaltenweise, wird auf vec4(rgb, 1) angewendet, Werte 0..1) um.
     */
    fun toMedia3(cm: ColorMatrix): FloatArray {
        val a = cm.array
        val m = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 3) m[col * 4 + row] = a[row * 5 + col]
            m[3 * 4 + row] = a[row * 5 + 4] / 255f
        }
        m[3 * 4 + 3] = 1f
        return m
    }
}
