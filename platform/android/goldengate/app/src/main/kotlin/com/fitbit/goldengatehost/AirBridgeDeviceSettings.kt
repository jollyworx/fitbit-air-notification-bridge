package com.fitbit.goldengatehost

import android.bluetooth.BluetoothDevice
import android.content.Context

object AirBridgeDeviceSettings {
    private const val PREFS_NAME = "air_bridge_device"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_LAST_STATUS = "last_status"

    fun saveDevice(context: Context, device: BluetoothDevice) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, device.address)
            .putString(KEY_NAME, device.name ?: "Fitbit Air")
            .apply()
    }

    fun loadAddress(context: Context): String? = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_ADDRESS, null)
        ?.takeIf(String::isNotBlank)

    fun loadDeviceLabel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null) ?: return "未选择 Fitbit Air"
        val name = prefs.getString(KEY_NAME, "Fitbit Air") ?: "Fitbit Air"
        return "$name ($address)"
    }

    fun saveLastStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATUS, status)
            .apply()
    }

    fun loadLastStatus(context: Context): String = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_LAST_STATUS, "尚未执行按需连接任务")
        ?: "尚未执行按需连接任务"
}
