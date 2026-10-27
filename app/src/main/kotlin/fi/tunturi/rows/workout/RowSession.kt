package fi.tunturi.rows.workout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import fi.tunturi.rows.ble.HeartRateClient
import fi.tunturi.rows.ble.RowerClient
import fi.tunturi.rows.ftms.RowerSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The row in progress, owned by the process rather than the screen.
 *
 * It used to live in the UI, so rotating the tablet (which rebuilds the activity)
 * threw away every sample recorded so far. Like [fi.tunturi.rows.ble.RowerHub], one
 * instance per process; the foreground service keeps the process alive mid-row.
 */
class RowSession private constructor(private val client: RowerClient, context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // One lane for draft files, so a write queued before a delete never lands after it.
    private val drafts = Dispatchers.IO.limitedParallelism(1)
    private val store = SessionStore(context.applicationContext)
    private val recorder = WorkoutRecorder()
    private val smoother = PaceSmoother()
    private val strap = HeartRateClient.get(context)

    /** A row the last run never finished (crash, or the app was killed), offered back once. */
    var recovered by mutableStateOf(store.takeLeftoverDraft())
        private set

    var latest by mutableStateOf<RowerSample?>(null)
        private set
    var recordedCount by mutableIntStateOf(0)
        private set

    /** Pace averaged over a few seconds, for driving the video speed. */
    var smoothedPace by mutableStateOf<Double?>(null)
        private set

    /** When the machine last reported real rowing (stroke rate above 4 spm). */
    var lastActiveMs by mutableLongStateOf(0L)
        private set

    var goalM by mutableStateOf<Int?>(null)
    var difficulty by mutableIntStateOf(5)

    init {
        scope.launch {
            client.samples.collect { raw ->
                val now = System.currentTimeMillis()
                // Most consoles send no heart rate; a paired strap fills it in.
                val sample = if (raw.heartRateBpm == null) raw.copy(heartRateBpm = strap.freshBpm()) else raw
                latest = sample
                recorder.record(sample)
                recordedCount = recorder.size
                smoothedPace = smoother.add(sample.instantPaceSecPer500m, now)
                if ((sample.strokeRateSpm ?: 0.0) > 4.0) lastActiveMs = now
            }
        }
        autosave()
    }

    /** Writes the row in progress every 30 s, so a crash loses at most that much. */
    private fun autosave() = scope.launch {
        var written = 0
        while (true) {
            delay(AUTOSAVE_MS)
            val count = recordedCount
            if (count > 0 && count != written) {
                val csv = recorder.csv()
                val goal = goalM
                val damper = difficulty
                launch(drafts) { store.writeDraft(csv, goal, damper) }
                written = count
            }
        }
    }

    /** Saves the recovered row to History. */
    fun keepRecovered() {
        store.keepRecovered()
        recovered = null
    }

    fun discardRecovered() {
        store.discardRecovered()
        recovered = null
    }

    fun csv(): String = recorder.csv()

    /** The row was finished (and saved) or discarded: start the next one from zero. */
    fun clear() {
        recorder.clear()
        smoother.clear()
        recordedCount = 0
        client.resetSession()
        scope.launch(drafts) { store.clearDraft() }
    }

    companion object {
        private const val AUTOSAVE_MS = 30_000L
        private var instance: RowSession? = null

        fun get(client: RowerClient, context: Context): RowSession =
            instance ?: RowSession(client, context).also { instance = it }
    }
}
