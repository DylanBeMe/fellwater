package fi.tunturi.rows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fi.tunturi.rows.BuildConfig
import fi.tunturi.rows.ble.FakeRower
import fi.tunturi.rows.ble.RowerClient
import fi.tunturi.rows.ble.RowerState
import fi.tunturi.rows.ble.HeartRateClient
import fi.tunturi.rows.ble.StrapState
import androidx.compose.runtime.collectAsState

/** Connection, appearance, storage and (debug builds only) protocol tools. */
@Composable
fun SetupTab(
    client: RowerClient,
    state: RowerState,
    devices: List<fi.tunturi.rows.ble.ScanHit>,
    inventory: String?,
    rawLog: String,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    sessionsDir: String,
) {
    val context = LocalContext.current
    val simulator = remember { FakeRower(context) }
    var simOn by remember { mutableStateOf(false) }

    ScrollColumn {
        Text("Setup", style = MaterialTheme.typography.headlineSmall)

        Section("Rower") {
            Text(
                when (state) {
                    RowerState.Idle -> "Not connected"
                    RowerState.Scanning -> "Scanning…"
                    is RowerState.Connecting -> "Connecting to ${state.name}…"
                    is RowerState.Ready -> "Connected: ${state.name}"
                    is RowerState.Failed -> state.reason
                    is RowerState.Reconnecting -> "Reconnecting to ${state.name} (try ${state.attempt})…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is RowerState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (state is RowerState.Scanning) client.stopScan() else client.startScan()
                    }
                ) { Text(if (state is RowerState.Scanning) "Stop scan" else "Scan") }
                OutlinedButton(
                    onClick = { client.disconnect() },
                    enabled = state is RowerState.Ready || state is RowerState.Reconnecting,
                ) { Text("Disconnect") }
            }
            devices.forEach { hit ->
                OutlinedButton(
                    onClick = { client.connect(hit) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Text("${hit.name}  (${hit.rssi} dBm)")
                        Text(
                            hit.servicesLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HeartRateSection(remember { HeartRateClient.get(context) }, client.hasPermission)

        Section("Appearance") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> "System"
                        ThemeMode.DARK -> "Dark"
                        ThemeMode.LIGHT -> "Light"
                    }
                    if (mode == themeMode) {
                        Button(onClick = { onThemeChange(mode) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onThemeChange(mode) }) { Text(label) }
                    }
                }
            }
        }

        Section("Storage") {
            Text(sessionsDir, style = MaterialTheme.typography.labelSmall)
            Text(
                "Row files are plain CSV in this folder, so they can be copied off the device " +
                    "or picked up by a backup.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Debug builds only: the simulator advertises a fake FTMS rower and the
        // inspector dumps the GATT table. Neither belongs in a release build.
        if (BuildConfig.DEBUG) {
            Section("Developer tools (debug)") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = simOn,
                        onCheckedChange = { on ->
                            simOn = on
                            if (on) {
                                simulator.start()
                                simulator.selfHit()?.let { client.connect(it) }
                            } else {
                                simulator.stop()
                            }
                        },
                    )
                    Text("Simulated rower", style = MaterialTheme.typography.bodySmall)
                }
                inventory?.let {
                    Text("GATT table", style = MaterialTheme.typography.labelLarge)
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                if (rawLog.isNotBlank()) {
                    Text("Frames", style = MaterialTheme.typography.labelLarge)
                    Text(
                        rawLog.lines().takeLast(30).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** A standard Bluetooth chest strap, for consoles that send no heart rate. */
@Composable
private fun HeartRateSection(strap: HeartRateClient, permitted: Boolean) {
    val state by strap.state.collectAsState()
    val found by strap.found.collectAsState()
    val bpm by strap.bpm.collectAsState()
    Section("Heart-rate strap") {
        Text(
            when (val s = state) {
                StrapState.Idle -> if (strap.hasSaved) "Not connected" else "None paired"
                StrapState.Scanning -> "Looking for straps… (wear it, so it wakes up)"
                is StrapState.Connecting -> "Waiting for ${s.name}…"
                is StrapState.Connected -> "${s.name}: ${bpm?.let { "$it bpm" } ?: "no reading yet"}"
                is StrapState.Failed -> s.reason
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { if (state is StrapState.Scanning) strap.stopScan() else strap.scan() },
                enabled = permitted,
            ) { Text(if (state is StrapState.Scanning) "Stop" else "Find strap") }
            if (strap.hasSaved) {
                OutlinedButton(onClick = { strap.forget() }) { Text("Forget strap") }
            }
        }
        found.forEach { hit ->
            OutlinedButton(onClick = { strap.connect(hit) }, modifier = Modifier.fillMaxWidth()) {
                Text(hit.name)
            }
        }
        Text(
            "Used only when the rower itself sends no heart rate. Recorded with the row and " +
                "included in TCX exports.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
