package de.mawlo.mediaeditor.video

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.max
import kotlin.math.min

/**
 * Übergänge zwischen zwei Clips.
 * Bewegung (Wischen, Zoom, Drehen) übernimmt der Video-Kompositor,
 * Licht-/Farbeffekte (Blitz, Glitch, RGB) der FX-Shader.
 */
enum class TransitionType(val label: String, val defaultMs: Long) {
    CUT("Schnitt", 0),
    FADE("Überblenden", 600),
    FLASH("Blitz", 400),
    DIP_BLACK("Schwarzblende", 700),
    SLIDE_LEFT("Wischen ←", 450),
    SLIDE_RIGHT("Wischen →", 450),
    SLIDE_UP("Wischen ↑", 450),
    ZOOM_IN("Zoom rein", 500),
    ZOOM_OUT("Zoom raus", 500),
    SPIN("Drehen", 600),
    GLITCH("Glitch", 350),
    RGB_SPLIT("RGB-Split", 400),
    SHAKE("Wackeln", 400),
}

/** Position eines Clips auf der Zeitachse des fertigen Videos (alles in Mikrosekunden). */
data class PlacedClip(
    val index: Int,
    val clip: Clip,
    val input: Int,
    val startUs: Long,
    val durationUs: Long,
    /** Übergang IN diesen Clip (vom vorherigen). */
    val transitionIn: TransitionType,
    val transitionInUs: Long,
) {
    val endUs get() = startUs + durationUs
}

data class TimelinePlan(val clips: List<PlacedClip>, val totalUs: Long) {
    val usesTwoTracks get() = clips.any { it.transitionInUs > 0 }

    /** Zeitpunkt (Mitte des Übergangs), an dem Clip [i] sichtbar übernimmt. */
    fun cutPointUs(i: Int): Long = clips[i].startUs + clips[i].transitionInUs / 2

    companion object {
        /** Kürzester erlaubter Clip (damit Übergänge nicht überlappen). */
        fun maxTransitionUs(prevDurUs: Long, curDurUs: Long): Long = min(prevDurUs, curDurUs) * 45 / 100

        fun build(clips: List<Clip>, speed: Float): TimelinePlan {
            val placed = ArrayList<PlacedClip>()
            var cursor = 0L
            clips.forEachIndexed { i, c ->
                val dur = (c.trimmedMs * 1000 / speed).toLong()
                var t = 0L
                if (i > 0 && c.transition != TransitionType.CUT) {
                    val prev = placed[i - 1]
                    t = min(c.transitionMs * 1000, maxTransitionUs(prev.durationUs, dur))
                    // Mit dem Übergang davor darf sich nichts überschneiden
                    t = min(t, max(0L, prev.durationUs - prev.transitionInUs - 50_000))
                }
                val start = max(0L, cursor - t)
                placed += PlacedClip(i, c, i % 2, start, dur, if (t > 0) c.transition else TransitionType.CUT, t)
                cursor = start + dur
            }
            return TimelinePlan(placed, cursor)
        }
    }
}

private fun ease(p: Float): Float {
    val x = p.coerceIn(0f, 1f)
    return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
}

/** Steuert pro Einzelbild, wie die beiden Spuren übereinandergelegt werden. Spur 0 liegt oben. */
@UnstableApi
class TransitionCompositorSettings(
    private val plan: TimelinePlan,
    private val width: Int,
    private val height: Int,
) : VideoCompositorSettings {

    private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    private val normal = StaticOverlaySettings.Builder().build()

    override fun getOutputSize(inputSizes: List<Size>) = Size(width, height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val t = presentationTimeUs
        val clip = plan.clips.firstOrNull { it.input == inputId && t >= it.startUs - 20_000 && t < it.endUs }
            ?: return hidden
        if (inputId != 0) return normal // untere Spur bleibt immer ganz

        // Übergang in diesen Clip hinein?
        if (clip.transitionInUs > 0 && t < clip.startUs + clip.transitionInUs) {
            val p = (t - clip.startUs).toFloat() / clip.transitionInUs
            return incoming(clip.transitionIn, ease(p))
        }
        // Übergang aus diesem Clip heraus?
        val next = plan.clips.getOrNull(clip.index + 1)
        if (next != null && next.transitionInUs > 0 && t >= next.startUs) {
            val p = (t - next.startUs).toFloat() / next.transitionInUs
            return outgoing(next.transitionIn, ease(p))
        }
        return normal
    }

    /** Oberer Clip kommt herein (p: 0 → 1). */
    private fun incoming(type: TransitionType, p: Float): OverlaySettings {
        val b = StaticOverlaySettings.Builder()
        when (type) {
            TransitionType.SLIDE_LEFT -> b.setBackgroundFrameAnchor(2f * (1 - p), 0f)
            TransitionType.SLIDE_RIGHT -> b.setBackgroundFrameAnchor(-2f * (1 - p), 0f)
            TransitionType.SLIDE_UP -> b.setBackgroundFrameAnchor(0f, -2f * (1 - p))
            TransitionType.ZOOM_IN -> { val s = 0.4f + 0.6f * p; b.setScale(s, s).setAlphaScale(p) }
            TransitionType.ZOOM_OUT -> { val s = 1.8f - 0.8f * p; b.setScale(s, s).setAlphaScale(p) }
            TransitionType.SPIN -> { val s = 0.3f + 0.7f * p; b.setScale(s, s).setRotationDegrees(-200f * (1 - p)).setAlphaScale(min(1f, p * 1.5f)) }
            TransitionType.GLITCH, TransitionType.SHAKE -> b.setAlphaScale(if (p < 0.5f) 0f else 1f)
            else -> b.setAlphaScale(p)
        }
        return b.build()
    }

    /** Oberer Clip geht hinaus (p: 0 → 1), darunter wird der nächste sichtbar. */
    private fun outgoing(type: TransitionType, p: Float): OverlaySettings {
        val b = StaticOverlaySettings.Builder()
        when (type) {
            TransitionType.SLIDE_LEFT -> b.setBackgroundFrameAnchor(-2f * p, 0f)
            TransitionType.SLIDE_RIGHT -> b.setBackgroundFrameAnchor(2f * p, 0f)
            TransitionType.SLIDE_UP -> b.setBackgroundFrameAnchor(0f, 2f * p)
            TransitionType.ZOOM_IN -> { val s = 1f + 0.8f * p; b.setScale(s, s).setAlphaScale(1 - p) }
            TransitionType.ZOOM_OUT -> { val s = 1f - 0.6f * p; b.setScale(s, s).setAlphaScale(1 - p) }
            TransitionType.SPIN -> { val s = 1f - 0.7f * p; b.setScale(s, s).setRotationDegrees(200f * p).setAlphaScale(1 - p) }
            TransitionType.GLITCH, TransitionType.SHAKE -> b.setAlphaScale(if (p < 0.5f) 1f else 0f)
            else -> b.setAlphaScale(1 - p)
        }
        return b.build()
    }
}
