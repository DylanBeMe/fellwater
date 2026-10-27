package fi.tunturi.rows.ui

import android.content.SharedPreferences
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import fi.tunturi.rows.workout.VideoPlayback
import fi.tunturi.rows.workout.youTubeIdFrom
import java.io.File
import kotlin.math.roundToInt

/** Values drawn over the footage. */
data class OverlayMetrics(
    val spm: Double? = null,
    val paceSecPer500m: Double? = null,
    val distanceM: Int? = null,
    val elapsedSec: Int? = null,
    val watts: Int? = null,
    val heartRate: Int? = null,
)

/** Where everyone is on the course, for the race bar beside fullscreen footage. */
data class RaceStatus(
    val finishM: Double,
    val youM: Double?,
    val rivals: List<Pair<String, Double?>>,
)

/**
 * The footage is the screen.
 *
 * Three sources, all driven by the same pace/playback controls:
 *  - a local file (no network)
 *  - a direct stream (mp4 / HLS / DASH)
 *  - a YouTube URL, resolved to its streams and played by the same ExoPlayer, so
 *    pace following gets the full 0.25-4x range
 *
 * Only the controls live here; the player, pace following and downloads belong to
 * [VideoPlayback] so they survive tab switches and rotation.
 */
@Composable
fun VideoRide(
    playback: VideoPlayback,
    overlay: OverlayMetrics = OverlayMetrics(),
    race: RaceStatus? = null,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var raceBarOn by remember { mutableStateOf(prefs.getBoolean(KEY_RACE_BAR, true)) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    val hasMedia = playback.hasMedia

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playback.playFile(uri)
    }

    // One player, one surface at a time: whichever view is showing owns it, and gives
    // it back when it leaves so the other view does not stay black.
    @Composable
    fun Surface(modifier: Modifier) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = playback.player
                    useController = true
                }
            },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            HideSystemBars()
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                Surface(Modifier.fillMaxSize())
                Draggable(prefs, "stats", default = Offset(0f, 0f)) {
                    Row(
                        Modifier
                            .background(Color(0x99000000), MaterialTheme.shapes.small)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) { OverlayStats(overlay, large = true) }
                }
                TextButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) { Text("Exit", color = Color.White) }
                if (race != null && raceBarOn) {
                    val barHeight = maxHeight * 2 / 3
                    Draggable(prefs, "race", default = Offset(1f, 0.5f)) {
                        RaceBar(race, Modifier.height(barHeight))
                    }
                }
            }
        }
    }

    confirmDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete saved video?") },
            text = { Text("${displayName(file)} (${sizeLabel(file.length())}) will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    playback.delete(file)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    val mediaModifier = if (portrait) {
        Modifier.fillMaxWidth().height(230.dp)
    } else {
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!hasMedia) {
                Box(
                    modifier = mediaModifier
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { pickVideo.launch(arrayOf("video/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Choose a video", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Local file, stream URL or YouTube — or share a video from the YouTube app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Box(mediaModifier.clip(MaterialTheme.shapes.medium)) {
                    if (!fullscreen) Surface(Modifier.fillMaxSize())
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .background(Color(0x99000000), MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) { OverlayStats(overlay) }
                    TextButton(
                        onClick = { fullscreen = true },
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                    ) {
                        Text("Fullscreen", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) {
                        Text(if (hasMedia) "Change" else "Choose video")
                    }
                    OutlinedButton(
                        onClick = { playback.setPlaying(!playback.playWhenReady) },
                        enabled = hasMedia,
                    ) { Text(if (playback.playWhenReady) "Pause" else "Play") }
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Less" else "More")
                    }
                }

                val saved = playback.saved
                if (saved.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Saved offline · ${saved.size} · ${sizeLabel(saved.sumOf { it.length() })}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        saved.forEach { file ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        displayName(file),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        sizeLabel(file.length()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { playback.playFile(Uri.fromFile(file)) }) { Text("Play") }
                                TextButton(onClick = { confirmDelete = file }) { Text("Delete") }
                            }
                        }
                    }
                }

                if (playback.resolving) {
                    Text(
                        "Loading YouTube video…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                playback.playError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (playback.autoPaused) {
                    Text(
                        "Paused — row to resume",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (playback.saving) {
                    Text(
                        "Saving video… ${(playback.saveProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { playback.saveProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                playback.saveError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (showAdvanced) {
                    // Three separate groups, divided and spaced, so the panel does not
                    // read as one dense clump under the playback buttons.
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    )

                    SettingSwitch("Match speed to my pace", playback.followPace) { playback.updateFollowPace(it) }
                    SettingSwitch("Pause when I stop rowing", playback.pauseWhenIdle) { playback.updatePauseWhenIdle(it) }
                    SettingSwitch("Race bar in fullscreen", raceBarOn) {
                        raceBarOn = it
                        prefs.edit().putBoolean(KEY_RACE_BAR, it).apply()
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "Video pace ${paceLabel(playback.referencePace.toDouble())} /500m · " +
                                "playing at ${"%.2f".format(playback.appliedRate)}×" +
                                if (hasMedia) " · remembered for this video" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = playback.referencePace,
                            onValueChange = { playback.referencePace = it },
                            onValueChangeFinished = { playback.saveReferencePace() },
                            valueRange = 80f..200f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = playback.urlText,
                            onValueChange = { playback.urlText = it },
                            label = { Text("Stream or YouTube URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val urlInput = playback.urlText.trim()
                        val urlValid = urlInput.startsWith("http") || youTubeIdFrom(urlInput) != null
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { playback.playLink(urlInput) },
                                enabled = urlValid && !playback.resolving,
                            ) { Text("Play URL") }

                            OutlinedButton(
                                onClick = { playback.saveOffline(urlInput) },
                                enabled = urlValid && !playback.saving,
                            ) { Text(if (playback.saving) "Saving…" else "Save offline") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lets the user drag [content] anywhere inside the fullscreen view. The position is
 * stored as a fraction of the free space, so it survives rotation and can never be
 * dragged off screen.
 */
@Composable
private fun BoxWithConstraintsScope.Draggable(
    prefs: SharedPreferences,
    key: String,
    default: Offset,
    content: @Composable () -> Unit,
) {
    var at by remember {
        mutableStateOf(Offset(prefs.getFloat("${key}_x", default.x), prefs.getFloat("${key}_y", default.y)))
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val freeX = (constraints.maxWidth - size.width).coerceAtLeast(1)
    val freeY = (constraints.maxHeight - size.height).coerceAtLeast(1)
    Box(
        Modifier
            .offset { IntOffset((at.x * freeX).roundToInt(), (at.y * freeY).roundToInt()) }
            .onSizeChanged { size = it }
            .pointerInput(freeX, freeY) {
                detectDragGestures(
                    onDragEnd = {
                        prefs.edit().putFloat("${key}_x", at.x).putFloat("${key}_y", at.y).apply()
                    },
                ) { change, drag ->
                    change.consume()
                    at = Offset(
                        (at.x + drag.x / freeX).coerceIn(0f, 1f),
                        (at.y + drag.y / freeY).coerceIn(0f, 1f),
                    )
                }
            }
            .padding(16.dp),
    ) { content() }
}

/** "1733929512-Big_Buck_Bunny_60fps_4K.mp4" -> "Big Buck Bunny 60fps 4K". */
private fun displayName(file: File): String =
    file.name.substringAfter('-').substringBeforeLast('.').replace('_', ' ').trim().ifBlank { file.name }

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

private const val PREFS = "video"
private const val KEY_RACE_BAR = "race_bar"

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(
            "  $label",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Fullscreen is read from the seat, a couple of metres away, so it gets larger type. */
@Composable
private fun OverlayStats(overlay: OverlayMetrics, large: Boolean = false) {
    OverlayStat(overlay.spm?.let { "%.0f".format(it) } ?: "—", "spm", large, lead = true)
    OverlayStat(paceLabel(overlay.paceSecPer500m), "/500m", large)
    OverlayStat(overlay.distanceM?.let { "%.2f".format(it / 1000.0) } ?: "—", "km", large)
    OverlayStat(clockLabel(overlay.elapsedSec), "time", large)
    OverlayStat(overlay.watts?.toString() ?: "—", "W", large)
    overlay.heartRate?.let { OverlayStat(it.toString(), "bpm", large) }
}

@Composable
private fun OverlayStat(value: String, unit: String, large: Boolean, lead: Boolean = false) {
    val type = MaterialTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = when {
                large && lead -> type.displayMedium
                large -> type.headlineMedium
                lead -> type.headlineLarge
                else -> type.titleLarge
            },
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            unit,
            style = if (large) type.titleSmall else type.labelMedium,
            color = Color(0xFFCCCCCC),
        )
    }
}

/**
 * Hides the status and navigation bars while the fullscreen dialog is showing; a
 * swipe from the edge brings them back briefly. The dialog has its own window, so
 * closing it restores the bars without any cleanup.
 */
@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
}

/**
 * The race as a vertical course: start (0 m) at the bottom, finish at the top, a dot
 * per rival and a bigger one for you, each labelled to the left of the track.
 */
@Composable
private fun RaceBar(race: RaceStatus, modifier: Modifier) {
    val youColor = MaterialTheme.colorScheme.primary
    val rivalColors = race.rivals.indices.map { rivalColor(it) }
    Column(
        modifier
            .width(150.dp)
            .background(Color(0x99000000), MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(courseLabel(race.finishM), style = MaterialTheme.typography.titleSmall, color = Color.White)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp)) {
            val track = maxHeight
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 7.dp)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(Color(0x66FFFFFF), MaterialTheme.shapes.extraSmall),
            )
            @Composable
            fun Marker(label: String, distanceM: Double, color: Color, dot: Dp, bold: Boolean) {
                val fraction = (distanceM / race.finishM).coerceIn(0.0, 1.0).toFloat()
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = track * (1f - fraction) - dot / 2)
                        .padding(end = 10.dp - dot / 2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        label,
                        style = if (bold) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Box(Modifier.size(dot).background(color, CircleShape).border(2.dp, Color.White, CircleShape))
                }
            }
            race.rivals.forEachIndexed { i, (name, distance) ->
                if (distance != null) Marker(name, distance, rivalColors[i], 14.dp, bold = false)
            }
            // Drawn last so you stay on top when someone is level with you.
            race.youM?.let { Marker("You", it, youColor, 20.dp, bold = true) }
        }
        Text("0 m", style = MaterialTheme.typography.titleSmall, color = Color.White)
    }
}

private fun courseLabel(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000.0)
