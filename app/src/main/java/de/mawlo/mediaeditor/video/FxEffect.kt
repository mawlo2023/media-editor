package de.mawlo.mediaeditor.video

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Effekte, die auf jeden Beat der Musik ausgelöst werden. */
enum class BeatFx(val label: String) {
    ZOOM("Zoom-Puls"), FLASH("Blitz"), RGB("RGB-Split"), GLITCH("Glitch"), SHAKE("Wackeln")
}

/** Dauerhafte Looks (laufen über das ganze Video). */
enum class StyleFx(val label: String) {
    NONE("Keiner"), RGB("RGB-Verschiebung"), VHS("VHS"), GRAIN("Filmkorn")
}

/** Stärke aller Shader-Effekte zu einem Zeitpunkt. */
data class FxState(
    var flash: Float = 0f, var dark: Float = 0f, var rgb: Float = 0f, var glitch: Float = 0f,
    var zoomBlur: Float = 0f, var zoom: Float = 1f, var shakeX: Float = 0f, var shakeY: Float = 0f,
    var grain: Float = 0f, var scan: Float = 0f,
)

/** Berechnet für jeden Zeitpunkt, welche Effekte wie stark wirken. */
class FxTimeline(
    private val plan: TimelinePlan,
    /** Beat-Zeitpunkte im fertigen Video (µs). */
    private val beatsUs: LongArray,
    private val beatFx: Set<BeatFx>,
    private val beatIntensity: Float,
    private val style: StyleFx,
    private val styleIntensity: Float,
) {
    val isNoOp: Boolean
        get() = style == StyleFx.NONE && (beatFx.isEmpty() || beatsUs.isEmpty()) &&
            plan.clips.none { it.transitionIn in shaderTransitions && it.transitionInUs > 0 }

    private val shaderTransitions = setOf(
        TransitionType.FLASH, TransitionType.DIP_BLACK, TransitionType.GLITCH, TransitionType.RGB_SPLIT,
        TransitionType.ZOOM_IN, TransitionType.ZOOM_OUT, TransitionType.SHAKE, TransitionType.SPIN,
    )

    fun at(tUs: Long, s: FxState) {
        s.flash = 0f; s.dark = 0f; s.rgb = 0f; s.glitch = 0f; s.zoomBlur = 0f; s.zoom = 1f
        s.shakeX = 0f; s.shakeY = 0f; s.grain = 0f; s.scan = 0f
        val sec = tUs / 1_000_000f

        // Übergänge: Spitze genau in der Mitte des Übergangs
        for (c in plan.clips) {
            if (c.transitionInUs <= 0) continue
            val mid = c.startUs + c.transitionInUs / 2
            val half = max(c.transitionInUs / 2, 120_000L)
            val d = abs(tUs - mid)
            if (d >= half) continue
            val a = 1f - d.toFloat() / half
            when (c.transitionIn) {
                TransitionType.FLASH -> s.flash = max(s.flash, a)
                TransitionType.DIP_BLACK -> s.dark = max(s.dark, a)
                TransitionType.GLITCH -> { s.glitch = max(s.glitch, a); s.rgb = max(s.rgb, a) }
                TransitionType.RGB_SPLIT -> s.rgb = max(s.rgb, a * 1.6f)
                TransitionType.ZOOM_IN, TransitionType.ZOOM_OUT, TransitionType.SPIN -> s.zoomBlur = max(s.zoomBlur, a)
                TransitionType.SHAKE -> {
                    s.shakeX += sin(sec * 90f) * 0.04f * a
                    s.shakeY += sin(sec * 70f + 1f) * 0.03f * a
                    s.zoom = max(s.zoom, 1f + 0.1f * a)
                }
                else -> Unit
            }
        }

        // Beat-Effekte: kurzer Impuls nach jedem Beat
        if (beatFx.isNotEmpty() && beatsUs.isNotEmpty()) {
            val idx = java.util.Arrays.binarySearch(beatsUs, tUs).let { if (it >= 0) it else -it - 2 }
            if (idx >= 0) {
                val since = tUs - beatsUs[idx]
                val len = 220_000L
                if (since in 0 until len) {
                    val k = (1f - since.toFloat() / len).let { it * it } * beatIntensity
                    if (BeatFx.ZOOM in beatFx) s.zoom = max(s.zoom, 1f + 0.09f * k)
                    if (BeatFx.FLASH in beatFx) s.flash = max(s.flash, 0.55f * k)
                    if (BeatFx.RGB in beatFx) s.rgb = max(s.rgb, 1.2f * k)
                    if (BeatFx.GLITCH in beatFx) s.glitch = max(s.glitch, k)
                    if (BeatFx.SHAKE in beatFx) {
                        s.shakeX += sin(sec * 80f) * 0.03f * k
                        s.shakeY += sin(sec * 63f + 2f) * 0.025f * k
                        s.zoom = max(s.zoom, 1f + 0.06f * k)
                    }
                }
            }
        }

        when (style) {
            StyleFx.RGB -> s.rgb = max(s.rgb, 0.35f * styleIntensity)
            StyleFx.VHS -> {
                s.rgb = max(s.rgb, 0.3f * styleIntensity)
                s.scan = 0.5f * styleIntensity
                s.grain = 0.25f * styleIntensity
                if (((sec * 3).toInt() % 7) == 0) s.glitch = max(s.glitch, 0.25f * styleIntensity)
            }
            StyleFx.GRAIN -> s.grain = 0.45f * styleIntensity
            StyleFx.NONE -> Unit
        }
    }
}

