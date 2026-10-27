package fi.tunturi.rows

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Keeps the process (and therefore the BLE connection) alive while the app is
 * backgrounded or the screen is off. Without this, Android can freeze or kill the
 * app mid-session and the row is silently lost.
 *
 * The notification is deliberately low-importance: it is a "session is running"
 * indicator, not something that should interrupt.
 */
class RowService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rowing session", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(NOTIFICATION_ID, buildNotification("Connected to rower"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TEXT)?.let { text ->
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Fellwater")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "rowing_session"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String = "Connected to rower") {
            val intent = Intent(context, RowService::class.java).putExtra(EXTRA_TEXT, text)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RowService::class.java))
        }
    }
}
