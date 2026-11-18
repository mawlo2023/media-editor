package de.mawlo.mediaeditor.video

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import de.mawlo.mediaeditor.common.Adjustments
import de.mawlo.mediaeditor.common.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

enum class VideoTool(val label: String) {
    CUT("Schnitt"), TRANSITION("Übergänge"), EFFECTS("Effekte"), FILTER("Filter"), ADJUST("Anpassen"),
    TEXT("Text"), AUDIO("Musik"), FORMAT("Format")
}

@OptIn(UnstableApi::class)
class VideoEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    /** Vorschau-Player: spielt exakt die Komposition ab, die auch exportiert wird. */
    var player by mutableStateOf<CompositionPlayer?>(null); private set

    var clips by mutableStateOf<List<Clip>>(emptyList()); private set
    var selectedClipId by mutableStateOf<Long?>(null)
    var tool by mutableStateOf(VideoTool.CUT)

    var adj by mutableStateOf(Adjustments()); private set
    var texts by mutableStateOf<List<TextItem>>(emptyList())
    var selectedTextId by mutableStateOf<Long?>(null)

    var format by mutableStateOf(VideoFormat.TIKTOK); private set
    var speed by mutableStateOf(1f); private set

    // Musik + Beats
    var music by mutableStateOf<Uri?>(null); private set
    var musicName by mutableStateOf<String?>(null); private set
    var musicDurationMs by mutableStateOf(0L); private set
    var musicStartMs by mutableStateOf(0L); private set
    var musicVolume by mutableStateOf(0.8f); private set
    var originalVolume by mutableStateOf(1f); private set
    var beatInfo by mutableStateOf<BeatInfo?>(null); private set

    // Effekte
    var beatFx by mutableStateOf<Set<BeatFx>>(emptySet()); private set
    var beatIntensity by mutableStateOf(0.8f); private set
    var style by mutableStateOf(StyleFx.NONE); private set
    var styleIntensity by mutableStateOf(0.6f); private set

    // Wiedergabe
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableStateOf(0L); private set

    var busy by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<Float?>(null); private set
    var message by mutableStateOf<String?>(null)

    private var exporter: VideoExporter? = null
    private var rebuildJob: Job? = null
    private var pollJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onPlayerError(error: PlaybackException) {
            message = "Vorschau-Fehler: ${error.cause?.message ?: error.errorCodeName}"
        }
    }

    private fun ensurePlayer(): CompositionPlayer {
        player?.let { return it }
        val p = CompositionPlayer.Builder(ctx).build()
        p.addListener(listener)
        p.repeatMode = Player.REPEAT_MODE_ALL
        player = p
        pollJob = viewModelScope.launch {
            while (isActive) {
                player?.let { positionMs = it.currentPosition }
                delay(80)
            }
        }
        return p
    }

    // ---------- Clips ----------
    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = "Videos werden geladen …"
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { uris.map { ClipLoader.load(ctx, it) } }
                    .filter { it.durationMs > 0 }
                clips = clips + loaded
                if (selectedClipId == null) selectedClipId = clips.firstOrNull()?.id
                rebuild(immediate = true)
            } catch (t: Throwable) {
                message = "Video konnte nicht geladen werden: ${t.message}"
            } finally {
                busy = null
            }
        }
    }

    fun removeClip(id: Long) {
        clips = clips.filterNot { it.id == id }.mapIndexed { i, c -> if (i == 0) c.copy(transition = TransitionType.CUT, transitionMs = 0) else c }
        if (selectedClipId == id) selectedClipId = clips.firstOrNull()?.id
        rebuild()
    }

    fun moveClip(id: Long, delta: Int) {
        val i = clips.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in clips.indices) return
        clips = clips.toMutableList().apply { add(j, removeAt(i)) }
        rebuild()
    }

    fun trimClip(id: Long, startMs: Long, endMs: Long) {
        clips = clips.map { if (it.id == id) it.copy(startMs = startMs, endMs = endMs) else it }
    }

    fun trimFinished() = rebuild()

    // ---------- Übergänge ----------
    fun setTransition(clipId: Long, type: TransitionType, ms: Long? = null) {
        clips = clips.map {
            if (it.id == clipId) it.copy(transition = type, transitionMs = ms ?: if (it.transition == type) it.transitionMs else type.defaultMs) else it
        }
        rebuild()
    }

    fun applyTransitionToAll(type: TransitionType, ms: Long) {
        clips = clips.mapIndexed { i, c -> if (i == 0) c else c.copy(transition = type, transitionMs = ms) }
        rebuild()
    }

    // ---------- Look ----------
    fun updateAdj(a: Adjustments) { adj = a; rebuild() }
    fun changeFormat(f: VideoFormat) { format = f; rebuild() }
    fun changeSpeed(s: Float) { speed = s; rebuild() }
    fun changeOriginalVolume(v: Float) { originalVolume = v; rebuild() }
    fun changeMusicVolume(v: Float) { musicVolume = v; rebuild() }
    fun changeMusicStart(ms: Long) { musicStartMs = ms; rebuild() }

    fun toggleBeatFx(f: BeatFx) { beatFx = if (f in beatFx) beatFx - f else beatFx + f; rebuild() }
    fun changeBeatIntensity(v: Float) { beatIntensity = v; rebuild() }
    fun changeStyle(s: StyleFx) { style = s; rebuild() }
    fun changeStyleIntensity(v: Float) { styleIntensity = v; rebuild() }

    // ---------- Musik ----------
    fun chooseMusic(uri: Uri?) {
        music = uri
        beatInfo = null
        musicStartMs = 0
        if (uri == null) {
            musicName = null
            rebuild()
            return
        }
        musicName = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Musik"
        busy = "Beats werden erkannt …"
        viewModelScope.launch {
            try {
                musicDurationMs = withContext(Dispatchers.IO) {
                    MediaMetadataRetriever().run {
                        try { setDataSource(ctx, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                        finally { release() }
                    }
                }
                beatInfo = withContext(Dispatchers.Default) { BeatDetector.detect(ctx, uri) }
                if (musicDurationMs <= 0) musicDurationMs = beatInfo?.durationMs ?: 0
                beatInfo?.let { message = "Tempo erkannt: ${it.bpm.toInt()} BPM (${it.beatsMs.size} Beats)" }
            } catch (t: Throwable) {
                message = "Beat-Erkennung nicht möglich: ${t.message}"
            } finally {
                busy = null
                rebuild(immediate = true)
            }
        }
    }

    /** Beats (ms) auf der Zeitachse des fertigen Videos, ab dem Musik-Startpunkt. */
    private fun outputBeatsMs(): List<Long> = request().outputBeatsUs().map { it / 1000 }

    /**
     * Schneidet alle Clips so, dass jeder Wechsel genau auf einem Beat liegt
     * (bei Übergängen liegt die Mitte des Übergangs auf dem Beat).
     */
    fun syncToBeats(everyN: Int) {
        val info = beatInfo
        if (music == null || info == null || info.beatsMs.isEmpty()) { message = "Bitte zuerst Musik wählen."; return }
        if (clips.isEmpty()) return
        // Beats genügend weit in die Zukunft (Musik wiederholt sich)
        val loopMs = musicDurationMs - musicStartMs
        val beats = ArrayList<Long>()
        var base = 0L
        while (beats.size < clips.size * everyN + 2 && loopMs > 0 && base < 3_600_000) {
            info.beatsMs.filter { it >= musicStartMs }.forEach { beats += base + it - musicStartMs }
            base += loopMs
        }
        if (beats.isEmpty()) return
        val cuts = beats.filterIndexed { i, _ -> i % everyN == 0 }.filter { it > 300 }

        val out = clips.toMutableList()
        var startMs = 0L // Startzeit des aktuellen Clips im Video
        for (k in out.indices) {
            val c = out[k]
            val next = out.getOrNull(k + 1)
            val target = cuts.getOrNull(k) ?: break
            val tNext = if (next == null || next.transition == TransitionType.CUT) 0L else next.transitionMs
            // Clip muss so lang sein, dass die Mitte des nächsten Übergangs auf dem Beat liegt
            val wantOutMs = max(400L, target + tNext / 2 - startMs)
            val wantSrc = (wantOutMs * speed).toLong()
            val end = min(c.durationMs, c.startMs + wantSrc)
            out[k] = c.copy(endMs = end)
            val actualOut = ((end - c.startMs) / speed).toLong()
            startMs += actualOut - tNext
        }
        clips = out
        val short = out.count { it.endMs == it.durationMs }
        message = "Clips auf den Beat geschnitten" + if (short > 0) " ($short Clip(s) waren zu kurz)" else ""
        rebuild(immediate = true)
    }

    // ---------- Text ----------
    fun addText(text: String) {
        val t = TextItem(text = text, y = 0.75f)
        texts = texts + t
        selectedTextId = t.id
    }

    fun updateText(id: Long, f: (TextItem) -> TextItem) { texts = texts.map { if (it.id == id) f(it) else it } }

    fun deleteText(id: Long) {
        texts = texts.filterNot { it.id == id }
        if (selectedTextId == id) selectedTextId = null
    }

    // ---------- Wiedergabe ----------
    fun request() = ExportRequest(
        clips, adj, texts, format, speed, originalVolume, music, musicVolume, musicStartMs, musicDurationMs,
        beatInfo?.beatsMs ?: LongArray(0), beatFx, beatIntensity, style, styleIntensity,
    )

    val outputAspect: Float get() {
        val (w, h) = VideoEffects.outputSize(clips, format)
        return w.toFloat() / h
    }

    val totalDurationMs: Long get() = if (clips.isEmpty()) 0 else TimelinePlan.build(clips, speed).totalUs / 1000

    /** Komposition neu aufbauen (verzögert, damit Schieberegler flüssig bleiben). */
    private fun rebuild(immediate: Boolean = false) {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            if (!immediate) delay(350)
            if (clips.isEmpty()) { player?.stop(); return@launch }
            val p = ensurePlayer()
            try {
                val wasPlaying = p.isPlaying
                val pos = min(p.currentPosition, max(0, totalDurationMs - 100))
                p.setComposition(VideoEffects.buildComposition(request(), forExport = false), pos)
                p.prepare()
                if (wasPlaying) p.play()
            } catch (t: Throwable) {
                message = "Vorschau-Fehler: ${t.message}"
            }
        }
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    fun seekTo(ms: Long) { player?.seekTo(ms); positionMs = ms }

    fun pause() { player?.pause() }

    // ---------- Export ----------
    fun export() {
        if (clips.isEmpty() || busy != null) return
        player?.pause()
        busy = "Video wird erstellt …"
        progress = 0f
        val e = VideoExporter(ctx)
        exporter = e
        try {
            e.export(request(), onProgress = { progress = it }) { result ->
                busy = null
                progress = null
                exporter = null
                message = result.fold(
                    { "Gespeichert in Galerie › Filme › MediaStudio" },
                    { "Export fehlgeschlagen: ${it.cause?.message ?: it.message}" },
                )
            }
        } catch (t: Throwable) {
            busy = null; progress = null; exporter = null
            message = "Export fehlgeschlagen: ${t.message}"
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
        pollJob?.cancel()
        player?.release()
    }
}
