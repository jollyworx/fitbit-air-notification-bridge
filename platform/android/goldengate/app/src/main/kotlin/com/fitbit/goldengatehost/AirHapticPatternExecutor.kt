package com.fitbit.goldengatehost

import com.fitbit.goldengate.bindings.coap.CoapEndpoint
import com.fitbit.goldengate.bindings.coap.data.IncomingResponse
import com.fitbit.goldengate.bindings.coap.data.Method
import com.fitbit.goldengate.bindings.coap.data.OutgoingRequestBuilder
import com.fitbit.goldengate.bindings.coap.data.ok
import io.reactivex.Single
import java.util.concurrent.TimeUnit

data class AirHapticGroupResult(
    val alternateResponse: IncomingResponse,
    val restoreResponse: IncomingResponse
)

data class AirHapticExecutionResult(
    val initialIntensity: Int,
    val finalIntensity: Int,
    val groups: List<AirHapticGroupResult>
)

/** Executes bounded pattern groups and verifies that the original setting was restored. */
object AirHapticPatternExecutor {
    private const val RESTORE_DELAY_MILLIS = 800L
    private const val RESTORE_RETRIES = 2

    fun execute(endpoint: CoapEndpoint, pattern: AirHapticPattern): Single<AirHapticExecutionResult> =
        readIntensity(endpoint).flatMap { initialIntensity ->
            require(initialIntensity in AirDirectSettingsProtocol.HAPTICS_LOW..AirDirectSettingsProtocol.HAPTICS_HIGH) {
                "Unsupported starting haptics intensity ${AirDirectSettingsProtocol.hapticsIntensityName(initialIntensity)}"
            }
            val alternateIntensity = when (initialIntensity) {
                AirDirectSettingsProtocol.HAPTICS_HIGH -> AirDirectSettingsProtocol.HAPTICS_LOW
                else -> AirDirectSettingsProtocol.HAPTICS_HIGH
            }
            var chain: Single<List<AirHapticGroupResult>> = Single.just(emptyList())
            repeat(pattern.groupCount) { groupIndex ->
                val delayBefore = if (groupIndex == 0) {
                    0L
                } else {
                    pattern.betweenGroupDelaysMillis[groupIndex - 1]
                }
                chain = chain.flatMap { completed ->
                    val wait = if (delayBefore == 0L) {
                        Single.just(0L)
                    } else {
                        Single.timer(delayBefore, TimeUnit.MILLISECONDS)
                    }
                    wait.flatMap {
                        executeGroup(endpoint, initialIntensity, alternateIntensity)
                    }.map { completed + it }
                }
            }
            chain.flatMap { groups ->
                readIntensity(endpoint).map { finalIntensity ->
                    require(finalIntensity == initialIntensity) {
                        "Haptics restore verification failed: initial=$initialIntensity final=$finalIntensity"
                    }
                    AirHapticExecutionResult(initialIntensity, finalIntensity, groups)
                }
            }
        }

    private fun executeGroup(
        endpoint: CoapEndpoint,
        originalIntensity: Int,
        alternateIntensity: Int
    ): Single<AirHapticGroupResult> =
        writeIntensity(endpoint, alternateIntensity).flatMap { alternateResponse ->
            require(alternateResponse.responseCode.ok()) {
                "Alternate write returned ${alternateResponse.responseCode}"
            }
            Single.timer(RESTORE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .flatMap {
                    writeIntensity(endpoint, originalIntensity)
                        .map { restoreResponse ->
                            require(restoreResponse.responseCode.ok()) {
                                "Restore write returned ${restoreResponse.responseCode}"
                            }
                            restoreResponse
                        }
                        .retry(RESTORE_RETRIES.toLong())
                }
                .map { restoreResponse -> AirHapticGroupResult(alternateResponse, restoreResponse) }
        }

    private fun readIntensity(endpoint: CoapEndpoint): Single<Int> {
        val request = OutgoingRequestBuilder("/settings", Method.PUT)
            .expectSuccess(false)
            .maxResendCount(0)
            .body(AirDirectSettingsProtocol.encodeHapticsSettingsRequest())
            .build()
        return endpoint.responseFor(request).flatMap { response ->
            require(response.responseCode.ok()) { "Settings read returned ${response.responseCode}" }
            response.body.asData().map { body ->
                AirDirectSettingsProtocol.parseHapticsIntensity(body)
                    ?: throw IllegalStateException("Haptics intensity missing from settings response")
            }
        }
    }

    private fun writeIntensity(endpoint: CoapEndpoint, intensity: Int): Single<IncomingResponse> {
        val request = OutgoingRequestBuilder("/settings", Method.POST)
            .expectSuccess(false)
            .maxResendCount(0)
            .body(AirDirectSettingsProtocol.encodeHapticsSettingsResponse(intensity))
            .build()
        return endpoint.responseFor(request)
    }
}
