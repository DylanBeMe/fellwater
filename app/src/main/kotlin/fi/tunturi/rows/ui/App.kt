package fi.tunturi.rows.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fi.tunturi.rows.RowService
import fi.tunturi.rows.ble.RowerClient
import fi.tunturi.rows.ble.RowerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import fi.tunturi.rows.workout.RowSession
import fi.tunturi.rows.workout.SessionStore
import fi.tunturi.rows.workout.VideoPlayback

private const val PREFS = "ui"
private const val KEY_THEME = "theme_mode"

@Composable
fun App(client: RowerClient) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var themeMode by remember {
        mutableStateOf(
            when (prefs.getInt(KEY_THEME, 0)) {
                1 -> ThemeMode.DARK
                2 -> ThemeMode.LIGHT
                else -> ThemeMode.SYSTEM
            }
        )
    }
    TunturiTheme(themeMode) {
        val state by client.state.collectAsState()
        val devices by client.devices.collectAsState()
        val inventory by client.inventory.collectAsState()
        val rawLog by client.rawLog.collectAsState()

        // The row and the video are owned by the process, not this screen, so a
        // rotation or a tab switch cannot lose the samples or stop the footage.
        val session = remember { RowSession.get(client, context) }
        val playback = remember { VideoPlayback.get(context, session) }
        val latest = session.latest
        val store = remember { SessionStore(context) }
        var sessions by remember { mutableStateOf(store.list()) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var lastSaved by rememberSaveable { mutableStateOf<String?>(null) }
        val tabState = rememberSaveableStateHolder()

        // A video shared from another app always lands on the Row tab.
        LaunchedEffect(playback.shares) { if (playback.shares > 0) tab = 0 }

        // Keep the screen on and hold a foreground service while connected, so the
        // session survives the screen turning off or the app being backgrounded.
        val activity = context as? android.app.Activity
        // Reconnecting counts as connected: a blip mid-row must not let the screen
        // sleep or drop the service while the link is being restored.
        val live = state is RowerState.Ready || state is RowerState.Reconnecting
        LaunchedEffect(live) {
            val connected = live
            activity?.window?.let { window ->
                if (connected) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            if (connected) RowService.start(context) else RowService.stop(context)
        }

        // Spoken pace coach: calls out pace (+ stroke rate) every 15 s while rowing.
        var coachOn by rememberSaveable { mutableStateOf(false) }
        val tts = remember { android.speech.tts.TextToSpeech(context) { } }
        DisposableEffect(Unit) { onDispose { runCatching { tts.stop(); tts.shutdown() } } }
        LaunchedEffect(coachOn) {
            while (coachOn) {
                val pace = latest?.instantPaceSecPer500m
                if (pace != null && pace > 0) {
                    val minutes = (pace / 60).toInt()
                    val seconds = (pace % 60).toInt()
                    val spm = latest?.strokeRateSpm?.toInt()
                    val message = buildString {
                        append("$minutes minutes $seconds seconds per 500 metres")
                        if (spm != null && spm > 0) append(", $spm strokes per minute")
                    }
                    tts.speak(message, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "pace-coach")
                }
                kotlinx.coroutines.delay(15_000)
            }
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        label = { Text("Row") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1; sessions = store.list() },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("History") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Setup") },
                    )
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                tabState.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> RowTab(
                            client = client,
                            state = state,
                            latest = latest,
                            coachOn = coachOn,
                            onCoachChange = { coachOn = it },
                            recordedCount = session.recordedCount,
                            onFinish = { goalM, difficulty ->
                                val file = store.save(session.csv(), goalM, difficulty)
                                if (file != null) {
                                    sessions = store.list()
                                    session.clear()
                                    lastSaved = file.name
                                }
                            },
                            onDiscard = {
                                session.clear()
                                lastSaved = null
                            },
                            lastSavedName = lastSaved,
                            sessions = sessions,
                            csvFor = { id -> store.csv(id) },
                            session = session,
                            playback = playback,
                            onKeepRecovered = {
                                session.keepRecovered()
                                sessions = store.list()
                            },
                        )
                        1 -> HistoryTab(
                            sessions = sessions,
                            store = store,
                            onChanged = { sessions = store.list() },
                        )
                        2 -> SetupTab(
                            client = client,
                            state = state,
                            devices = devices,
                            inventory = inventory,
                            rawLog = rawLog,
                            themeMode = themeMode,
                            onThemeChange = {
                                themeMode = it
                                prefs.edit()
                                    .putInt(
                                        KEY_THEME,
                                        when (it) {
                                            ThemeMode.DARK -> 1
                                            ThemeMode.LIGHT -> 2
                                            ThemeMode.SYSTEM -> 0
                                        },
                                    )
                                    .apply()
                            },
                            sessionsDir = store.directory().absolutePath,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ScrollColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}