/** Media3-Effekt mit eigenem Shader (RGB-Split, Glitch, Blitz, Zoom-Blur, Wackeln, VHS …). */
@UnstableApi
class FxEffect(private val timeline: FxTimeline) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = FxShaderProgram(useHdr, timeline)
}

@UnstableApi
private class FxShaderProgram(useHdr: Boolean, private val timeline: FxTimeline) : BaseGlShaderProgram(useHdr, 1) {

    private val program = GlProgram(VERTEX, FRAGMENT).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }
    private val state = FxState()
    private var aspect = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        timeline.at(presentationTimeUs, state)
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uFlash", state.flash)
            program.setFloatUniform("uDark", state.dark)
            program.setFloatUniform("uRgb", state.rgb)
            program.setFloatUniform("uGlitch", state.glitch)
            program.setFloatUniform("uZoomBlur", state.zoomBlur)
            program.setFloatUniform("uZoom", state.zoom)
            program.setFloatsUniform("uShake", floatArrayOf(state.shakeX, state.shakeY))
            program.setFloatUniform("uGrain", state.grain)
            program.setFloatUniform("uScan", state.scan)
            program.setFloatUniform("uTime", (presentationTimeUs % 100_000_000L) / 1_000_000f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw androidx.media3.common.VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (_: GlUtil.GlException) { }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform float uFlash;
uniform float uDark;
uniform float uRgb;
uniform float uGlitch;
uniform float uZoomBlur;
uniform float uZoom;
uniform vec2 uShake;
uniform float uGrain;
uniform float uScan;
uniform float uTime;
varying vec2 vTexSamplingCoord;

float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }

vec4 tex(vec2 uv) { return texture2D(uTexSampler, clamp(uv, 0.0, 1.0)); }

void main() {
  vec2 uv = (vTexSamplingCoord - 0.5) / uZoom + 0.5 + uShake;

  if (uGlitch > 0.0) {
    float block = floor(uv.y * 18.0);
    float n = rand(vec2(block, floor(uTime * 24.0)));
    if (n < uGlitch * 0.7) {
      uv.x += (rand(vec2(block, floor(uTime * 24.0) + 1.0)) - 0.5) * 0.3 * uGlitch;
    }
  }

  vec4 c;
  if (uZoomBlur > 0.01) {
    vec2 dir = uv - 0.5;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 12; i++) {
      float s = 1.0 - uZoomBlur * 0.3 * float(i) / 11.0;
      acc += tex(0.5 + dir * s);
    }
    c = acc / 12.0;
  } else {
    c = tex(uv);
  }

  float off = uRgb * 0.018 + uGlitch * 0.02;
  if (off > 0.0005) {
    c.r = tex(uv + vec2(off, off * 0.3)).r;
    c.b = tex(uv - vec2(off, off * 0.3)).b;
  }

  if (uScan > 0.0) {
    float line = sin(vTexSamplingCoord.y * 900.0) * 0.5 + 0.5;
    c.rgb *= 1.0 - uScan * 0.25 * line;
  }
  if (uGrain > 0.0) {
    float g = rand(vTexSamplingCoord * 1000.0 + uTime) - 0.5;
    c.rgb += g * uGrain * 0.25;
  }

  c.rgb = mix(c.rgb, vec3(1.0), clamp(uFlash, 0.0, 1.0));
  c.rgb = mix(c.rgb, vec3(0.0), clamp(uDark, 0.0, 1.0));
  gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0), 1.0);
}
"""
    }
}
