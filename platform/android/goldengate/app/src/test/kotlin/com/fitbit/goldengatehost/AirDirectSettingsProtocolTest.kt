package com.fitbit.goldengatehost

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirDirectSettingsProtocolTest {

    @Test
    fun encodesPackedHapticsSettingsRequest() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0x01, 0x01),
            AirDirectSettingsProtocol.encodeHapticsSettingsRequest()
        )
    }

    @Test
    fun parsesHighHapticsIntensity() {
        // SettingsResponse.settings[0].haptics_setting.haptics_intensity = HIGH (3)
        val response = byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x08, 0x03)
        assertEquals(3, AirDirectSettingsProtocol.parseHapticsIntensity(response))
        assertEquals("HIGH", AirDirectSettingsProtocol.hapticsIntensityName(3))
    }

    @Test
    fun encodesHighHapticsSettingsResponse() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x08, 0x03),
            AirDirectSettingsProtocol.encodeHapticsSettingsResponse(3)
        )
    }
}
