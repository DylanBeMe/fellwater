package fi.tunturi.rows

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the process alive while a video is being saved offline. A YouTube save can
 * be 1-2 GB and take many minutes; without a foreground service Android may kill
 * the app as soon as it is backgrounded, losing the download.
 *
 * The download itself runs in [fi.tunturi.rows.workout.VideoPlayback]; this only
 * holds the process and shows progress.
 */
class DownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, build(this, "Saving video", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "video_download"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }

        /** Updates the running notification in place; [percent] < 0 shows an indeterminate bar. */
        fun progress(context: Context, title: String, percent: Int) {
            ensureChannel(context)
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build(context, title, percent))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Video downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun build(context: Context, title: String, percent: Int): Notification =
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(if (percent >= 0) "$percent%" else "Starting…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
    }
}
