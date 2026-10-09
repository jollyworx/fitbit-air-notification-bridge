package com.fitbit.goldengatehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirBridgeNotificationSettingsTest {
    @Test
    fun parsesPackagePatternRules() {
        val parsed = AirBridgeNotificationSettings.parseRules(
            "com.tencent.mm=single\norg.telegram.messenger=long_gap"
        )

        assertTrue(parsed.errors.toString(), parsed.isValid)
        assertEquals(
            listOf(
                AirNotificationRule("com.tencent.mm", AirHapticPattern.SINGLE),
                AirNotificationRule("org.telegram.messenger", AirHapticPattern.LONG_GAP)
            ),
            parsed.rules
        )
    }

    @Test
    fun rejectsDuplicateInvalidAndMoreThanFiveRules() {
        val parsed = AirBridgeNotificationSettings.parseRules(
            "com.tencent.mm=single\n" +
                "com.tencent.mm=double\n" +
                "invalid=single\n" +
                "org.one.app=unknown\n" +
                "org.two.app=single\n" +
                "org.three.app=single"
        )

        assertFalse(parsed.isValid)
        assertTrue(parsed.errors.any { it.contains("maximaal") })
        assertTrue(parsed.errors.any { it.contains("dubbele regel") })
        assertTrue(parsed.errors.any { it.contains("ongeldige pakketnaam") })
        assertTrue(parsed.errors.any { it.contains("ongeldig patroon") })
    }

    @Test
    fun exposesStablePatternGroupCounts() {
        assertEquals(1, AirHapticPattern.SINGLE.groupCount)
        assertEquals(2, AirHapticPattern.DOUBLE.groupCount)
        assertEquals(3, AirHapticPattern.TRIPLE.groupCount)
        assertEquals(2, AirHapticPattern.LONG_GAP.groupCount)
        assertEquals(4, AirHapticPattern.URGENT.groupCount)
    }
}
