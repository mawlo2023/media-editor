package de.mawlo.mediaeditor.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import de.mawlo.mediaeditor.common.Adjustments
import de.mawlo.mediaeditor.common.ColorFilters
import de.mawlo.mediaeditor.common.TextItem
import de.mawlo.mediaeditor.common.TextRenderer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class ExportRequest(
    val clips: List<Clip>,
    val adj: Adjustments,
    val texts: List<TextItem>,
    val format: VideoFormat,
    val speed: Float,
    val originalVolume: Float,
    val music: Uri?,
    val musicVolume: Float,
)

@UnstableApi
object VideoEffects {

    /** Ausgabegröße (gerade Zahlen, max. 1920 lange Seite). */
    fun outputSize(req: ExportRequest): Pair<Int, Int> {
        if (req.format != VideoFormat.ORIGINAL) return req.format.width to req.format.height
        val c = req.clips.first()
        val s = minOf(1f, 1920f / max(c.width, c.height))
        fun even(v: Float) = ((v.roundToInt() / 2) * 2).coerceAtLeast(2)
        return even(c.width * s) to even(c.height * s)
    }

    /** Farb-Effekte (Filter + Regler) – auch für die Live-Vorschau im Player. */
    fun colorEffects(adj: Adjustments): List<Effect> {
        if (adj.filter == de.mawlo.mediaeditor.common.FilterPreset.NONE &&
            adj.brightness == 0f && adj.contrast == 0f && adj.saturation == 0f && adj.warmth == 0f && adj.fade == 0f
        ) return emptyList()
        val m = ColorFilters.toMedia3(ColorFilters.build(adj))
        return listOf(RgbMatrix { _, _ -> m })
    }

    fun presentation(req: ExportRequest): Presentation {
        val (w, h) = outputSize(req)
        val layout = if (req.format == VideoFormat.ORIGINAL) Presentation.LAYOUT_SCALE_TO_FIT
        else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        return Presentation.createForWidthAndHeight(w, h, layout)
    }
}

@UnstableApi
class VideoExporter(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null

    private fun volumeProcessor(volume: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(volume))
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    /**
     * Startet den Export. Muss auf dem Haupt-Thread aufgerufen werden.
     * [onProgress] 0..1, [onDone] mit Galerie-Uri oder Fehler.
     */
    fun export(req: ExportRequest, onProgress: (Float) -> Unit, onDone: (Result<Uri>) -> Unit) {
        val (w, h) = VideoEffects.outputSize(req)
        val overlayBmp = if (req.texts.isNotEmpty() || req.adj.vignette > 0f)
            TextRenderer.renderOverlay(w, h, req.texts, req.adj.vignette) else null

        val items = req.clips.map { clip ->
            val mediaItem = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build()
                )
                .build()
            val audio = mutableListOf<AudioProcessor>()
            val video = mutableListOf<Effect>()
            if (req.speed != 1f) {
                val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(req.speed))
                audio += pair.first
                video += pair.second
            }
            audio += volumeProcessor(req.originalVolume)
            video += VideoEffects.presentation(req)
            video += VideoEffects.colorEffects(req.adj)
            if (overlayBmp != null) {
                video += OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlayBmp)))
            }
            EditedMediaItem.Builder(mediaItem).setEffects(Effects(audio, video)).build()
        }

        val sequences = mutableListOf(EditedMediaItemSequence(items))
        if (req.music != null) {
            val music = EditedMediaItem.Builder(MediaItem.fromUri(req.music))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volumeProcessor(req.musicVolume)), emptyList()))
                .build()
            sequences += EditedMediaItemSequence(listOf(music), /* isLooping= */ true)
        }
        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .build()

        val bitrate = if (w * h >= 1920 * 1080) 16_000_000 else 10_000_000
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()

        val out = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    try {
                        val uri = copyToGallery(out)
                        out.delete()
                        onDone(Result.success(uri))
                    } catch (e: Exception) {
                        onDone(Result.failure(e))
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopPolling()
                    out.delete()
                    onDone(Result.failure(exportException))
                }
            })
            .build()
        transformer = t
        t.start(composition, out.absolutePath)
        startPolling(onProgress)
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    private var poller: Runnable? = null

    private fun startPolling(onProgress: (Float) -> Unit) {
        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                main.postDelayed(this, 300)
            }
        }
        poller = r
        main.post(r)
    }

    private fun stopPolling() {
        poller?.let { main.removeCallbacks(it) }
        poller = null
    }

    private fun copyToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MediaStudio_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MediaStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Speichern nicht möglich")
        resolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
