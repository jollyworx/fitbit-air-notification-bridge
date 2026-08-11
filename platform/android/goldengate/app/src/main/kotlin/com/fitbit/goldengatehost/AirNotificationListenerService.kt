package com.fitbit.goldengatehost

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber

class AirNotificationListenerService : NotificationListenerService() {
    private val recentNotifications = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.i("Air notification listener connected")
    }

    override fun onListenerDisconnected() {
        Timber.w("Air notification listener disconnected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, AirNotificationListenerService::class.java))
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val rule = AirBridgeNotificationSettings.findRule(this, sbn.packageName) ?: return

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val contentSignature = listOf(
            sbn.packageName,
            sbn.key ?: "${sbn.id}:${sbn.tag.orEmpty()}",
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        ).joinToString("\u0000")
        if (isRecentDuplicate(contentSignature)) return

        val trigger = AirNotificationTrigger(rule.packageName, rule.pattern)
        val accepted = AirBridgeOneShotService.enqueue(this, trigger)
        Timber.i(
            "Air notification bridge package=%s pattern=%s accepted=%s",
            rule.packageName,
            rule.pattern.wireName,
            accepted
        )
    }

    private fun isRecentDuplicate(signature: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(recentNotifications) {
            recentNotifications.entries.removeAll { now - it.value > DUPLICATE_WINDOW_MILLIS }
            val previous = recentNotifications[signature]
            if (previous != null && now - previous < DUPLICATE_WINDOW_MILLIS) return true
            recentNotifications[signature] = now
            while (recentNotifications.size > MAX_RECENT_NOTIFICATIONS) {
                val firstKey = recentNotifications.keys.firstOrNull() ?: break
                recentNotifications.remove(firstKey)
            }
        }
        return false
    }

    companion object {
        private const val DUPLICATE_WINDOW_MILLIS = 1_500L
        private const val MAX_RECENT_NOTIFICATIONS = 512
    }
}
