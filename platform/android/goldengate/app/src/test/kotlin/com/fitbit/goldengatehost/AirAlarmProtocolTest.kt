package com.fitbit.goldengatehost

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirAlarmProtocolTest {

    @Test
    fun encodesGoogleHealthCompatibleOneShotConfiguration() {
        assertArrayEquals(
            byteArrayOf(
                0x08, 0x01,
                0x10, 0x00,
                0x18, 0x00,
                0x20, 0x00,
                0x2a, 0x06, 0x08, 21, 0x10, 44, 0x18, 17,
                0x32, 0x01, 0x00
            ),
            AirAlarmProtocol.encodeOneShotConfiguration(21, 44, 17)
        )
    }

    @Test
    fun parsesDeviceAlarmsAndPreservesMultiByteId() {
        val configuration = AirAlarmProtocol.encodeOneShotConfiguration(21, 44, 17)
        val alarmEntry = byteArrayOf(0x08, 0xac.toByte(), 0x02, 0x12, configuration.size.toByte()) + configuration
        val response = byteArrayOf(0x0a, 0x04, 0x08, 0x08, 0x10, 0x00, 0x12, alarmEntry.size.toByte()) + alarmEntry

        val alarm = AirAlarmProtocol.parseDeviceAlarms(response).single()

        assertEquals(300, alarm.id)
        assertEquals(21, alarm.hour)
        assertEquals(44, alarm.minute)
        assertEquals(17, alarm.second)
        assertEquals(true, alarm.enabled)
        assertEquals(false, alarm.repeats)
    }
}
