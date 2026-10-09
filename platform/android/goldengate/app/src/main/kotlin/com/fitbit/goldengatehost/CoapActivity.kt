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
        updateResponseMessage("Wachten op de DTLS-verbinding. Daarna worden de huidige trilinstellingen alleen gelezen.")
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
    }

    private fun renderButtonText(stackConfig: StackConfig) {
        val messageType = when (stackConfig) {
            is SocketNetifGattlink -> "UDP"
            is DtlsSocketNetifGattlink -> "DTLS"
            else -> throw IllegalStateException("StackConfig not supported for single message")
        }
        send.text = "Trilinstellingen lezen ($messageType)"
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
            "Alleen-lezen-verzoek verzenden: PUT /settings\n" +
                "SettingsRequest=SETTING_TYPE_HAPTICS (${AirDirectSettingsProtocol.toHex(body)})\n" +
                "Dit verzoek bevat geen instellingswaarden, wijzigt de intensiteit niet en veroorzaakt geen trilling."
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
                            send.text = "Korte triltest: ${AirDirectSettingsProtocol.hapticsIntensityName(intensity)} → " +
                                "${AirDirectSettingsProtocol.hapticsIntensityName(alternate)} → " +
                                AirDirectSettingsProtocol.hapticsIntensityName(intensity)
                        } else {
                            send.text = "Trilinstellingen opnieuw lezen"
                        }
                        updateResponseMessage(
                            "PUT /settings -> ${formatCode(response)}\n" +
                                "Antwoord=${responseBody.size}B ${AirDirectSettingsProtocol.toHex(responseBody)}\n" +
                                "Haptics intensity=${AirDirectSettingsProtocol.hapticsIntensityName(intensity)}\n" +
                                "Er zijn geen instellingswaarden geschreven en geen alarmen verstuurd."
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        updateResponseMessage(
                            "Fout bij het lezen van de trilinstellingen: ${throwable.javaClass.simpleName}: " +
                                "${throwable.message ?: "no message"}\nEr zijn geen instellingswaarden geschreven en geen alarmen verstuurd."
                        )
                    }
                )
        )
    }

    /** Change to the other active intensity, pause briefly, restore the original, then verify. */
    private fun runToggleAndRestoreHapticsTest(
        stackService: CoapEndpoint,
        intensity: Int,
        trigger: String = "Testknop"
    ) {
        if (hapticsOperationInProgress) return
        hapticsOperationInProgress = true
        send.isEnabled = false
        val originalName = AirDirectSettingsProtocol.hapticsIntensityName(intensity)
        val alternateIntensity = alternateHapticsIntensity(intensity)
        val alternateName = AirDirectSettingsProtocol.hapticsIntensityName(alternateIntensity)
        updateResponseMessage(
            "Gestart via $trigger: $originalName → $alternateName → $originalName.\n" +
                "Pauze tussen de schrijfacties: ${HAPTICS_RESTORE_DELAY_MILLIS}ms. Bij een mislukte herstelactie maximaal " +
                "${HAPTICS_RESTORE_RETRIES + 1} pogingen.\n" +
                "Dit zou twee korte trillingen moeten geven. Daarna wordt de instelling automatisch gecontroleerd."
        )
        disposeBag.add(
            postHapticsIntensity(stackService, alternateIntensity)
                .flatMap { alternateResponse ->
                    require(alternateResponse.responseCode.ok()) {
                        "Omschakelen naar $alternateName: ${formatCode(alternateResponse)}"
                    }
                    Single.timer(HAPTICS_RESTORE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                        .flatMap {
                            postHapticsIntensity(stackService, intensity)
                                .map { restoreResponse ->
                                    require(restoreResponse.responseCode.ok()) {
                                        "Herstellen naar $originalName: ${formatCode(restoreResponse)}"
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
                            "Omschakelen naar $alternateName -> ${formatCode(result.alternateResponse)}\n" +
                                "Herstellen naar $originalName -> ${formatCode(result.restoreResponse)}\n" +
                                "Controle achteraf -> ${formatCode(result.readResult.response)}\n" +
                                "Begin=$originalName, einde=" +
                                "${AirDirectSettingsProtocol.hapticsIntensityName(verifiedIntensity)}\n" +
                                "Instelling ongewijzigd=$unchanged\n" +
                                "Controleer hoeveel korte trillingen je voelde en hoe lang de pauze ertussen was."
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        hapticsOperationInProgress = false
                        currentHapticsIntensity = null
                        send.text = "Trilinstellingen opnieuw lezen"
                        updateResponseMessage(
                            "Fout tijdens de omschakel- en hersteltest: ${throwable.javaClass.simpleName}: " +
                                "${throwable.message ?: "no message"}\n" +
                                "Lees de instelling opnieuw. Is de eindwaarde niet $originalName, herstel ze dan in Google Health." +
                                "Deze test schakelt trillingen niet uit (OFF)."
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
            "Melding van ${trigger.sourcePackage}\n" +
                "Patroon=${pattern.wireName} (${pattern.displayName}, ${pattern.groupCount} trilgroepen)\n" +
                "Per trilgroep: $originalName → $alternateName → $originalName; " +
                "herstelpauze binnen de groep: ${HAPTICS_RESTORE_DELAY_MILLIS}ms."
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
                        }.joinToString(", ")
                        updateResponseMessage(
                            "Melding van ${trigger.sourcePackage} uitgevoerd met ${pattern.wireName}\n" +
                                "Resultaat schrijfacties: $responseSummary\n" +
                                "Controle achteraf -> ${formatCode(result.readResult.response)}\n" +
                                "Begin=$originalName, einde=" +
                                "${AirDirectSettingsProtocol.hapticsIntensityName(verifiedIntensity)}\n" +
                                "Instelling ongewijzigd=${verifiedIntensity == intensity}"
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        hapticsOperationInProgress = false
                        currentHapticsIntensity = null
                        send.text = "Trilinstellingen opnieuw lezen"
                        updateResponseMessage(
                            "Fout bij het uitvoeren van het trilpatroon: ${throwable.javaClass.simpleName}: " +
                                "${throwable.message ?: "no message"}\n" +
                                "Lees opnieuw en controleer of de eindintensiteit $originalName is."
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
                    "Resultaat omschakelen: ${formatCode(alternateResponse)}"
                }
                Single.timer(HAPTICS_RESTORE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                    .flatMap {
                        postHapticsIntensity(stackService, originalIntensity)
                            .map { restoreResponse ->
                                require(restoreResponse.responseCode.ok()) {
                                    "Resultaat herstellen: ${formatCode(restoreResponse)}"
                                }
                                restoreResponse
                            }
                            .retry(HAPTICS_RESTORE_RETRIES.toLong())
                    }
                    .map { restoreResponse -> alternateResponse to restoreResponse }
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
                "Alleen-lezen-controle mislukt: de BitGatt-verbinding of gevonden GATT-database is niet beschikbaar.\n" +
                    "Er zijn geen GATT-leesacties, schrijfacties, abonnementen of CoAP-verzoeken uitgevoerd."
            )
            return
        }

        val service = services.firstOrNull { it.uuid == MOBILE_DATA_KEY_SERVICE_UUID }
        if (service == null) {
            val relatedServices = services
                .map { it.uuid.toString() }
                .filter { it.startsWith("26f33a", ignoreCase = true) }
            val relatedText = if (relatedServices.isEmpty()) "Geen" else relatedServices.joinToString()
            updateResponseMessage(
                "Alleen-lezen-controle van GATT voltooid: \n" +
                    "Aantal services=${services.size}\n" +
                    "Mobile Data Key-service=niet gevonden\n" +
                    "26F33A*-services=$relatedText\n" +
                    "Er zijn geen GATT-leesacties, schrijfacties, abonnementen of CoAP-verzoeken uitgevoerd."
            )
            return
        }

        val characteristic = service.getCharacteristic(MOBILE_DATA_KEY_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            val characteristicUuids = service.characteristics.joinToString { it.uuid.toString() }
            updateResponseMessage(
                "Alleen-lezen-controle van GATT voltooid: \n" +
                    "Mobile Data Key-service=gevonden\n" +
                    "Gezochte characteristic=niet gevonden\n" +
                    "Characteristics van deze service=$characteristicUuids\n" +
                    "Er zijn geen GATT-leesacties, schrijfacties, abonnementen of CoAP-verzoeken uitgevoerd."
            )
            return
        }

        val properties = characteristic.properties
        updateResponseMessage(
            "Alleen-lezen-controle van GATT voltooid: \n" +
                "Mobile Data Key-service=gevonden\n" +
                "characteristic=gevonden\n" +
                "properties=0x${properties.toString(16).toUpperCase(Locale.US)} " +
                "[${renderProperties(properties)}]\n" +
                "permissions=0x${characteristic.permissions.toString(16).toUpperCase(Locale.US)}\n" +
                "Er zijn geen GATT-leesacties, schrijfacties, abonnementen of CoAP-verzoeken uitgevoerd."
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
        return if (flags.isEmpty()) "Geen gangbare eigenschappen" else flags.joinToString("|")
    }

    private fun runReadOnlyAuthorizationProbe(stackService: CoapEndpoint) {
        send.isEnabled = false
        updateResponseMessage("BOOTSTRAP-resources alleen lezen; er worden geen gegevens aangemaakt, gewijzigd of verwijderd.")
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
                            "Alleen-lezen-controle van BOOTSTRAP voltooid: \n" + results.joinToString("\n") +
                                "\nAlle verzoeken waren GET-verzoeken; er is niets naar het apparaat geschreven."
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        updateResponseMessage(
                            "Fout tijdens de alleen-lezen-controle: ${throwable.javaClass.simpleName}: ${throwable.message}"
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
        updateResponseMessage("Bestaande Air-alarmen lezen. Als dit mislukt, wordt er geen testalarm aangemaakt.")
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
                            "Testalarm aangemaakt: id=${alarm.id}, starttijd %02d:%02d:%02d.\n".format(
                                alarm.hour,
                                alarm.minute,
                                alarm.second
                            ) + "Na ongeveer ${AUTO_DELETE_AFTER_TRIGGER_MILLIS} ms trillen volgt DELETE /alarms/${alarm.id}."
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
                            "DELETE /alarms/${alarm.id}: ${formatCode(response)}.\n" +
                                "Controleer of de trilling ongeveer ${AUTO_DELETE_AFTER_TRIGGER_MILLIS} ms na de start stopte."
                        )
                    },
                    { throwable ->
                        send.isEnabled = true
                        updateResponseMessage(
                            "Test mislukt: ${throwable.message ?: throwable.javaClass.simpleName}\n" +
                                "Als de Air al trilt, tik er dan tweemaal op om te stoppen. Verwijder een eventueel achtergebleven testalarm in Google Health."
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
