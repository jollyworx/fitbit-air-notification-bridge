package com.fitbit.goldengatehost

data class AirNotificationTrigger(
    val sourcePackage: String,
    val pattern: AirHapticPattern
)

/** In-process handoff from NotificationListenerService to the currently connected CoAP screen. */
object AirNotificationBridgeController {
    private var owner: Any? = null
    private var handler: ((AirNotificationTrigger) -> Boolean)? = null

    @Synchronized
    fun attach(owner: Any, handler: (AirNotificationTrigger) -> Boolean) {
        this.owner = owner
        this.handler = handler
    }

    @Synchronized
    fun detach(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            this.handler = null
        }
    }

    @Synchronized
    fun requestPulse(trigger: AirNotificationTrigger): Boolean = handler?.invoke(trigger) ?: false
}
