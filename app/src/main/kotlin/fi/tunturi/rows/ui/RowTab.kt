package fi.tunturi.rows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import fi.tunturi.rows.ble.RowerClient
import fi.tunturi.rows.workout.RowSession
import fi.tunturi.rows.workout.VideoPlayback
import fi.tunturi.rows.ble.RowerState
import fi.tunturi.rows.ftms.RowerSample

/**
 * The rowing screen: the video is the app. Everything else is a strip above and
 * below it, so the footage stays the biggest thing on screen.
 */
@Composable
fun RowTab(
    client: RowerClient,
    state: RowerState,
    latest: RowerSample?,
    coachOn: Boolean,
    onCoachChange: (Boolean) -> Unit,
    recordedCount: Int,
    onFinish: (goalM: Int?, difficulty: Int?) -> Unit,
    onDiscard: () -> Unit,
    lastSavedName: String?,
    sessions: List<fi.tunturi.rows.workout.SessionSummary>,
    csvFor: (String) -> String?,
    session: RowSession,
    playback: VideoPlayback,
    onKeepRecovered: () -> Unit,
) {
    // Goal and damper belong to the row, so they live on the session and survive
    // tab switches and rotation; the rest is screen state.
    var goalM by session::goalM
    var difficulty by session::difficulty
    var racersOn by rememberSaveable { mutableStateOf(true) }
    var pacerPace by rememberSaveable { mutableFloatStateOf(150f) }
    val racers = remember(sessions, pacerPace, goalM) {
        fi.tunturi.rows.workout.RacerRoster.build(sessions, csvFor, pacerPace.toDouble(), goalM)
    }
    val race = if (racersOn) raceStatus(racers, latest, goalM) else null

    val overlay = OverlayMetrics(
        spm = latest?.strokeRateSpm,
        paceSecPer500m = latest?.instantPaceSecPer500m,
        distanceM = latest?.totalDistanceM,
        elapsedSec = latest?.elapsedSec,
        watts = latest?.instantPowerW,
        heartRate = latest?.heartRateBpm,
    )

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (landscape) {
        // Landscape: video takes the left pane, numbers the right, no scrolling.
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusStrip(state, client)
            session.recovered?.let { RecoveredRowCard(it, onKeepRecovered) { session.discardRecovered() } }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Scrollable: expanding the video controls must not squeeze the card
                // into overlapping itself in a fixed-height pane.
                Box(
                    Modifier
                        .weight(1.6f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    VideoRide(
                        playback = playback,
                        overlay = overlay,
                        race = race,
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VerticalMetrics(latest)
                    RacersCard(racers, race, latest, { racersOn = it }, pacerPace, { pacerPace = it }, compact = true)
                    GoalCard(
                        distanceM = latest?.totalDistanceM,
                        goalM = goalM,
                        onGoalChange = { goalM = it },
                        difficulty = difficulty,
                        onDifficultyChange = { difficulty = it },
                        compact = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onFinish(goalM, difficulty) },
                            enabled = recordedCount > 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Finish")
                        }
                        OutlinedButton(onClick = onDiscard, enabled = recordedCount > 0) { Text("Discard") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = coachOn, onCheckedChange = onCoachChange)
                        Text("  Coach", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusStrip(state, client)
        session.recovered?.let { RecoveredRowCard(it, onKeepRecovered) { session.discardRecovered() } }

        // The video, full width, as large as the screen allows.
        Box(Modifier.fillMaxWidth()) {
            VideoRide(
                playback = playback,
                overlay = overlay,
                race = race,
            )
        }

        MetricStrip(latest)

        RacersCard(racers, race, latest, { racersOn = it }, pacerPace, { pacerPace = it })

        GoalCard(
            distanceM = latest?.totalDistanceM,
            goalM = goalM,
            onGoalChange = { goalM = it },
            difficulty = difficulty,
            onDifficultyChange = { difficulty = it },
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onFinish(goalM, difficulty) },
                enabled = recordedCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text("Finish row")
            }
            OutlinedButton(onClick = onDiscard, enabled = recordedCount > 0) { Text("Discard") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = coachOn, onCheckedChange = onCoachChange)
            Text("Spoken pace coach", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f))
            Text(
                "$recordedCount samples",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        lastSavedName?.let {
            Text(
                "Saved $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun StatusStrip(state: RowerState, client: RowerClient) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (text, color) = when (state) {
                RowerState.Idle -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
                RowerState.Scanning -> "Scanning…" to MaterialTheme.colorScheme.primary
                is RowerState.Connecting -> "Connecting to ${state.name}…" to MaterialTheme.colorScheme.primary
                is RowerState.Ready -> "Connected: ${state.name}" to MaterialTheme.colorScheme.secondary
                is RowerState.Failed -> state.reason to MaterialTheme.colorScheme.error
                is RowerState.Reconnecting ->
                    "Connection lost — reconnecting to ${state.name} (try ${state.attempt})…" to MaterialTheme.colorScheme.primary
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.weight(1f),
            )
            if (state is RowerState.Ready || state is RowerState.Reconnecting) {
                OutlinedButton(onClick = { client.disconnect() }) { Text("Disconnect") }
            } else {
                Button(onClick = { client.startScan() }) { Text("Connect") }
            }
        }
    }
}

/** A row the last run never finished (crash or killed app), found in the autosave. */
@Composable
private fun RecoveredRowCard(
    summary: fi.tunturi.rows.workout.SessionSummary,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unfinished row found", style = MaterialTheme.typography.titleSmall)
            Text(
                "${summary.dateLabel} · ${summary.label}. The app closed before it was finished.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeep) { Text("Save to history") }
                OutlinedButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

/** Five big numbers: the things you actually glance at mid-stroke. */
@Composable
private fun MetricStrip(latest: RowerSample?) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BigMetric(latest?.strokeRateSpm?.let { "%.0f".format(it) } ?: "—", "spm")
            BigMetric(paceLabel(latest?.instantPaceSecPer500m), "/500m")
            BigMetric(latest?.totalDistanceM?.let { "%.2f".format(it / 1000.0) } ?: "—", "km")
            BigMetric(clockLabel(latest?.elapsedSec), "time")
            BigMetric(latest?.instantPowerW?.toString() ?: "—", "watts")
            latest?.heartRateBpm?.let { BigMetric(it.toString(), "bpm") }
        }
    }
}

@Composable
private fun BigMetric(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun paceLabel(secPer500: Double?): String {
    if (secPer500 == null || secPer500 <= 0) return "—"
    val total = secPer500.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun clockLabel(seconds: Int?): String {
    if (seconds == null) return "—"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun VerticalMetrics(latest: RowerSample?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VerticalMetric(latest?.strokeRateSpm?.let { "%.0f".format(it) } ?: "—", "spm")
            VerticalMetric(paceLabel(latest?.instantPaceSecPer500m), "/500m")
            VerticalMetric(latest?.totalDistanceM?.let { "%.2f".format(it / 1000.0) } ?: "—", "km")
            VerticalMetric(clockLabel(latest?.elapsedSec), "time")
            VerticalMetric(latest?.instantPowerW?.toString() ?: "—", "watts")
            latest?.heartRateBpm?.let { VerticalMetric(it.toString(), "bpm") }
        }
    }
}

@Composable
private fun VerticalMetric(value: String, unit: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            unit,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Target distance and damper setting.
 *
 * The damper is recorded, not read: this console reports no resistance field and
 * accepts no target commands, so the only truthful source is what you set on the
 * machine.
 */
@Composable
private fun GoalCard(
    distanceM: Int?,
    goalM: Int?,
    onGoalChange: (Int?) -> Unit,
    difficulty: Int,
    onDifficultyChange: (Int) -> Unit,
    compact: Boolean = false,
) {
    var custom by remember { mutableStateOf("") }
    val presets = listOf(null, 500, 2_000, 5_000, 10_000)
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Goal", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        goalM == null -> "open row"
                        distanceM == null -> "${goalM / 1000.0} km".let { "%.1f km".format(goalM / 1000.0) }
                        else -> "${distanceM.coerceAtMost(goalM)} / ${goalM} m"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    val label = when (preset) {
                        null -> "None"
                        500 -> "500 m"
                        else -> "${preset / 1000} km"
                    }
                    if (preset == goalM) {
                        Button(onClick = { onGoalChange(preset) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onGoalChange(preset) }) { Text(label) }
                    }
                }
            }

            // Any distance, not just the presets. Hidden in the compact landscape
            // card, where vertical space is the constraint.
            if (!compact) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { text ->
                        custom = text.filter { it.isDigit() }.take(6)
                        custom.toIntOrNull()?.takeIf { it in 50..100_000 }?.let(onGoalChange)
                    },
                    label = { Text("Custom distance (m)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (goalM != null && distanceM != null) {
                val progress = (distanceM.toFloat() / goalM).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                val remaining = goalM - distanceM
                Text(
                    if (remaining > 0) "$remaining m to go" else "Goal reached",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (remaining > 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Damper", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$difficulty / 10",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = difficulty.toFloat(),
                onValueChange = { onDifficultyChange(it.toInt().coerceIn(1, 10)) },
                valueRange = 1f..10f,
                steps = 8,
            )
            if (!compact) {
                Text(
                    "Recorded with the row, not read from the machine: this console " +
                        "reports no resistance and accepts no settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Where everyone is right now. The course runs to the chosen goal; an open row
 * gets a course just longer than whoever is furthest along.
 */
private fun raceStatus(
    racers: List<fi.tunturi.rows.workout.Racer>,
    latest: RowerSample?,
    goalM: Int?,
): RaceStatus {
    val you = latest?.totalDistanceM?.toDouble()
    val rivals = racers.map { racer ->
        racer.shortName to latest?.elapsedSec?.let { racer.ghost.distanceAt(it) }
    }
    val furthest = rivals.mapNotNull { it.second }.maxOrNull() ?: 0.0
    val finish = goalM?.toDouble() ?: (maxOf(you ?: 0.0, furthest, 500.0) * 1.05)
    return RaceStatus(finishM = finish, youM = you, rivals = rivals)
}

/** Rival dot colours, shared by the route bar and the fullscreen race bar. */
@Composable
internal fun rivalColor(index: Int): androidx.compose.ui.graphics.Color {
    val colors = listOf(
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
    )
    return colors[index % colors.size]
}

/**
 * Racers as a single toggle: a route bar showing where everyone is right now,
 * rather than a separate page. Rivals are your saved rows plus pacers.
 */
@Composable
private fun RacersCard(
    racers: List<fi.tunturi.rows.workout.Racer>,
    race: RaceStatus?,
    latest: RowerSample?,
    onToggle: (Boolean) -> Unit,
    basePace: Float,
    onBasePaceChange: (Float) -> Unit,
    compact: Boolean = false,
) {
    val you = latest?.totalDistanceM
    val elapsed = latest?.elapsedSec

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Racers", style = MaterialTheme.typography.titleSmall)
                Switch(checked = race != null, onCheckedChange = onToggle)
            }

            if (race != null) {
                RouteBar(budgetM = race.finishM, youM = race.youM, rivals = race.rivals)

                racers.forEach { racer ->
                    val delta = if (you != null && elapsed != null) {
                        racer.ghost.deltaSec(you, elapsed)
                    } else null
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(racer.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                delta == null -> "—"
                                kotlin.math.abs(delta) < 0.5 -> "level"
                                delta < 0 -> "${(-delta).toInt()} s ahead"
                                else -> "${delta.toInt()} s behind"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!compact) {
                    Text(
                        "Pacer pace " + paceLabel(basePace.toDouble()) + " /500m",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = basePace,
                        onValueChange = onBasePaceChange,
                        valueRange = 90f..210f,
                    )
                }
            }
        }
    }
}

/** Track with a dot per rival and a bigger dot for you. */
@Composable
private fun RouteBar(
    budgetM: Double,
    youM: Double?,
    rivals: List<Pair<String, Double?>>,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val youColor = MaterialTheme.colorScheme.primary
    val rivalColors = rivals.indices.map { rivalColor(it) }
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(vertical = 6.dp)) {
        val y = size.height / 2f
        drawLine(
            color = trackColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height * 0.55f,
            cap = StrokeCap.Round,
        )
        fun x(distance: Double): Float =
            ((distance / budgetM).coerceIn(0.0, 1.0) * size.width).toFloat()

        rivals.forEachIndexed { index, (_, distance) ->
            if (distance == null) return@forEachIndexed
            drawCircle(
                color = rivalColors[index],
                radius = size.height * 0.28f,
                center = Offset(x(distance), y),
            )
        }
        youM?.let {
            drawCircle(
                color = youColor,
                radius = size.height * 0.42f,
                center = Offset(x(it), y),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("0", style = MaterialTheme.typography.labelSmall)
        Text(
            "%.2f km".format(budgetM / 1000.0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
