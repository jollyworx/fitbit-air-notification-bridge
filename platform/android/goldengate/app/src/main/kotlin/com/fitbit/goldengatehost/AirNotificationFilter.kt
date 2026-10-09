package com.fitbit.goldengatehost

/** Notification classification only; no notification text or call identity is stored. */
object AirNotificationFilter {
    // Notification.EXTRA_CALL_TYPE and CallStyle constants, public since API 34.
    // Literal values retain compatibility with the inherited compile SDK 30.
    const val EXTRA_CALL_TYPE = "android.callType"
    const val CALL_TYPE_UNKNOWN = 0
    const val CALL_TYPE_INCOMING = 1

    enum class Delivery { IGNORE, NORMAL, INCOMING_CALL }

    fun classify(
        isGroupSummary: Boolean,
        isOngoing: Boolean,
        isCallCategory: Boolean,
        callType: Int,
        hasFullScreenIntent: Boolean,
        usesChronometer: Boolean
    ): Delivery {
        if (isGroupSummary) return Delivery.IGNORE
        if (callType == CALL_TYPE_INCOMING) return Delivery.INCOMING_CALL
        // An ongoing or screened CallStyle call must not trigger a new alert.
        if (callType != CALL_TYPE_UNKNOWN) return Delivery.IGNORE
        // Older incoming-call notifications commonly use a full-screen intent.
        // A chronometer indicates an already connected call.
        if (isCallCategory && hasFullScreenIntent && !usesChronometer) {
            return Delivery.INCOMING_CALL
        }
        return if (isOngoing) Delivery.IGNORE else Delivery.NORMAL
    }
}

/** Remember notification keys only until removal, to alert once per ringing call. */
class AirIncomingCallTracker(private val maxKeys: Int = 512) {
    private val deliveredKeys = LinkedHashSet<String>()

    fun wasDelivered(key: String): Boolean = key in deliveredKeys

    fun markDelivered(key: String) {
        deliveredKeys.add(key)
        while (deliveredKeys.size > maxKeys) {
            deliveredKeys.remove(deliveredKeys.first())
        }
    }

    fun onRemoved(key: String) {
        deliveredKeys.remove(key)
    }
}
