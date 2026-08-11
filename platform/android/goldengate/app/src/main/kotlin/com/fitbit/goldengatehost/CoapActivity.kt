// Copyright 2017-2020 Fitbit, Inc
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.fitbit.bluetooth.fbgatt.FitbitGatt
import com.fitbit.bluetooth.fbgatt.GattConnection
import com.fitbit.bluetooth.fbgatt.rx.BaseFitbitGattCallback
import com.fitbit.bluetooth.fbgatt.rx.PeripheralConnectionStatus
import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.CoapEndpointBuilder
import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import com.fitbit.goldengate.bindings.coap.data.ok
import com.fitbit.goldengate.bindings.dtls.DtlsProtocolStatus
import com.fitbit.goldengate.bindings.node.BluetoothAddressNodeKey
import com.fitbit.goldengate.bindings.stack.DtlsSocketNetifGattlink
import com.fitbit.goldengate.bindings.stack.SocketNetifGattlink
import com.fitbit.goldengate.bindings.stack.Stack
import com.fitbit.goldengate.bindings.stack.StackConfig
import com.fitbit.goldengate.bt.PeerRole
import com.fitbit.goldengate.node.PeerBuilder
import com.fitbit.goldengate.node.stack.StackPeer
import com.fitbit.goldengate.node.stack.StackPeerBuilder
import com.fitbit.goldengatehost.scan.EXTRA_DEVICE
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class CoapActivity : AbstractHostActivity<CoapEndpoint>() {

    private var sendRequestTime: Long = 0
    private val fitbitGatt: FitbitGatt = FitbitGatt.getInstance()
    private lateinit var coapResponseTime: TextView
    private lateinit var editText: EditText
    private lateinit var rxText: TextView
    @Volatile private var currentHapticsIntensity: Int? = null
    @Volatile private var hapticsOperationInProgress = false

    override fun getContentViewRes(): Int = R.layout.a_single_message

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindViews()
        sendRequestTime = System.currentTimeMillis()
        updateResponseMessage("等待 DTLS 会话建立；随后只读请求当前 Haptics 设置。")
    }

    private fun bindViews() {
        coapResponseTime = ActivityCompat.requireViewById(this, R.id.coap_response_time)
        editText = ActivityCompat.requireViewById(this, R.id.edittext)
        rxText = ActivityCompat.requireViewById(this, R.id.rxtext)
    }

    override fun getPeerBuilder(
        peerRole: PeerRole,
        stackConfig: StackConfig,
        connectionStatus: (GattConnection) -> Observable<PeripheralConnectionStatus>,
        dtlsStatus: (Stack) -> Observable<DtlsProtocolStatus>
    ): PeerBuilder<CoapEndpoint, BluetoothAddressNodeKey> = StackPeerBuilder(
        CoapEndpoint::class.java,
        peerRole,
        stackConfig
    ) { nodeKey ->
        StackPeer(
            nodeKey,
            peerRole,
            stackConfig,
            CoapEndpointBuilder(),
            connectionStatus,
            dtlsStatus
        )
    }

    override fun onConnected(stackService: CoapEndpoint, stackConfig: StackConfig) {
        renderButtonText(stackConfig)
        editText.visibility = View.GONE

        send.setOnClickListener {
            sendRequestTime = System.currentTimeMillis()
            val intensity = currentHapticsIntensity
            if (intensity in AirDirectSettingsProtocol.HAPTICS_OFF..AirDirectSettingsProtocol.HAPTICS_HIGH) {
                runToggleAndRestoreHapticsTest(stackService, intensity!!)
            } else {
                runHapticsReadProbe(stackService)
            }
        }
        sendRequestTime = System.currentTimeMillis()
        runHapticsReadProbe(stackService)
        AirNotificationBridgeController.attach(this) { trigger ->
            val intensity = currentHapticsIntensity
            if (hapticsOperationInProgress ||
                intensity !in AirDirectSettingsProtocol.HAPTICS_LOW..AirDirectSettingsProtocol.HAPTICS_HIGH
            ) {
                false
            } else {
                runOnUiThread {
                    sendRequestTime = System.currentTimeMillis()
                    runNotificationHapticPattern(stackService, intensity!!, trigger)
                }
                true
            }
        }
    }

    private fun renderButtonText(stackConfig: StackConfig) {
        val messageType = when (stackConfig) {
            is SocketNetifGattlink -> "UDP"
            is DtlsSocketNetifGattlink -> "DTLS"
            else -> throw IllegalStateException("StackConfig not supported for single message")
        }
        send.text = "只读请求当前 Haptics 设置（$messageType）"
    }

    /**
     * Google Health uses PUT /settings with a SettingsRequest to read selected setting types.
     * This payload contains only SETTING_TYPE_HAPTICS and no setting value, so it cannot change
     * the current intensity or trigger a vibration.
     */
    private fun runHapticsReadProbe(stackService: CoapEndpoint) {
        send.isEnabled = false
        val body = AirDirectSettingsProtocol.encodeHapticsSettingsRequest()
        updateResponseMessage(
            "正在发送语义只读请求：PUT /settings\n" +
                "SettingsRequest=SETTING_TYPE_HAPTICS (${AirDirectSettingsProtocol.toHex(body)})\n" +
                "请求中不含设置值，不会修改强度或触发震动。"
        )
        val request = OutgoingRequestBuilder("/settings", Method.PUT)
            .expectSuccess(false)
            .maxResendCount(0)
            .body(body)
            .build()
        disposeBag.add(
            stackService.responseFor(request)
                .flatMap { response ->
                    response.body.asData()
                        .onErrorReturnItem(byteArrayOf())
                        .map { responseBody -> response to responseBody }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        send.isEnabled = true
                        val response = result.first
                        val responseBody = result.second
                        val intensity = runCatching {
                            AirDirectSettingsProtocol.parseHapticsIntensity(responseBody)
                        }.getOrNull()
                        currentHapticsIntensity = intensity
                        if (response.responseCode.ok() &&
                            intensity in AirDirectSettingsProtocol.HAPTICS_OFF..AirDirectSettingsProtocol.HAPTICS_HIGH
                        ) {
                            val alternate = alternateHapticsIntensity(intensity!!)
                            send.text = "有限震动测试：${AirDirectSettingsProtocol.hapticsIntensityName(intensity)} → " +
                                "${AirDirectSettingsProtocol.hapticsIntensityName(alternate)} → " +
                                AirDirectSettingsProtocol.hapticsIntensityName(intensity)
                        } else {
                            send.text = "重新读取当前 Haptics 设置"
                        }
                        updateResponseMessage(
                            "PUT /settings -> ${formatCode(response)}\n" +
                                "响应=${responseBody.size}B ${AirDirectSettingsProtocol.toHex(responseBody)}\n" +
                                "Haptics intensity=${AirDirectSettingsProtocol.hapticsIntensityName(intensity)}\n" +
                                "本次没有写入设置值，也没有发送闹钟。"
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        updateResponseMessage(
                            "Haptics 只读探测异常：${throwable.javaClass.simpleName}: " +
                                "${throwable.message ?: "no message"}\n未写入设置值，也没有发送闹钟。"
                        )
                    }
                )
        )
    }

    /** Change to the other active intensity, pause briefly, restore the original, then verify. */
    private fun runToggleAndRestoreHapticsTest(
        stackService: CoapEndpoint,
        intensity: Int,
        trigger: String = "手动按钮"
    ) {
        if (hapticsOperationInProgress) return
        hapticsOperationInProgress = true
        send.isEnabled = false
        val originalName = AirDirectSettingsProtocol.hapticsIntensityName(intensity)
        val alternateIntensity = alternateHapticsIntensity(intensity)
        val alternateName = AirDirectSettingsProtocol.hapticsIntensityName(alternateIntensity)
        updateResponseMessage(
            "$trigger 触发：$originalName → $alternateName → $originalName。\n" +
                "两次写入间隔 ${HAPTICS_RESTORE_DELAY_MILLIS}ms；恢复请求失败时最多重试 " +
                "${HAPTICS_RESTORE_RETRIES + 1} 次。\n" +
                "预计产生两次短预览震动，最后自动读取复核。"
        )
        disposeBag.add(
            postHapticsIntensity(stackService, alternateIntensity)
                .flatMap { alternateResponse ->
                    require(alternateResponse.responseCode.ok()) {
                        "切换到 $alternateName 返回 ${formatCode(alternateResponse)}"
                    }
                    Single.timer(HAPTICS_RESTORE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                        .flatMap {
                            postHapticsIntensity(stackService, intensity)
                                .map { restoreResponse ->
                                    require(restoreResponse.responseCode.ok()) {
                                        "恢复 $originalName 返回 ${formatCode(restoreResponse)}"
                                    }
                                    restoreResponse
                                }
                                .retry(HAPTICS_RESTORE_RETRIES.toLong())
                        }
                        .map { restoreResponse -> alternateResponse to restoreResponse }
                }
                .flatMap { writeResponses ->
                    readHapticsIntensity(stackService)
                        .map { readResult -> ToggleRestoreResult(writeResponses.first, writeResponses.second, readResult) }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        send.isEnabled = true
                        hapticsOperationInProgress = false
                        val verifiedIntensity = result.readResult.intensity
                        currentHapticsIntensity = verifiedIntensity
                        val unchanged = verifiedIntensity == intensity
                        updateResponseMessage(
                            "切换 $alternateName -> ${formatCode(result.alternateResponse)}\n" +
                                "恢复 $originalName -> ${formatCode(result.restoreResponse)}\n" +
                                "复核读取 -> ${formatCode(result.readResult.response)}\n" +
                                "初始=$originalName，最终=" +
                                "${AirDirectSettingsProtocol.hapticsIntensityName(verifiedIntensity)}\n" +
                                "状态未改变=$unchanged\n" +
                                "请确认：产生了几次短震、两次间隔体感如何？"
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        hapticsOperationInProgress = false
                        currentHapticsIntensity = null
                        send.text = "重新读取当前 Haptics 设置"
                        updateResponseMessage(
                            "切换/恢复测试异常：${throwable.javaClass.simpleName}: " +
                                "${throwable.message ?: "no message"}\n" +
                                "请点击重新读取；若最终不是 $originalName，可在 Google Health 中恢复。" +
                                "本测试不会设置 OFF。"
                        )
                    }
                )
        )
    }

    /** Execute one or more verified toggle/restore groups without leaving the user's setting changed. */
    private fun runNotificationHapticPattern(
        stackService: CoapEndpoint,
        intensity: Int,
        trigger: AirNotificationTrigger
    ) {
        if (hapticsOperationInProgress) return
        hapticsOperationInProgress = true
        send.isEnabled = false
        val originalName = AirDirectSettingsProtocol.hapticsIntensityName(intensity)
        val alternateIntensity = alternateHapticsIntensity(intensity)
        val alternateName = AirDirectSettingsProtocol.hapticsIntensityName(alternateIntensity)
        val pattern = trigger.pattern
        updateResponseMessage(
            "通知 ${trigger.sourcePackage}\n" +
                "pattern=${pattern.wireName}（${pattern.displayName}，${pattern.groupCount} 组）\n" +
                "每组执行 $originalName → $alternateName → $originalName；" +
                "组内恢复间隔 ${HAPTICS_RESTORE_DELAY_MILLIS}ms。"
        )

        var chain: Single<List<Pair<IncomingResponse, IncomingResponse>>> =
            Single.just(emptyList<Pair<IncomingResponse, IncomingResponse>>())
        repeat(pattern.groupCount) { groupIndex ->
            val gapBefore = if (groupIndex == 0) 0L else pattern.betweenGroupDelaysMillis[groupIndex - 1]
            chain = chain.flatMap { completed ->
                val wait = if (gapBefore == 0L) {
                    Single.just(0L)
                } else {
                    Single.timer(gapBefore, TimeUnit.MILLISECONDS)
                }
                wait.flatMap {
                    postToggleRestoreGroup(stackService, intensity, alternateIntensity)
                }.map { responses -> completed + responses }
            }
        }

        disposeBag.add(
            chain.flatMap { groupResponses ->
                readHapticsIntensity(stackService)
                    .map { readResult -> NotificationPatternResult(groupResponses, readResult) }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        send.isEnabled = true
                        hapticsOperationInProgress = false
                        val verifiedIntensity = result.readResult.intensity
                        currentHapticsIntensity = verifiedIntensity
                        val responseSummary = result.groupResponses.mapIndexed { index, responses ->
                            "#${index + 1} ${formatCode(responses.first)}/${formatCode(responses.second)}"
                        }.joinToString("，")
                        updateResponseMessage(
                            "通知 ${trigger.sourcePackage} 已执行 ${pattern.wireName}\n" +
                                "写入结果：$responseSummary\n" +
                                "复核读取 -> ${formatCode(result.readResult.response)}\n" +
                                "初始=$originalName，最终=" +
                                "${AirDirectSettingsProtocol.hapticsIntensityName(verifiedIntensity)}\n" +
                                "状态未改变=${verifiedIntensity == intensity}"
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        hapticsOperationInProgress = false
                        currentHapticsIntensity = null
                        send.text = "重新读取当前 Haptics 设置"
                        updateResponseMessage(
                            "通知 pattern 执行异常：${throwable.javaClass.simpleName}: " +
                                "${throwable.message ?: "no message"}\n" +
                                "请点击重新读取并确认最终强度为 $originalName。"
                        )
                    }
                )
        )
    }

    private fun postToggleRestoreGroup(
        stackService: CoapEndpoint,
        originalIntensity: Int,
        alternateIntensity: Int
    ): Single<Pair<IncomingResponse, IncomingResponse>> =
        postHapticsIntensity(stackService, alternateIntensity)
            .flatMap { alternateResponse ->
                require(alternateResponse.responseCode.ok()) {
                    "切换写入返回 ${formatCode(alternateResponse)}"
                }
                Single.timer(HAPTICS_RESTORE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                    .flatMap {
                        postHapticsIntensity(stackService, originalIntensity)
                            .map { restoreResponse ->
                                require(restoreResponse.responseCode.ok()) {
                                    "恢复写入返回 ${formatCode(restoreResponse)}"
                                }
                                restoreResponse
                            }
                            .retry(HAPTICS_RESTORE_RETRIES.toLong())
                    }
                    .map { restoreResponse -> alternateResponse to restoreResponse }
            }

    override fun onDestroy() {
        AirNotificationBridgeController.detach(this)
        super.onDestroy()
    }

    private fun alternateHapticsIntensity(intensity: Int): Int = when (intensity) {
        AirDirectSettingsProtocol.HAPTICS_HIGH -> AirDirectSettingsProtocol.HAPTICS_LOW
        AirDirectSettingsProtocol.HAPTICS_LOW -> AirDirectSettingsProtocol.HAPTICS_HIGH
        else -> AirDirectSettingsProtocol.HAPTICS_HIGH
    }

    private fun postHapticsIntensity(
        stackService: CoapEndpoint,
        intensity: Int
    ): Single<IncomingResponse> {
        val request = OutgoingRequestBuilder("/settings", Method.POST)
            .expectSuccess(false)
            .maxResendCount(0)
            .body(AirDirectSettingsProtocol.encodeHapticsSettingsResponse(intensity))
            .build()
        return stackService.responseFor(request)
    }

    private fun readHapticsIntensity(stackService: CoapEndpoint): Single<HapticsReadResult> {
        val request = OutgoingRequestBuilder("/settings", Method.PUT)
            .expectSuccess(false)
            .maxResendCount(0)
            .body(AirDirectSettingsProtocol.encodeHapticsSettingsRequest())
            .build()
        return stackService.responseFor(request)
            .flatMap { response ->
                response.body.asData()
                    .onErrorReturnItem(byteArrayOf())
                    .map { responseBody ->
                        val intensity = runCatching {
                            AirDirectSettingsProtocol.parseHapticsIntensity(responseBody)
                        }.getOrNull()
                        HapticsReadResult(response, intensity)
                    }
            }
    }

    /**
     * Inspect Android's already-discovered GATT database only. This method does not start service
     * discovery and never reads, writes, subscribes to, or otherwise accesses a characteristic.
     */
    private fun inspectMobileDataKeyGatt() {
        val bluetoothDevice = intent.getParcelableExtra<BluetoothDevice>(EXTRA_DEVICE)
        val connection = bluetoothDevice?.let { fitbitGatt.getConnection(it) }
        val services = connection?.gatt?.services

        if (connection == null || services == null) {
            updateResponseMessage(
                "只读检查失败：当前 BitGatt 连接或已发现的 GATT 数据库不可用。\n" +
                    "未执行 GATT 读、写、订阅或 CoAP 请求。"
            )
            return
        }

        val service = services.firstOrNull { it.uuid == MOBILE_DATA_KEY_SERVICE_UUID }
        if (service == null) {
            val relatedServices = services
                .map { it.uuid.toString() }
                .filter { it.startsWith("26f33a", ignoreCase = true) }
            val relatedText = if (relatedServices.isEmpty()) "无" else relatedServices.joinToString()
            updateResponseMessage(
                "只读 GATT 检查完成：\n" +
                    "服务总数=${services.size}\n" +
                    "Mobile Data Key service=未发现\n" +
                    "26F33A* 服务=$relatedText\n" +
                    "未执行 GATT 读、写、订阅或 CoAP 请求。"
            )
            return
        }

        val characteristic = service.getCharacteristic(MOBILE_DATA_KEY_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            val characteristicUuids = service.characteristics.joinToString { it.uuid.toString() }
            updateResponseMessage(
                "只读 GATT 检查完成：\n" +
                    "Mobile Data Key service=已发现\n" +
                    "目标 characteristic=未发现\n" +
                    "该服务特征=$characteristicUuids\n" +
                    "未执行 GATT 读、写、订阅或 CoAP 请求。"
            )
            return
        }

        val properties = characteristic.properties
        updateResponseMessage(
            "只读 GATT 检查完成：\n" +
                "Mobile Data Key service=已发现\n" +
                "characteristic=已发现\n" +
                "properties=0x${properties.toString(16).toUpperCase(Locale.US)} " +
                "[${renderProperties(properties)}]\n" +
                "permissions=0x${characteristic.permissions.toString(16).toUpperCase(Locale.US)}\n" +
                "未执行 GATT 读、写、订阅或 CoAP 请求。"
        )
    }

    private fun renderProperties(properties: Int): String {
        val flags = listOf(
            BluetoothGattCharacteristic.PROPERTY_READ to "READ",
            BluetoothGattCharacteristic.PROPERTY_WRITE to "WRITE",
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to "WRITE_NO_RESPONSE",
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE to "SIGNED_WRITE",
            BluetoothGattCharacteristic.PROPERTY_NOTIFY to "NOTIFY",
            BluetoothGattCharacteristic.PROPERTY_INDICATE to "INDICATE"
        ).filter { properties and it.first != 0 }
            .map { it.second }
        return if (flags.isEmpty()) "无常见属性" else flags.joinToString("|")
    }

    private fun runReadOnlyAuthorizationProbe(stackService: CoapEndpoint) {
        send.isEnabled = false
        updateResponseMessage("正在执行 BOOTSTRAP 只读资源探测；不会创建、修改或删除任何数据。")
        disposeBag.add(
            Observable.fromIterable(READ_ONLY_PROBE_PATHS)
                .concatMapSingle { path -> probeGet(stackService, path) }
                .toList()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { results ->
                        send.isEnabled = true
                        updateResponseMessage(
                            "BOOTSTRAP 只读探测完成：\n" + results.joinToString("\n") +
                                "\n所有请求均为 GET；未写入设备。"
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        updateResponseMessage(
                            "只读探测异常：${throwable.javaClass.simpleName}: ${throwable.message}"
                        )
                    }
                )
        )
    }

    private fun probeGet(stackService: CoapEndpoint, path: String): Single<String> {
        val request = OutgoingRequestBuilder(path, Method.GET)
            .expectSuccess(false)
            .maxResendCount(0)
            .build()
        return stackService.responseFor(request)
            .flatMap { response ->
                response.body.asData()
                    .onErrorReturnItem(byteArrayOf())
                    .map { body ->
                        val discovery = if (path == "/.well-known/core" && body.isNotEmpty()) {
                            val text = body.toString(Charsets.UTF_8)
                                .replace(Regex("[\\r\\n\\t]+"), " ")
                                .take(240)
                            " body=${body.size}B '$text'"
                        } else if (body.isNotEmpty()) {
                            " body=${body.size}B"
                        } else {
                            ""
                        }
                        "$path -> ${formatCode(response)}$discovery"
                    }
            }
            .onErrorReturn { error ->
                "$path -> ERROR ${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            }
    }

    private fun runAlarmAutoDeleteTest(stackService: CoapEndpoint) {
        send.isEnabled = false
        updateResponseMessage("正在读取 Air 现有闹钟；读取失败时不会创建测试闹钟。")
        disposeBag.add(
            getAlarms(stackService)
                .subscribeOn(Schedulers.io())
                .flatMap { existingAlarms ->
                    val triggerAtMillis = System.currentTimeMillis() + TEST_LEAD_MILLIS
                    val calendar = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
                    val hour = calendar.get(Calendar.HOUR_OF_DAY)
                    val minute = calendar.get(Calendar.MINUTE)
                    val second = calendar.get(Calendar.SECOND)
                    val baselineIds = existingAlarms.map { it.id }.toSet()
                    val configuration = AirAlarmProtocol.encodeOneShotConfiguration(hour, minute, second)

                    createAlarm(stackService, configuration)
                        .flatMap {
                            findCreatedAlarm(
                                stackService,
                                baselineIds,
                                hour,
                                minute,
                                second,
                                FIND_ALARM_ATTEMPTS
                            )
                        }
                        .map { alarm -> PreparedAlarm(alarm.id, hour, minute, second, triggerAtMillis) }
                }
                .doOnSuccess { alarm ->
                    runOnUiThread {
                        updateResponseMessage(
                            "已创建测试闹钟 id=${alarm.id}，触发时间 %02d:%02d:%02d。\n".format(
                                alarm.hour,
                                alarm.minute,
                                alarm.second
                            ) + "预计震动 ${AUTO_DELETE_AFTER_TRIGGER_MILLIS} ms 后发送 DELETE /alarms/${alarm.id}。"
                        )
                    }
                }
                .flatMap { alarm ->
                    val deleteAtMillis = alarm.triggerAtMillis + AUTO_DELETE_AFTER_TRIGGER_MILLIS
                    val waitMillis = (deleteAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
                    Single.timer(waitMillis, TimeUnit.MILLISECONDS)
                        .flatMap { deleteAlarm(stackService, alarm.id) }
                        .map { response -> alarm to response }
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        val alarm = result.first
                        val response = result.second
                        send.isEnabled = true
                        updateResponseMessage(
                            "DELETE /alarms/${alarm.id} 返回 ${formatCode(response)}。\n" +
                                "请确认：震动是否在开始约 ${AUTO_DELETE_AFTER_TRIGGER_MILLIS} ms 后停止。"
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        updateResponseMessage(
                            "测试失败：${throwable.message ?: throwable.javaClass.simpleName}\n" +
                                "若 Air 已开始震动，请双击停止，并在 Google Health 中删除残留测试闹钟。"
                        )
                    }
                )
        )
    }

    private fun getAlarms(stackService: CoapEndpoint): Single<List<AirAlarmProtocol.AlarmRecord>> {
        val request = OutgoingRequestBuilder("/alarms", Method.GET)
            .expectSuccess(true)
            .build()
        return stackService.responseFor(request)
            .flatMap { response ->
                require(response.responseCode.ok()) { "GET /alarms returned ${formatCode(response)}" }
                response.body.asData().map(AirAlarmProtocol::parseDeviceAlarms)
            }
    }

    private fun createAlarm(stackService: CoapEndpoint, configuration: ByteArray): Single<IncomingResponse> {
        val request = OutgoingRequestBuilder("/alarms", Method.POST)
            .expectSuccess(true)
            .body(configuration)
            .build()
        return stackService.responseFor(request)
            .map { response ->
                require(response.responseCode.ok()) { "POST /alarms returned ${formatCode(response)}" }
                response
            }
    }

    private fun deleteAlarm(stackService: CoapEndpoint, alarmId: Long): Single<IncomingResponse> {
        val request = OutgoingRequestBuilder("/alarms/$alarmId", Method.DELETE)
            .expectSuccess(true)
            .build()
        return stackService.responseFor(request)
            .map { response ->
                require(response.responseCode.ok()) { "DELETE /alarms/$alarmId returned ${formatCode(response)}" }
                response
            }
    }

    private fun findCreatedAlarm(
        stackService: CoapEndpoint,
        baselineIds: Set<Long>,
        hour: Int,
        minute: Int,
        second: Int,
        attemptsRemaining: Int
    ): Single<AirAlarmProtocol.AlarmRecord> {
        return Single.timer(FIND_ALARM_POLL_MILLIS, TimeUnit.MILLISECONDS)
            .flatMap { getAlarms(stackService) }
            .flatMap { alarms ->
                val matching = alarms.filter {
                    it.id !in baselineIds &&
                        it.hour == hour && it.minute == minute && it.second == second &&
                        it.enabled && !it.repeats
                }
                when {
                    matching.size == 1 -> Single.just(matching.single())
                    matching.size > 1 -> Single.error(
                        IllegalStateException("More than one newly-created alarm matched the test time")
                    )
                    attemptsRemaining > 1 -> findCreatedAlarm(
                        stackService,
                        baselineIds,
                        hour,
                        minute,
                        second,
                        attemptsRemaining - 1
                    )
                    else -> Single.error(
                        IllegalStateException("POST succeeded but the new alarm id could not be identified")
                    )
                }
            }
    }

    private fun formatCode(response: IncomingResponse): String =
        "${response.responseCode.responseClass}.${response.responseCode.detail.toInt().toString().padStart(2, '0')}"

    private fun updateResponseMessage(message: String) {
        coapResponseTime.visibility = View.VISIBLE
        val sendResponseTime = System.currentTimeMillis() - sendRequestTime
        coapResponseTime.text = getString(R.string.label_response_time).format(sendResponseTime)
        rxText.visibility = View.VISIBLE
        rxText.text = getString(R.string.label_log_coap).format(message)
    }

    companion object {
        private val MOBILE_DATA_KEY_SERVICE_UUID =
            UUID.fromString("26F33A00-52A8-414B-99A2-1DB75C99C032")
        private val MOBILE_DATA_KEY_CHARACTERISTIC_UUID =
            UUID.fromString("26F33A01-52A8-414B-99A2-1DB75C99C032")
        private const val TEST_LEAD_MILLIS = 20_000L
        private const val AUTO_DELETE_AFTER_TRIGGER_MILLIS = 1_500L
        private const val FIND_ALARM_POLL_MILLIS = 400L
        private const val FIND_ALARM_ATTEMPTS = 5
        private const val HAPTICS_RESTORE_DELAY_MILLIS = 800L
        private const val HAPTICS_RESTORE_RETRIES = 2
        private val READ_ONLY_PROBE_PATHS = listOf(
            "/.well-known/core",
            "/alarms",
            "/device/info",
            "/pair/status",
            "/pair/display",
            "/bond/control",
            "/sync/status",
            "/sync/config"
        )

        private data class PreparedAlarm(
            val id: Long,
            val hour: Int,
            val minute: Int,
            val second: Int,
            val triggerAtMillis: Long
        )

        private data class HapticsReadResult(
            val response: IncomingResponse,
            val intensity: Int?
        )

        private data class ToggleRestoreResult(
            val alternateResponse: IncomingResponse,
            val restoreResponse: IncomingResponse,
            val readResult: HapticsReadResult
        )

        private data class NotificationPatternResult(
            val groupResponses: List<Pair<IncomingResponse, IncomingResponse>>,
            val readResult: HapticsReadResult
        )

        fun getIntent(context: Context): Intent {
            return Intent(context, CoapActivity::class.java)
        }
    }
}
