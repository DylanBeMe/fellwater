package fi.tunturi.rows.workout

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import fi.tunturi.rows.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * The video side of a row, owned by the process rather than the screen: the player,
 * what is loaded, pace following, auto-pause and offline saves.
 *
 * It used to live inside the Row tab, so switching to History or rotating the tablet
 * released the player and cancelled any download in progress.
 */
class VideoPlayback private constructor(context: Context, private val session: RowSession) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val paces = app.getSharedPreferences(PACE_PREFS, Context.MODE_PRIVATE)
    private val store = VideoStore(app)

    val player: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = false
    }

    var hasMedia by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var resolving by mutableStateOf(false)
        private set
    var playError by mutableStateOf<String?>(null)
        private set
    var autoPaused by mutableStateOf(false)
        private set
    var appliedRate by mutableFloatStateOf(1f)
        private set

    /** The loaded video's own pace: what the footage was filmed at. Saved per video. */
    var referencePace by mutableFloatStateOf(DEFAULT_PACE)

    var followPace by mutableStateOf(prefs.getBoolean(KEY_FOLLOW_PACE, true))
        private set
    var pauseWhenIdle by mutableStateOf(prefs.getBoolean(KEY_PAUSE_WHEN_IDLE, true))
        private set

    /** The URL box, kept here so a link shared from another app lands in it. */
    var urlText by mutableStateOf("")

    /** Bumped when a link arrives from another app, so the UI can jump to the Row tab. */
    var shares by mutableStateOf(0)
        private set

    var saved by mutableStateOf(store.list())
        private set
    var saving by mutableStateOf(false)
        private set
    var saveProgress by mutableFloatStateOf(0f)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    private var currentKey: String? = null
    private var youTubeId: String? = null
    private var lastRefreshMs = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                this@VideoPlayback.playWhenReady = playWhenReady
            }

            override fun onPlayerError(error: PlaybackException) {
                // YouTube stream URLs expire after about six hours; a long pause ends in
                // a 403. Fetch fresh ones once and carry on from the same spot.
                val id = youTubeId
                val now = System.currentTimeMillis()
                if (id != null && error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                    now - lastRefreshMs > REFRESH_COOLDOWN_MS
                ) {
                    lastRefreshMs = now
                    loadYouTube(id, startMs = player.currentPosition)
                    return
                }
                playError = error.message ?: "playback failed"
            }
        })
        followRowing()
    }

    fun playFile(uri: Uri) {
        // Picked documents are only readable while the picking activity lives; the
        // player outlives it (rotation), so keep the grant.
        if (uri.scheme == "content") {
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        youTubeId = null
        load(uri.toString())
        hasMedia = true
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun playYouTube(id: String) {
        youTubeId = id
        load("yt:$id")
        loadYouTube(id, startMs = 0)
    }

    /** A pasted or shared link: YouTube if it looks like one, otherwise a direct stream. */
    fun playLink(input: String) {
        val text = input.trim()
        val id = youTubeIdFrom(text)
        if (id != null) playYouTube(id) else playFile(Uri.parse(text))
    }

    /** A link shared from another app (YouTube's Share button): fill the box and play it. */
    fun openShared(text: String) {
        val link = linkIn(text) ?: text.trim().takeIf { youTubeIdFrom(it) != null } ?: return
        urlText = link
        playLink(link)
        shares++
    }

    fun setPlaying(play: Boolean) {
        player.playWhenReady = play
    }

    fun updateFollowPace(on: Boolean) {
        followPace = on
        prefs.edit().putBoolean(KEY_FOLLOW_PACE, on).apply()
    }

    fun updatePauseWhenIdle(on: Boolean) {
        pauseWhenIdle = on
        prefs.edit().putBoolean(KEY_PAUSE_WHEN_IDLE, on).apply()
    }

    /** Call when the slider is released, not on every move. */
    fun saveReferencePace() {
        currentKey?.let { paces.edit().putFloat(it, referencePace).apply() }
    }

    fun saveOffline(input: String) {
        if (saving) return
        val text = input.trim()
        val id = youTubeIdFrom(text)
        saving = true
        saveError = null
        saveProgress = 0f
        DownloadService.start(app)
        DownloadService.progress(app, "Saving video", -1)
        scope.launch {
            var shown = -1
            val onProgress = { p: Float ->
                saveProgress = p
                val percent = (p * 100).toInt()
                if (percent != shown) {
                    shown = percent
                    DownloadService.progress(app, "Saving video", percent)
                }
            }
            runCatching {
                if (id != null) store.downloadYouTube(id, onProgress) else store.download(text, onProgress)
            }.onSuccess { file ->
                saved = store.list()
                // Carry the pace set while streaming over to the saved copy.
                val from = if (id != null) "yt:$id" else Uri.parse(text).toString()
                if (paces.contains(from)) {
                    paces.edit().putFloat(Uri.fromFile(file).toString(), paces.getFloat(from, DEFAULT_PACE)).apply()
                }
                playFile(Uri.fromFile(file))
            }.onFailure {
                saveError = it.message ?: "download failed"
            }
            saving = false
            DownloadService.stop(app)
        }
    }

    fun delete(file: File) {
        val key = Uri.fromFile(file).toString()
        // The player would otherwise fail on the next loop or seek of a missing file.
        if (key == currentKey) stop()
        store.delete(file)
        paces.edit().remove(key).apply()
        saved = store.list()
    }

    /** The activity is finishing for good: nothing should keep playing without a screen. */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        hasMedia = false
        autoPaused = false
    }

    private fun load(key: String) {
        playError = null
        currentKey = key
        referencePace = paces.getFloat(key, DEFAULT_PACE)
    }

    private fun loadYouTube(id: String, startMs: Long) {
        resolving = true
        scope.launch {
            runCatching { YouTube.mediaSource(YouTube.resolve(id)) }
                .onSuccess { source ->
                    if (youTubeId != id) return@onSuccess // another video was picked meanwhile
                    hasMedia = true
                    player.setMediaSource(source, startMs)
                    player.prepare()
                    if (startMs == 0L) player.playWhenReady = true
                }
                .onFailure { playError = "YouTube: ${it.message ?: "could not load video"}" }
            resolving = false
        }
    }

    /**
     * Speed follows the smoothed pace; 5 s without a stroke pauses, the next stroke
     * resumes. 5 s rather than 1-2 s because at 20 spm there is only one stroke every
     * 3 s. Pausing only applies while a rower is reporting, so a video can still be
     * previewed with nothing connected.
     */
    private fun followRowing() = scope.launch {
        while (true) {
            val reporting = session.latest?.strokeRateSpm != null
            val idle = reporting && System.currentTimeMillis() - session.lastActiveMs > IDLE_MS
            if (hasMedia && pauseWhenIdle && idle) {
                if (!autoPaused) {
                    player.playWhenReady = false
                    autoPaused = true
                }
            } else if (autoPaused) {
                player.playWhenReady = true
                autoPaused = false
            }

            val pace = session.smoothedPace
            val rate = if (!followPace || pace == null || pace <= 0.0) 1f else (referencePace / pace).toFloat()
            val clamped = rate.coerceIn(0.25f, 4f)
            if (abs(clamped - appliedRate) > 0.01f) {
                appliedRate = clamped
                player.setPlaybackSpeed(clamped)
            }
            delay(500)
        }
    }

    companion object {
        private const val PREFS = "video"
        private const val PACE_PREFS = "video_pace"
        private const val KEY_FOLLOW_PACE = "follow_pace"
        private const val KEY_PAUSE_WHEN_IDLE = "pause_when_idle"
        private const val DEFAULT_PACE = 120f
        private const val IDLE_MS = 5_000L
        private const val REFRESH_COOLDOWN_MS = 60_000L

        private var instance: VideoPlayback? = null

        fun get(context: Context, session: RowSession): VideoPlayback =
            instance ?: VideoPlayback(context, session).also { instance = it }
    }
}

/** Accepts watch?v=, youtu.be/, /embed/, /shorts/, /live/ or a bare 11-character id. */
fun youTubeIdFrom(input: String): String? {
    if (input.isBlank()) return null
    Regex("""(?:v=|/embed/|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""")
        .find(input)?.let { return it.groupValues[1] }
    if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(input)) return input
    return null
}

/** The first http(s) link in shared text ("Check this out https://youtu.be/…"). */
fun linkIn(text: String): String? = Regex("""https?://\S+""").find(text)?.value
