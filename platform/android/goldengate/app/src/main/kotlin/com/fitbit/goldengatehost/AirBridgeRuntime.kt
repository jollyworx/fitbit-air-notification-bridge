package com.fitbit.goldengatehost

import android.content.Context
import com.fitbit.goldengate.GoldenGateConnectionManagerModule
import java.util.concurrent.atomic.AtomicBoolean

/** Initializes the process-wide Golden Gate and BitGatt runtime exactly once. */
object AirBridgeRuntime {
    private val initialized = AtomicBoolean(false)

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized.get()) return
        try {
            GoldenGateConnectionManagerModule.init(
                context.applicationContext,
                loggingEnabled = true,
                isBleCentralRole = true
            )
            initialized.set(true)
        } catch (throwable: Throwable) {
            initialized.set(false)
            throw throwable
        }
    }
}
