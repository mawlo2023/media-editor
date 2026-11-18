package de.mawlo.mediaeditor.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

data class Clip(
    val id: Long = System.nanoTime(),
    val uri: Uri,
    val durationMs: Long,
    /** Anzeigegröße (Drehung bereits berücksichtigt). */
    val width: Int,
    val height: Int,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
    val thumb: Bitmap? = null,
) {
    val trimmedMs get() = endMs - startMs
}

enum class VideoFormat(val label: String, val width: Int, val height: Int) {
    ORIGINAL("Original", 0, 0),
    TIKTOK("9:16 TikTok", 1080, 1920),
    SQUARE("1:1", 1080, 1080),
    PORTRAIT("4:5", 1080, 1350),
    WIDE("16:9", 1920, 1080),
}

val SpeedOptions = listOf(0.5f, 0.75f, 1f, 1.5f, 2f)

object ClipLoader {
    fun load(context: Context, uri: Uri): Clip {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val frame = r.getScaledFrameAtTime(
                minOf(1_000_000L, dur * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240,
            )
            return Clip(uri = uri, durationMs = dur, width = w, height = h, thumb = frame)
        } finally {
            r.release()
        }
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val tenth = (ms % 1000) / 100
    return "%d:%02d.%d".format(s / 60, s % 60, tenth)
}
