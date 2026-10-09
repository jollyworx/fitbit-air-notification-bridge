package com.fitbit.goldengatehost

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Permission compatibility for BLE scanning from Android 5 through Android 16+. */
object AirBluetoothPermissions {
    private const val ANDROID_12 = 31
    private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= ANDROID_12) {
        arrayOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasAll(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun request(activity: Activity, requestCode: Int) {
        val permissions = requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), requestCode)
    }

    fun readinessSummary(context: Context): String {
        val bluetoothEnabled = try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        val permissionSummary = requiredPermissions().joinToString(", ") { permission ->
            val shortName = permission.substringAfterLast('.')
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            "$shortName=${if (granted) "granted" else "denied"}"
        }
        return "API=${Build.VERSION.SDK_INT}, Bluetooth=${if (bluetoothEnabled) "on" else "off"}, $permissionSummary"
    }
}
