// Copyright 2017-2020 Fitbit, Inc
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost.scan

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.ParcelUuid
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fitbit.bluetooth.fbgatt.FitbitGatt
import com.fitbit.bluetooth.fbgatt.FitbitBluetoothDevice
import com.fitbit.bluetooth.fbgatt.GattConnection
import com.fitbit.bluetooth.fbgatt.rx.DEFAULT_UUID_MASK
import com.fitbit.bluetooth.fbgatt.rx.GOLDEN_GATE_SERVICE_UUID_MASK
import com.fitbit.bluetooth.fbgatt.rx.KnownGattConnectionFinder
import com.fitbit.goldengate.bt.gatt.server.services.gattlink.FitbitGattlinkService
import com.fitbit.goldengate.bt.gatt.server.services.gattlink.GattlinkService
import com.fitbit.goldengatehost.AirBluetoothPermissions
import com.fitbit.goldengatehost.R
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import timber.log.Timber

/**
 * Uses Android's platform BLE scanner instead of BitGatt 0.9.11's legacy scanner.
 *
 * The legacy scanner can return false before Android's ScanCallback is installed on
 * current Android releases.  A platform ScanResult can be registered through BitGatt's
 * public addBackgroundScannedDeviceConnection API, so the rest of the Golden Gate stack
 * still receives the same GattConnection objects.
 */
class ScanFragment : Fragment() {

    interface ScanListener {
        fun onScanItemClick(connection: GattConnection)
    }

