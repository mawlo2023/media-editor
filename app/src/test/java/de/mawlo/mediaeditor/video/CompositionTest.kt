package de.mawlo.mediaeditor.video

import android.net.Uri
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import de.mawlo.mediaeditor.common.Adjustments
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionTest {

    private fun clip(id: Long, t: TransitionType = TransitionType.CUT) =
        Clip(id = id, uri = Uri.parse("file:///sdcard/v$id.mp4"), durationMs = 4000, width = 1080, height = 1920,
            transition = t, transitionMs = t.defaultMs)

    private fun req(clips: List<Clip>, music: Boolean = false) = ExportRequest(
        clips, Adjustments(), emptyList(), VideoFormat.TIKTOK, 1f, 1f,
        if (music) Uri.parse("file:///sdcard/m.mp3") else null, 0.8f, 0, 60_000, LongArray(0),
        emptySet(), 0.8f, StyleFx.NONE, 0.6f,
    )

    private fun play(r: ExportRequest) {
        val ctx = RuntimeEnvironment.getApplication()
        val p = CompositionPlayer.Builder(ctx).setVideoGraphFactory(MultipleInputVideoGraph.Factory()).build()
        try {
            p.setComposition(VideoEffects.buildComposition(r, forExport = false), 0)
            p.prepare()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        } finally {
            p.release()
        }
    }

    @Test fun singleClip() = play(req(listOf(clip(1))))
    @Test fun twoClipsCut() = play(req(listOf(clip(1), clip(2))))
    @Test fun twoClipsFade() = play(req(listOf(clip(1), clip(2, TransitionType.FADE))))
    @Test fun threeClipsFadeMusic() = play(req(listOf(clip(1), clip(2, TransitionType.FADE), clip(3, TransitionType.GLITCH)), music = true))
    private fun export(r: ExportRequest) {
        val ctx = RuntimeEnvironment.getApplication()
        val t = androidx.media3.transformer.Transformer.Builder(ctx)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC).build()
        t.start(VideoEffects.buildComposition(r, forExport = true), java.io.File(ctx.cacheDir, "o.mp4").absolutePath)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        t.cancel()
    }

    @Test fun exportSingle() = export(req(listOf(clip(1))))
    @Test fun exportTransitionsMusic() = export(req(listOf(clip(1), clip(2, TransitionType.FADE), clip(3, TransitionType.SPIN)), music = true))
    @Test fun exportComposition() { VideoEffects.buildComposition(req(listOf(clip(1), clip(2, TransitionType.FADE)), true), forExport = true) }
}
