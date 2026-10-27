package fi.tunturi.rows

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import fi.tunturi.rows.ble.HeartRateClient
import fi.tunturi.rows.ble.RowerHub
import fi.tunturi.rows.ui.App
import fi.tunturi.rows.workout.RowSession
import fi.tunturi.rows.workout.VideoPlayback

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = RowerHub.get(applicationContext)

        val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        if (!client.hasPermission) {
            requestPermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            )
        }

        // Reconnect to the rower we used last time, without asking to scan again.
        if (client.hasPermission) {
            client.connectLast()
            HeartRateClient.get(applicationContext).connectLast()
        }

        // Only on a fresh start: after a rotation the shared link has already played.
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            App(client)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The player outlives the activity across rotations; leaving the app for good
        // must not leave a video playing with no screen.
        if (isFinishing) playback().stop()
    }

    /** "Share" from the YouTube app (or a browser) arrives as plain text with a link. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        playback().openShared(text)
    }

    private fun playback(): VideoPlayback =
        VideoPlayback.get(this, RowSession.get(RowerHub.get(applicationContext), this))
}
