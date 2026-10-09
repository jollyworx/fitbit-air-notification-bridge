// Copyright 2017-2020 Fitbit, Inc
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.fitbit.goldengatehost.scan.EXTRA_SAVE_ONLY
import com.fitbit.goldengatehost.scan.ScanActivity

class SetupActivity : AppCompatActivity() {
    private lateinit var notificationRules: EditText
    private lateinit var notificationAccessButton: Button
    private lateinit var notificationAccessStatus: TextView
    private lateinit var selectedDeviceStatus: TextView
    private lateinit var lastTaskStatus: TextView
    private lateinit var chooseDeviceButton: Button
    private lateinit var manualTestButton: Button
    private lateinit var bridgeButton: Button
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.a_setup)
        bindViews()

        notificationRules.setText(AirBridgeNotificationSettings.loadText(this))
        notificationAccessButton.setOnClickListener {
            if (saveRules()) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        chooseDeviceButton.setOnClickListener {
            if (!AirBluetoothPermissions.hasAll(this)) {
                AirBluetoothPermissions.request(this, REQ_ID)
                Toast.makeText(this, "Geef eerst toegang tot nabije apparaten/Bluetooth. Druk daarna opnieuw op Fitbit Air selecteren.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            runCatching { AirBridgeRuntime.ensureInitialized(this) }
                .onSuccess {
                    startActivity(
                        ScanActivity.getIntent(this)
                            .putExtra(EXTRA_SAVE_ONLY, true)
                    )
                }
                .onFailure {
                    Toast.makeText(this, "Bluetooth kon niet worden gestart: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
        bridgeButton.setOnClickListener {
            if (AirBridgeOneShotService.isRunning()) {
                AirBridgeOneShotService.stopBridge()
                Toast.makeText(this, "De bridge stopt zodra de huidige triltaak klaar is", Toast.LENGTH_LONG).show()
            } else if (saveRules() && ensureBridgePermissions()) {
                val accepted = AirBridgeOneShotService.startBridge(this)
                Toast.makeText(this, if (accepted) "Bridge inschakelen" else "Bridge kon niet starten; controleer de laatste taak", Toast.LENGTH_LONG).show()
            }
        }
        manualTestButton.setOnClickListener {
            if (!saveRules() || !ensureBridgePermissions()) return@setOnClickListener
            val accepted = AirBridgeOneShotService.startBridge(
                this,
                AirNotificationTrigger("manual.test", AirHapticPattern.SINGLE)
            )
            Toast.makeText(
                this,
                if (accepted) "De verbindingstest met één trilgroep is gestart" else "Selecteer eerst je Fitbit Air",
                Toast.LENGTH_LONG
            ).show()
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusRefresh)
        if (!AirBluetoothPermissions.hasAll(this)) {
            AirBluetoothPermissions.request(this, REQ_ID)
        }
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun ensureBridgePermissions(): Boolean {
        if (AirBridgeDeviceSettings.loadAddress(this) == null) {
            Toast.makeText(this, "Selecteer eerst je Fitbit Air", Toast.LENGTH_LONG).show()
            return false
        }
        if (!AirBluetoothPermissions.hasAll(this) || !AirBluetoothPermissions.hasNotificationPermission(this)) {
            AirBluetoothPermissions.request(this, REQ_ID)
            Toast.makeText(this, "Sta Bluetooth en meldingen toe. Druk daarna opnieuw op de knop.", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun bindViews() {
        notificationRules = ActivityCompat.requireViewById(this, R.id.notification_package_allowlist)
        notificationAccessButton = ActivityCompat.requireViewById(this, R.id.notification_access_button)
        notificationAccessStatus = ActivityCompat.requireViewById(this, R.id.notification_access_status)
        selectedDeviceStatus = ActivityCompat.requireViewById(this, R.id.selected_device_status)
        lastTaskStatus = ActivityCompat.requireViewById(this, R.id.last_task_status)
        chooseDeviceButton = ActivityCompat.requireViewById(this, R.id.start_button)
        manualTestButton = ActivityCompat.requireViewById(this, R.id.manual_test_button)
        bridgeButton = ActivityCompat.requireViewById(this, R.id.bridge_button)
    }

    private fun saveRules(): Boolean {
        val parsed = AirBridgeNotificationSettings.parseRules(notificationRules.text.toString())
        if (!parsed.isValid) {
            notificationAccessStatus.text = "Regels niet bewaard: \n${parsed.errors.joinToString("\n")}"
            Toast.makeText(this, "Corrigeer eerst de meldingsregels", Toast.LENGTH_LONG).show()
            return false
        }
        AirBridgeNotificationSettings.saveRules(this, parsed.rules)
        notificationRules.setText(AirBridgeNotificationSettings.loadText(this))
        refreshStatus()
        return true
    }

    private fun refreshStatus() {
        val granted = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        val rules = AirBridgeNotificationSettings.loadRules(this).joinToString("\n") {
            "${it.packageName} → ${it.pattern.wireName}"
        }
        notificationAccessStatus.text =
            "Meldingentoegang=${if (granted) "toegestaan" else "niet toegestaan"}\nHuidige regels: \n$rules"
        selectedDeviceStatus.text = "Geselecteerd apparaat: ${AirBridgeDeviceSettings.loadDeviceLabel(this)}"
        lastTaskStatus.text = "Laatste taak: ${AirBridgeDeviceSettings.loadLastStatus(this)}"
        bridgeButton.text = if (AirBridgeOneShotService.isRunning()) "Bridge uitschakelen" else "Bridge inschakelen"
        bridgeButton.isEnabled = AirBridgeDeviceSettings.loadAddress(this) != null
        manualTestButton.isEnabled = AirBridgeDeviceSettings.loadAddress(this) != null
    }
}
