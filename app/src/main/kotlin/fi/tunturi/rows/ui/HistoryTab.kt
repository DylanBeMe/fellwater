package fi.tunturi.rows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import fi.tunturi.rows.workout.Export
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.tunturi.rows.workout.SessionStore
import fi.tunturi.rows.workout.SessionSummary

/** Finished rows: totals, 100 m splits, export and delete. */
@Composable
fun HistoryTab(
    sessions: List<SessionSummary>,
    store: SessionStore,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var openId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }

    fun export(session: SessionSummary, tcx: Boolean) {
        val csv = store.csv(session.id) ?: return
        val (name, mime, body) = if (tcx) {
            Triple("${session.id}.tcx", "application/vnd.garmin.tcx+xml", Export.tcx(session.startedAt, csv))
        } else {
            Triple("${session.id}.csv", "text/csv", csv)
        }
        exported = if (Export.toDownloads(context, name, mime, body.toByteArray()) != null) {
            "Saved $name to ${Export.FOLDER}"
        } else {
            "Could not save $name"
        }
    }

    // Saved rows are also your racers, so a mis-tap here should not cost one.
    confirmDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this row?") },
            text = { Text("${session.dateLabel} · ${session.label} will be removed, and can no longer be raced.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(session.id)
                    confirmDelete = null
                    openId = null
                    onChanged()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    ScrollColumn {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Saved in ${store.directory().absolutePath}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (sessions.isEmpty()) {
            Section("No rows yet") {
                Text(
                    "Row with the app connected, then tap Finish row. Saved rows become " +
                        "racers, so your history is what you race against later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        sessions.forEach { session ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                session.dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                openId = if (openId == session.id) null else session.id
                            }
                        ) { Text(if (openId == session.id) "Hide" else "Details") }
                    }

                    if (openId == session.id) {
                        HorizontalDivider()
                        InfoRow("Average stroke rate", "%.1f spm".format(session.avgSpm))
                        InfoRow("Max stroke rate", "%.0f spm".format(session.maxSpm))
                        InfoRow("Average power", "${session.avgWatts} W")
                        InfoRow("Strokes", session.strokes.toString())
                        if (session.splits.isNotEmpty()) {
                            Text("Splits", style = MaterialTheme.typography.labelLarge)
                            session.splits.forEachIndexed { index, split ->
                                val prev = if (index == 0) 0 else session.splits[index - 1].elapsedSec
                                val splitTime = split.elapsedSec - prev
                                val pace = if (splitTime > 0) splitTime * 5.0 else null // 100 m -> 500 m
                                InfoRow(
                                    "${split.distanceM} m",
                                    clockLabel(splitTime) + (pace?.let { " · " + paceLabel(it) } ?: ""),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { export(session, tcx = false) }) { Text("Export CSV") }
                            OutlinedButton(onClick = { export(session, tcx = true) }) { Text("Export TCX (Strava)") }
                        }
                        exported?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { confirmDelete = session }) {
                            Text("Delete this row", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
