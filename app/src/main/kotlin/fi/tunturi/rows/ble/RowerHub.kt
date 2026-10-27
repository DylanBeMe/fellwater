package fi.tunturi.rows.ble

import android.content.Context

/**
 * One Bluetooth client for the whole process.
 *
 * The UI and the foreground service must share a single connection: two clients
 * would fight over the GATT link, and the service has to keep the session alive
 * while the screen is off.
 */
object RowerHub {
    private var instance: RowerClient? = null

    fun get(context: Context): RowerClient =
        instance ?: RowerClient(context.applicationContext).also { instance = it }
}
