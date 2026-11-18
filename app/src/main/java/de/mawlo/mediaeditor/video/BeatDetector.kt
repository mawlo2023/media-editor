package de.mawlo.mediaeditor.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class BeatInfo(val bpm: Float, val beatsMs: LongArray, val durationMs: Long)

/**
 * Einfache, robuste Beat-Erkennung (offline):
 * 1. Musik dekodieren und auf Mono reduzieren
 * 2. „Onset“-Kurve aus Energiesprüngen (Bass betont)
 * 3. Tempo per Autokorrelation (70–180 BPM), Phase per Raster-Abgleich
 * 4. Jeden Beat auf die nächste echte Spitze verfeinern
 */
object BeatDetector {

    private const val HOP = 512
    private const val MAX_SECONDS = 300

    fun detect(context: Context, uri: Uri): BeatInfo {
        val (samples, rate) = decodeMono(context, uri)
        return analyze(samples, rate)
    }

    fun analyze(samples: FloatArray, rate: Int): BeatInfo {
        val hopSec = HOP.toDouble() / rate
        val frames = samples.size / HOP
        if (frames < 50) return BeatInfo(0f, LongArray(0), (samples.size * 1000L / rate))

        // Energie gesamt + Bass (einpoliger Tiefpass ~150 Hz)
        val alpha = (1 - exp(-2 * Math.PI * 150 / rate)).toFloat()
        var lp = 0f
        val eFull = FloatArray(frames)
        val eLow = FloatArray(frames)
        for (f in 0 until frames) {
            var sf = 0f
            var sl = 0f
            for (i in f * HOP until (f + 1) * HOP) {
                val x = samples[i]
                lp += alpha * (x - lp)
                sf += x * x
                sl += lp * lp
            }
            eFull[f] = ln(1e-6f + sqrt(sf / HOP))
            eLow[f] = ln(1e-6f + sqrt(sl / HOP))
        }
        val onset = FloatArray(frames)
        for (f in 1 until frames) {
            onset[f] = 0.65f * max(0f, eLow[f] - eLow[f - 1]) + 0.35f * max(0f, eFull[f] - eFull[f - 1])
        }
        // Glätten + Mittelwert abziehen
        val sm = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f; var n = 0
            for (k in -1..1) { val j = f + k; if (j in 0 until frames) { s += onset[j]; n++ } }
            sm[f] = s / n
        }
        val mean = sm.average().toFloat()
        for (f in 0 until frames) sm[f] = max(0f, sm[f] - mean)

        // Tempo per Autokorrelation, leicht bevorzugt um 120 BPM
        val minLag = (60.0 / 180 / hopSec).roundToInt()
        val maxLag = (60.0 / 70 / hopSec).roundToInt()
        var bestLag = minLag
        var bestScore = -1.0
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (f in 0 until frames - lag) s += sm[f] * sm[f + lag]
            s /= (frames - lag)
            val bpm = 60.0 / (lag * hopSec)
            val w = exp(-0.5 * (ln(bpm / 120.0) / 0.45).let { it * it })
            scores[lag] = s * w
            if (scores[lag] > bestScore) { bestScore = scores[lag]; bestLag = lag }
        }
        // Parabel-Interpolation für genaueres Tempo
        var period = bestLag.toDouble()
        if (bestLag in (minLag + 1) until maxLag) {
            val a = scores[bestLag - 1]; val b = scores[bestLag]; val c = scores[bestLag + 1]
            val d = a - 2 * b + c
            if (d != 0.0) period += 0.5 * (a - c) / d
        }

        // Phase: wo liegt das Raster am besten auf den Spitzen?
        var bestPhase = 0
        var bestPhaseScore = -1.0
        for (p in 0 until period.toInt().coerceAtLeast(1)) {
            var s = 0.0
            var t = p.toDouble()
            while (t < frames) { s += sm[t.toInt()]; t += period }
            if (s > bestPhaseScore) { bestPhaseScore = s; bestPhase = p }
        }

        // Beats erzeugen und lokal auf Spitzen ziehen
        val beats = ArrayList<Long>()
        val win = max(1, (period * 0.12).toInt())
        var t = bestPhase.toDouble()
        while (t < frames) {
            val c = t.roundToInt()
            var best = c
            for (j in max(0, c - win)..min(frames - 1, c + win)) if (sm[j] > sm[best]) best = j
            val ms = (best * hopSec * 1000).toLong()
            if (beats.isEmpty() || ms - beats.last() > period * hopSec * 500) beats += ms
            t += period
        }
        val bpm = (60.0 / (period * hopSec)).toFloat()
        return BeatInfo(bpm, beats.toLongArray(), (samples.size * 1000L / rate))
    }

    /** Dekodiert die Audiospur zu Mono-Float-Samples (-1..1). */
    private fun decodeMono(context: Context, uri: Uri): Pair<FloatArray, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Keine Tonspur gefunden")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var out = FloatArray(rate * 60)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val maxSamples = rate.toLong() * MAX_SECONDS
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || n >= maxSamples) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = sb.remaining() / channels
                        if (n + frames > out.size) out = out.copyOf(max(out.size * 2, n + frames))
                        for (i in 0 until frames) {
                            var s = 0
                            for (c in 0 until channels) s += sb.get()
                            out[n++] = s / (channels * 32768f)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        return out.copyOf(n) to rate
    }
}
