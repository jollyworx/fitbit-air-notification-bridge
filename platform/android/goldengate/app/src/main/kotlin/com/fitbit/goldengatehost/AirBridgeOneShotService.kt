package com.fitbit.goldengatehost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fitbit.bluetooth.fbgatt.FitbitBluetoothDevice
import com.fitbit.bluetooth.fbgatt.FitbitGatt
import com.fitbit.bluetooth.fbgatt.GattConnection
import com.fitbit.bluetooth.fbgatt.rx.PeripheralConnectionChangeListener
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.CoapEndpointBuilder
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlink
import com.fitbit.goldengate.bt.DEFAULT_CLIENT_ADDRESS
import com.fitbit.goldengate.bt.DEFAULT_SERVER_ADDRESS
import com.fitbit.goldengate.bt.PeerRole
import com.fitbit.goldengate.node.stack.StackPeer
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Owns the Air connection only for one queued notification task:
 * scan -> connect -> DTLS -> haptic pattern -> verify -> disconnect.
 */
class AirBridgeOneShotService : Service() {
    private val queue = ArrayDeque<AirNotificationTrigger>()
    private val disposables = CompositeDisposable()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var processing = false
    private var activePeer: StackPeer<CoapEndpoint>? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("等待按需震动任务"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE)
        val pattern = intent?.getStringExtra(EXTRA_PATTERN)?.let(AirHapticPattern::fromWireName)
        if (sourcePackage != null && pattern != null) {
            if (queue.size < MAX_QUEUE_SIZE) {
                queue.addLast(AirNotificationTrigger(sourcePackage, pattern))
            } else {
                updateStatus("任务队列已满，忽略 $sourcePackage")
            }
        }
        processNext()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        disposables.dispose()
        releasePeer()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun processNext() {
        if (processing) return
        val trigger = queue.pollFirst()
        if (trigger == null) {
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
            return
        }
        acquireWakeLock()
        processing = true
        updateStatus("准备连接 Air：${trigger.sourcePackage} / ${trigger.pattern.wireName}")
        disposables.add(
            executeWithRetry(trigger, 0)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        val status = "成功：${trigger.sourcePackage} / ${trigger.pattern.wireName}，" +
                            "${result.groups.size} 组，强度已恢复为 " +
                            AirDirectSettingsProtocol.hapticsIntensityName(result.finalIntensity)
                        updateStatus(status)
                        processing = false
                        processNext()
                    },
                    { throwable ->
                        val status = "失败：${trigger.sourcePackage} / ${trigger.pattern.wireName}：" +
                            "${throwable.javaClass.simpleName}: ${throwable.message ?: "unknown"}"
                        Timber.w(throwable, status)
                        updateStatus(status)
                        processing = false
                        processNext()
                    }
                )
        )
    }

    private fun executeWithRetry(
        trigger: AirNotificationTrigger,
        attemptIndex: Int
    ): Single<AirHapticExecutionResult> = executeOnce(trigger)
        .onErrorResumeNext { throwable ->
            releasePeer()
            if (attemptIndex >= RETRY_DELAYS_MILLIS.size) {
                Single.error(throwable)
            } else {
                val delay = RETRY_DELAYS_MILLIS[attemptIndex]
                updateStatus(
                    "Air 暂不可用，${delay / 1000}s 后进行第 ${attemptIndex + 2} 次尝试；" +
                        "Google Health 可能正在同步"
                )
                Single.timer(delay, TimeUnit.MILLISECONDS)
                    .flatMap { executeWithRetry(trigger, attemptIndex + 1) }
            }
        }

    private fun executeOnce(trigger: AirNotificationTrigger): Single<AirHapticExecutionResult> {
        val address = AirBridgeDeviceSettings.loadAddress(this)
            ?: return Single.error(IllegalStateException("尚未选择 Fitbit Air"))
        return Single.fromCallable {
            AirBridgeRuntime.ensureInitialized(this)
            address
        }.flatMap { scanForDevice(it) }
            .flatMap { connection -> connectStack(connection.device.address) }
            .flatMap { endpoint ->
                updateStatus("已建立 DTLS，正在执行 ${trigger.pattern.wireName}")
                AirHapticPatternExecutor.execute(endpoint, trigger.pattern)
            }
            .flatMap { result ->
                releasePeer()
                Single.timer(RELEASE_SETTLE_MILLIS, TimeUnit.MILLISECONDS).map { result }
            }
            .doOnError { releasePeer() }
    }

    private fun scanForDevice(address: String): Single<GattConnection> = Single.create { emitter ->
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter
            ?: run {
                emitter.onError(IllegalStateException("Bluetooth adapter unavailable"))
                return@create
            }
        if (!adapter.isEnabled) {
            emitter.onError(IllegalStateException("Bluetooth is disabled"))
            return@create
        }
        val scanner = adapter.bluetoothLeScanner
            ?: run {
                emitter.onError(IllegalStateException("BLE scanner unavailable"))
                return@create
            }
        lateinit var callback: ScanCallback
        val stopScan = {
            mainHandler.removeCallbacksAndMessages(callback)
            runCatching { scanner.stopScan(callback) }
        }
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.device.address.equals(address, ignoreCase = true)) return
                stopScan()
                if (!emitter.isDisposed) {
                    runCatching { registerScannedDevice(result) }
                        .onSuccess(emitter::onSuccess)
                        .onFailure(emitter::onError)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                if (!emitter.isDisposed) emitter.onError(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        val timeout = Runnable {
            stopScan()
            if (!emitter.isDisposed) {
                emitter.onError(IllegalStateException("Air not advertising (timeout)"))
            }
        }
        emitter.setCancellable {
            mainHandler.removeCallbacks(timeout)
            stopScan()
        }
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
            mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MILLIS)
        } catch (throwable: Throwable) {
            stopScan()
            if (!emitter.isDisposed) emitter.onError(throwable)
        }
    }

    private fun registerScannedDevice(result: ScanResult): GattConnection {
        val fitbitGatt = FitbitGatt.getInstance()
        fitbitGatt.addBackgroundScannedDeviceConnection(result)
        fitbitGatt.getMatchingConnectionsForDeviceNames(null)
            .firstOrNull { it.device.address.equals(result.device.address, ignoreCase = true) }
            ?.let { return it }
        val wrappedDevice = FitbitBluetoothDevice(result.device)
        return GattConnection(wrappedDevice, Looper.getMainLooper()).also {
            fitbitGatt.putConnectionIntoDevices(wrappedDevice, it)
        }
    }

    private fun connectStack(address: String): Single<CoapEndpoint> {
        updateStatus("已发现 Air，正在建立 Gattlink / DTLS")
        DiagnosticTlsIdentityRecorder.clear()
        val stackConfig = DtlsSocketNetifGattlink(
            Inet4Address.getByName(DEFAULT_SERVER_ADDRESS) as Inet4Address,
            0,
            Inet4Address.getByName(DEFAULT_CLIENT_ADDRESS) as Inet4Address,
            0
        )
        val peer = StackPeer(
            BluetoothAddressNodeKey(address),
            PeerRole.Peripheral,
            stackConfig,
            CoapEndpointBuilder(),
            { connection -> PeripheralConnectionChangeListener().register(connection) },
            { stack -> stack.dtlsEventObservable }
        )
        activePeer = peer
        return peer.connection()
            .firstOrError()
            .timeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .map { peer.stackService }
    }

    @Synchronized
    private fun releasePeer() {
        activePeer?.let { peer ->
            activePeer = null
            runCatching { peer.close() }
                .onFailure { Timber.w(it, "Failed to close one-shot Air peer") }
        }
    }

    private fun updateStatus(status: String) {
        Timber.i("Air one-shot: %s", status)
        AirBridgeDeviceSettings.saveLastStatus(this, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitbitairbridge:one-shot")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(status: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.jiji)
        .setContentTitle("Fitbit Air Notification Bridge")
        .setContentText(status)
        .setStyle(NotificationCompat.BigTextStyle().bigText(status))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, SetupActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Fitbit Air 按需连接",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val EXTRA_SOURCE_PACKAGE = "source_package"
        private const val EXTRA_PATTERN = "pattern"
        private const val CHANNEL_ID = "air_bridge_connection"
        private const val NOTIFICATION_ID = 14001
        private const val MAX_QUEUE_SIZE = 10
        private const val SCAN_TIMEOUT_MILLIS = 7_000L
        private const val CONNECT_TIMEOUT_SECONDS = 18L
        private const val RELEASE_SETTLE_MILLIS = 700L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 120_000L
        private val RETRY_DELAYS_MILLIS = listOf(2_000L, 5_000L)

        fun enqueue(context: Context, trigger: AirNotificationTrigger): Boolean {
            if (AirBridgeDeviceSettings.loadAddress(context) == null) return false
            val intent = Intent(context, AirBridgeOneShotService::class.java)
                .putExtra(EXTRA_SOURCE_PACKAGE, trigger.sourcePackage)
                .putExtra(EXTRA_PATTERN, trigger.pattern.wireName)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse {
                Timber.w(it, "Unable to start one-shot Air service")
                AirBridgeDeviceSettings.saveLastStatus(
                    context,
                    "无法启动后台任务：${it.javaClass.simpleName}: ${it.message ?: "unknown"}"
                )
                false
            }
        }
    }
}
