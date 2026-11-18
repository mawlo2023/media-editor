package de.mawlo.mediaeditor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BeatDetectorTest {

    /** Synthetischer Song: Bass-Kick auf jedem Beat, Hi-Hat dazwischen, etwas Rauschen. */
    private fun song(bpm: Double, seconds: Int, rate: Int = 44100, offsetSec: Double = 0.23): FloatArray {
        val out = FloatArray(rate * seconds)
        val beat = 60.0 / bpm
        val rnd = Random(1)
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val tb = t - offsetSec
            var x = 0.0
            if (tb >= 0) {
                val inBeat = tb % beat
                x += 0.9 * sin(2 * PI * 55 * inBeat) * exp(-inBeat * 18)          // Kick
                val inHalf = (tb + beat / 2) % beat
                x += 0.15 * (rnd.nextDouble() - 0.5) * exp(-inHalf * 60)          // Hi-Hat
            }
            x += 0.2 * sin(2 * PI * 220 * t) + 0.03 * (rnd.nextDouble() - 0.5)   // Flächen + Rauschen
            out[i] = x.toFloat()
        }
        return out
    }

    @Test
    fun detectsTempoAndPhase() {
        for (bpm in listOf(95.0, 120.0, 128.0, 150.0)) {
            val info = BeatDetector.analyze(song(bpm, 30), 44100)
            assertEquals("BPM für $bpm", bpm, info.bpm.toDouble(), 2.0)
            val period = 60_000.0 / bpm
            // Beats müssen nahe am echten Raster liegen (±40 ms)
            val off = info.beatsMs.drop(4).map { ms -> ((ms - 230.0) % period + period) % period }
                .map { if (it > period / 2) it - period else it }
            assertTrue("Phase daneben für $bpm: ${off.take(5)}", off.count { abs(it) < 40 } > off.size * 0.9)
        }
    }
}
