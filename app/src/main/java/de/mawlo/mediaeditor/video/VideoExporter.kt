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
import de.mawlo.mediaeditor.common.FilterPreset
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
    val musicStartMs: Long,
    val musicDurationMs: Long,
    /** Beats in der Musikdatei (ms). */
    val musicBeatsMs: LongArray,
    val beatFx: Set<BeatFx>,
    val beatIntensity: Float,
    val style: StyleFx,
    val styleIntensity: Float,
) {
    val plan: TimelinePlan by lazy { TimelinePlan.build(clips, speed) }

    /** Beats auf der Zeitachse des fertigen Videos (µs) – Musik läuft in Schleife ab [musicStartMs]. */
    fun outputBeatsUs(): LongArray {
        if (music == null || musicBeatsMs.isEmpty()) return LongArray(0)
        val loopMs = musicDurationMs - musicStartMs
        if (loopMs <= 0) return LongArray(0)
        val total = plan.totalUs
        val out = ArrayList<Long>()
        var base = 0L
        while (base * 1000 < total) {
            for (b in musicBeatsMs) {
                if (b < musicStartMs) continue
                val t = (base + b - musicStartMs) * 1000
                if (t >= total) break
                out += t
            }
            base += loopMs
        }
        return out.toLongArray()
    }
}

@UnstableApi
object VideoEffects {

    fun outputSize(clips: List<Clip>, format: VideoFormat): Pair<Int, Int> {
        if (format != VideoFormat.ORIGINAL || clips.isEmpty()) {
            return if (format == VideoFormat.ORIGINAL) 1080 to 1920 else format.width to format.height
        }
        val c = clips.first()
        val s = minOf(1f, 1920f / max(c.width, c.height))
        fun even(v: Float) = ((v.roundToInt() / 2) * 2).coerceAtLeast(2)
        return even(c.width * s) to even(c.height * s)
    }

    fun colorEffects(adj: Adjustments): List<Effect> {
        if (adj.filter == FilterPreset.NONE && adj.brightness == 0f && adj.contrast == 0f &&
            adj.saturation == 0f && adj.warmth == 0f && adj.fade == 0f
        ) return emptyList()
        val m = ColorFilters.toMedia3(ColorFilters.build(adj))
        return listOf(RgbMatrix { _, _ -> m })
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    private fun volume(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(ch, ch).scaleBy(v))
    }

    /**
     * Baut die komplette Komposition – identisch für Vorschau und Export.
     * Bei Übergängen werden die Clips abwechselnd auf zwei Spuren gelegt, die sich überlappen.
     */
    fun buildComposition(req: ExportRequest, forExport: Boolean): Composition {
        val plan = req.plan
        val (w, h) = outputSize(req.clips, req.format)
        val layout = if (req.format == VideoFormat.ORIGINAL) Presentation.LAYOUT_SCALE_TO_FIT
        else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP

        fun item(c: Clip): EditedMediaItem {
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
            ).build()
            val b = EditedMediaItem.Builder(mi)
                .setDurationUs(c.durationMs * 1000)
                .setEffects(Effects(listOf(volume(req.originalVolume)), listOf(Presentation.createForWidthAndHeight(w, h, layout))))
            if (req.speed != 1f) b.setSpeed(ConstantSpeed(req.speed))
            return b.build()
        }

        val av = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        val sequences = ArrayList<EditedMediaItemSequence>()
        if (!plan.usesTwoTracks) {
            sequences += EditedMediaItemSequence.Builder(av).addItems(plan.clips.map { item(it.clip) })
                .experimentalSetForceAudioTrack(true).build()
        } else {
            for (track in 0..1) {
                val b = EditedMediaItemSequence.Builder(av)
                var cursor = 0L
                var any = false
                for (p in plan.clips.filter { it.input == track }) {
                    if (p.startUs > cursor) b.addGap(p.startUs - cursor)
                    b.addItem(item(p.clip))
                    cursor = p.endUs
                    any = true
                }
                if (!any) continue
                if (plan.totalUs > cursor) b.addGap(plan.totalUs - cursor)
                sequences += b.experimentalSetForceAudioTrack(true).experimentalSetForceVideoTrack(true).build()
            }
        }

        if (req.music != null) {
            val mi = MediaItem.Builder().setUri(req.music).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(req.musicStartMs).build()
            ).build()
            val music = EditedMediaItem.Builder(mi)
                .setRemoveVideo(true)
                .setDurationUs(req.musicDurationMs * 1000)
                .setEffects(Effects(listOf(volume(req.musicVolume)), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(music).setIsLooping(true).build()
        }

        val video = ArrayList<Effect>()
        video += colorEffects(req.adj)
        val fx = FxTimeline(plan, req.outputBeatsUs(), req.beatFx, req.beatIntensity, req.style, req.styleIntensity)
        if (!fx.isNoOp) video += FxEffect(fx)
        if (forExport && (req.texts.isNotEmpty() || req.adj.vignette > 0f)) {
            val bmp = TextRenderer.renderOverlay(w, h, req.texts, req.adj.vignette)
            video += OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bmp)))
        }

        val cb = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), video))
            .experimentalSetForceAudioTrack(true)
        if (plan.usesTwoTracks) cb.setVideoCompositorSettings(TransitionCompositorSettings(plan, w, h))
        return cb.build()
    }
}

@UnstableApi
class VideoExporter(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null

    /** Startet den Export (Haupt-Thread). */
    fun export(req: ExportRequest, onProgress: (Float) -> Unit, onDone: (Result<Uri>) -> Unit) {
        val (w, h) = VideoEffects.outputSize(req.clips, req.format)
        val composition = VideoEffects.buildComposition(req, forExport = true)

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
