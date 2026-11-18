package de.mawlo.mediaeditor.video

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import de.mawlo.mediaeditor.common.Adjustments
import de.mawlo.mediaeditor.common.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VideoTool(val label: String) {
    CUT("Schnitt"), FILTER("Filter"), ADJUST("Anpassen"), TEXT("Text"), AUDIO("Musik"), FORMAT("Format")
}

@OptIn(UnstableApi::class)
class VideoEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    val player: ExoPlayer = ExoPlayer.Builder(app).build().apply { repeatMode = Player.REPEAT_MODE_ALL }
    private val musicPlayer: ExoPlayer = ExoPlayer.Builder(app).build().apply { repeatMode = Player.REPEAT_MODE_ONE }

    var clips by mutableStateOf<List<Clip>>(emptyList()); private set
    var selectedClipId by mutableStateOf<Long?>(null)
    var tool by mutableStateOf(VideoTool.CUT)

    var adj by mutableStateOf(Adjustments()); private set
    var texts by mutableStateOf<List<TextItem>>(emptyList())
    var selectedTextId by mutableStateOf<Long?>(null)

    var format by mutableStateOf(VideoFormat.ORIGINAL); private set
    var speed by mutableStateOf(1f); private set

    var music by mutableStateOf<Uri?>(null); private set
    var musicName by mutableStateOf<String?>(null); private set
    var musicVolume by mutableStateOf(0.8f); private set
    var originalVolume by mutableStateOf(1f); private set

    var busy by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<Float?>(null); private set
    var message by mutableStateOf<String?>(null)

    private var exporter: VideoExporter? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (music == null) return
                if (isPlaying) musicPlayer.play() else musicPlayer.pause()
            }
        })
    }

    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = "Videos werden geladen …"
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { uris.map { ClipLoader.load(ctx, it) } }
                    .filter { it.durationMs > 0 }
                clips = clips + loaded
                if (selectedClipId == null) selectedClipId = clips.firstOrNull()?.id
                rebuildPlaylist()
            } catch (t: Throwable) {
                message = "Video konnte nicht geladen werden: ${t.message}"
            } finally {
                busy = null
            }
        }
    }

    fun removeClip(id: Long) {
        clips = clips.filterNot { it.id == id }
        if (selectedClipId == id) selectedClipId = clips.firstOrNull()?.id
        rebuildPlaylist()
    }

    fun moveClip(id: Long, delta: Int) {
        val i = clips.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in clips.indices) return
        clips = clips.toMutableList().apply { add(j, removeAt(i)) }
        rebuildPlaylist()
    }

    /** Beim Ziehen nur den Zustand ändern; Playlist erst beim Loslassen neu aufbauen. */
    fun trimClip(id: Long, startMs: Long, endMs: Long) {
        clips = clips.map { if (it.id == id) it.copy(startMs = startMs, endMs = endMs) else it }
    }

    fun trimFinished() = rebuildPlaylist()

    private fun rebuildPlaylist() {
        val items = clips.map { c ->
            MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
            ).build()
        }
        player.setMediaItems(items)
        player.prepare()
        applyPreviewEffects()
    }

    private fun applyPreviewEffects() {
        if (clips.isEmpty()) return
        val req = request()
        val effects = buildList {
            add(VideoEffects.presentation(req))
            addAll(VideoEffects.colorEffects(adj))
        }
        player.setVideoEffects(effects)
        player.playbackParameters = PlaybackParameters(speed)
        player.volume = originalVolume
        musicPlayer.volume = musicVolume
    }

    fun updateAdj(a: Adjustments) {
        adj = a
        applyPreviewEffects()
    }

    fun changeFormat(f: VideoFormat) { format = f; applyPreviewEffects() }
    fun changeSpeed(s: Float) { speed = s; applyPreviewEffects() }
    fun changeOriginalVolume(v: Float) { originalVolume = v; player.volume = v }
    fun changeMusicVolume(v: Float) { musicVolume = v; musicPlayer.volume = v }

    fun chooseMusic(uri: Uri?) {
        music = uri
        musicName = uri?.let { u ->
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Musik"
        }
        if (uri != null) {
            musicPlayer.setMediaItem(MediaItem.fromUri(uri))
            musicPlayer.prepare()
            musicPlayer.volume = musicVolume
            if (player.isPlaying) musicPlayer.play()
        } else {
            musicPlayer.stop()
            musicPlayer.clearMediaItems()
        }
    }

    // ---------- Text ----------
    fun addText(text: String) {
        val t = TextItem(text = text, y = 0.75f)
        texts = texts + t
        selectedTextId = t.id
    }

    fun updateText(id: Long, f: (TextItem) -> TextItem) {
        texts = texts.map { if (it.id == id) f(it) else it }
    }

    fun deleteText(id: Long) {
        texts = texts.filterNot { it.id == id }
        if (selectedTextId == id) selectedTextId = null
    }

    // ---------- Export ----------
    fun request() = ExportRequest(clips, adj, texts, format, speed, originalVolume, music, musicVolume)

    /** Seitenverhältnis des fertigen Videos (für Vorschau-Rahmen). */
    val outputAspect: Float get() {
        if (clips.isEmpty()) return 9f / 16f
        val (w, h) = VideoEffects.outputSize(request())
        return w.toFloat() / h
    }

    val totalDurationMs: Long get() = (clips.sumOf { it.trimmedMs } / speed).toLong()

    fun export() {
        if (clips.isEmpty() || busy != null) return
        player.pause()
        busy = "Video wird erstellt …"
        progress = 0f
        val e = VideoExporter(ctx)
        exporter = e
        e.export(request(), onProgress = { progress = it }) { result ->
            busy = null
            progress = null
            exporter = null
            message = result.fold(
                { "Gespeichert in Galerie › Filme › MediaStudio" },
                { "Export fehlgeschlagen: ${it.cause?.message ?: it.message}" },
            )
        }
    }

    fun cancelExport() {
        exporter?.cancel()
        exporter = null
        busy = null
        progress = null
        message = "Export abgebrochen"
    }

    override fun onCleared() {
        exporter?.cancel()
        player.release()
        musicPlayer.release()
    }
}
