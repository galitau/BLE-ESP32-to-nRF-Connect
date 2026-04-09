package com.example.electrium

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Listens for Google Maps navigation notifications and forwards combined
 * direction + distance text to [MapsBridgeService] for BLE delivery.
 */
class MapNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != GOOGLE_MAPS_PACKAGE) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        val body = when {
            text.isNotEmpty() -> text
            bigText.isNotEmpty() -> bigText
            else -> ""
        }

        val combined = when {
            title.isNotEmpty() && body.isNotEmpty() -> "$title · $body"
            title.isNotEmpty() -> title
            body.isNotEmpty() -> body
            else -> return
        }

        Log.i(TAG, "Maps nav: $combined")

        val intent = MapsBridgeService.navUpdateIntent(this, combined)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not forward to bridge (start the bridge service first)", e)
        }
    }

    companion object {
        private const val TAG = "MapNotificationListener"
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