    private var listener: ScanListener? = null
    private lateinit var adapter: ScanRecyclerViewAdapter
    private val knownGattConnectionFinder = KnownGattConnectionFinder()
    private val fitbitGatt = FitbitGatt.getInstance()
    private val disposeBag = CompositeDisposable()
    private var scanStarted = false
    private var discoveredResultCount = 0
    private var companionPickerStarted = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            registerScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::registerScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanStarted = false
            val callbackContext = context ?: return
            if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val message = "De gewone scan is geweigerd. De systeemkiezer voor apparaten wordt geopend.\n" +
                    "APPLICATION_REGISTRATION_FAILED ($errorCode)"
                Timber.w(message)
                Toast.makeText(callbackContext, message, Toast.LENGTH_LONG).show()
                startCompanionDevicePicker()
                return
            }
            val message = "BLE-scan mislukt: ${scanErrorName(errorCode)} ($errorCode)\n" +
                AirBluetoothPermissions.readinessSummary(callbackContext)
            Timber.e(message)
            if (isAdded) {
                Toast.makeText(callbackContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        adapter = ScanRecyclerViewAdapter(listener)

        val view = inflater.inflate(R.layout.fragment_scan_list, container, false)
        if (view is RecyclerView) {
            with(view) {
                adapter = this@ScanFragment.adapter
                layoutManager = LinearLayoutManager(context)
                addItemDecoration(DividerItemDecoration(view.context, DividerItemDecoration.VERTICAL))
            }
        }
        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is ScanListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnListFragmentInteractionListener")
        }
    }

    override fun onResume() {
        super.onResume()
        if (AirBluetoothPermissions.hasAll(requireContext())) {
            scan()
        } else {
            requestPermissions(AirBluetoothPermissions.requiredPermissions(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onPause() {
        stopNativeScan()
        disposeBag.clear()
        super.onPause()
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    @SuppressLint("MissingPermission") // guarded by AirBluetoothPermissions.hasAll
    private fun scan() {
        showKnownConnections()

        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        showBondedDevices(manager)
        val platformScanner = manager?.adapter?.bluetoothLeScanner
        if (platformScanner == null) {
            Toast.makeText(
                requireContext(),
                "Android BLE-scanner niet beschikbaar\n${AirBluetoothPermissions.readinessSummary(requireContext())}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        discoveredResultCount = 0
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            platformScanner.startScan(nativeScanFilters(), settings, scanCallback)
            scanStarted = true
            Toast.makeText(requireContext(), "Android BLE-scan gestart", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            scanStarted = false
            val message = "Fout bij het starten van de BLE-scan: ${t.javaClass.simpleName}: ${t.message}\n" +
                AirBluetoothPermissions.readinessSummary(requireContext())
            Timber.e(t, message)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showKnownConnections() {
        disposeBag.add(
            knownGattConnectionFinder.find()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { connections -> connections.forEach(adapter::add) },
                    { t -> Timber.e(t, "Failed to load known GATT connections") }
                )
        )
    }

    @SuppressLint("MissingPermission") // guarded by AirBluetoothPermissions.hasAll
    private fun showBondedDevices(manager: BluetoothManager?) {
        try {
            manager?.adapter?.bondedDevices?.forEach(::registerBluetoothDevice)
        } catch (t: Throwable) {
            Timber.w(t, "Unable to enumerate bonded Bluetooth devices")
        }
    }

    @SuppressLint("MissingPermission") // guarded by AirBluetoothPermissions.hasAll
    private fun registerScanResult(result: ScanResult) {
        discoveredResultCount += 1
        fitbitGatt.addBackgroundScannedDeviceConnection(result)

        val address = result.device.address
        val connection = fitbitGatt.getMatchingConnectionsForDeviceNames(null)
            .firstOrNull { it.device.address == address }
        if (connection != null && isAdded) {
            requireActivity().runOnUiThread { adapter.add(connection) }
        } else {
            Timber.w("Native scan result was not registered in BitGatt: $address")
        }
    }

    private fun registerBluetoothDevice(device: BluetoothDevice) {
        val existing = fitbitGatt.getMatchingConnectionsForDeviceNames(null)
            .firstOrNull { it.device.address == device.address }
        if (existing != null) {
            adapter.add(existing)
            return
        }

        val wrappedDevice = FitbitBluetoothDevice(device)
        val connection = GattConnection(wrappedDevice, Looper.getMainLooper())
        fitbitGatt.putConnectionIntoDevices(wrappedDevice, connection)
        adapter.add(connection)
    }

    private fun startCompanionDevicePicker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || companionPickerStarted || !isAdded) return
        companionPickerStarted = true

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(nativeScanFilters()[0]).build())
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(nativeScanFilters()[1]).build())
            .setSingleDevice(false)
            .build()
        val manager = requireContext().getSystemService(CompanionDeviceManager::class.java)
        manager.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender?) {
                    if (chooserLauncher == null || !isAdded) {
                        companionPickerStarted = false
                        return
                    }
                    try {
                        startIntentSenderForResult(
                            chooserLauncher,
                            COMPANION_DEVICE_REQUEST_CODE,
                            null,
                            0,
                            0,
                            0,
                            null
                        )
                    } catch (t: Throwable) {
                        companionPickerStarted = false
                        Timber.e(t, "Unable to open companion device chooser")
                        Toast.makeText(requireContext(), "De systeemkiezer voor apparaten kon niet openen: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    companionPickerStarted = false
                    if (!isAdded) return
                    Toast.makeText(
                        requireContext(),
                        "De systeemkiezer voor apparaten is mislukt: ${error ?: "unknown"}\nZet Bluetooth uit en weer aan en probeer opnieuw",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            null
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != COMPANION_DEVICE_REQUEST_CODE) return
        companionPickerStarted = false
        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Geen apparaat geselecteerd", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        val selected = data?.extras?.get(CompanionDeviceManager.EXTRA_DEVICE)
        when (selected) {
            is ScanResult -> {
                registerScanResult(selected)
                val device = selected.device
                Toast.makeText(context, "Toegevoegd via de systeemkiezer: ${device.name ?: device.address}", Toast.LENGTH_LONG).show()
            }
            is BluetoothDevice -> {
                registerBluetoothDevice(selected)
                Toast.makeText(context, "Toegevoegd via de systeemkiezer: ${selected.name ?: selected.address}", Toast.LENGTH_LONG).show()
            }
            else -> Toast.makeText(
                context,
                "De systeemkiezer gaf geen BLE ScanResult terug (ontvangen: ${selected?.javaClass?.simpleName ?: "null"})",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("MissingPermission") // guarded by AirBluetoothPermissions.hasAll
    private fun stopNativeScan() {
        if (!scanStarted || !AirBluetoothPermissions.hasAll(requireContext())) return
        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Timber.w(t, "Unable to stop native BLE scan")
        } finally {
            scanStarted = false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (AirBluetoothPermissions.hasAll(requireContext())) {
            scan()
        } else {
            Toast.makeText(
                context,
                "Scannen niet mogelijk: ${AirBluetoothPermissions.readinessSummary(requireContext())}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun nativeScanFilters() = listOf(
        ScanFilter.Builder()
            .setServiceUuid(
                ParcelUuid(GattlinkService.uuid),
                ParcelUuid.fromString(GOLDEN_GATE_SERVICE_UUID_MASK)
            )
            .build(),
        ScanFilter.Builder()
            .setServiceUuid(
                ParcelUuid(FitbitGattlinkService.uuid),
                ParcelUuid.fromString(DEFAULT_UUID_MASK)
            )
            .build()
    )

    private fun scanErrorName(errorCode: Int): String = when (errorCode) {
        SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APPLICATION_REGISTRATION_FAILED"
        SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "OUT_OF_HARDWARE_RESOURCES"
        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCANNING_TOO_FREQUENTLY"
        else -> "UNKNOWN"
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1235
        private const val COMPANION_DEVICE_REQUEST_CODE = 1236
        private const val SCAN_FAILED_ALREADY_STARTED = 1
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
        private const val SCAN_FAILED_INTERNAL_ERROR = 3
        private const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
        private const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
