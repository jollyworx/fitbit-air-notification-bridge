package com.fitbit.goldengatehost

/** Minimal protobuf codec for Fitbit DirectSettings messages used by /settings. */
object AirDirectSettingsProtocol {

    const val HAPTICS_UNSPECIFIED = 0
    const val HAPTICS_OFF = 1
    const val HAPTICS_LOW = 2
    const val HAPTICS_HIGH = 3

    /**
     * DirectSettings.SettingsRequest { setting_type: SETTING_TYPE_HAPTICS }
     *
     * setting_type is field 1, a packed repeated enum. SETTING_TYPE_HAPTICS is value 1.
     */
    fun encodeHapticsSettingsRequest(): ByteArray = byteArrayOf(0x0A, 0x01, 0x01)

    /**
     * DirectSettings.SettingsResponse {
     *   settings { haptics_setting { haptics_intensity: value } }
     * }
     *
     * Google Health sends this message with POST /settings when changing the preference.
     */
    fun encodeHapticsSettingsResponse(value: Int): ByteArray {
        require(value in HAPTICS_OFF..HAPTICS_HIGH) { "Unsupported haptics intensity $value" }
        return byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x08, value.toByte())
    }

    /** Returns the first haptics intensity in a DirectSettings.SettingsResponse. */
    fun parseHapticsIntensity(response: ByteArray): Int? {
        val responseReader = ProtoReader(response)
        while (!responseReader.exhausted()) {
            val tag = responseReader.readVarint().toInt()
            val field = tag ushr 3
            val wireType = tag and 7
            if (field == 1 && wireType == 2) {
                parseSetting(responseReader.readLengthDelimited())?.let { return it }
            } else {
                responseReader.skip(wireType)
            }
        }
        return null
    }

    fun hapticsIntensityName(value: Int?): String = when (value) {
        HAPTICS_UNSPECIFIED -> "UNSPECIFIED"
        HAPTICS_OFF -> "OFF"
        HAPTICS_LOW -> "LOW"
        HAPTICS_HIGH -> "HIGH"
        null -> "未找到 HapticsSetting"
        else -> "UNKNOWN($value)"
    }

    fun toHex(bytes: ByteArray, maxBytes: Int = 96): String {
        val visible = bytes.take(maxBytes).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return if (bytes.size > maxBytes) "$visible…" else visible
    }

    private fun parseSetting(setting: ByteArray): Int? {
        val settingReader = ProtoReader(setting)
        while (!settingReader.exhausted()) {
            val tag = settingReader.readVarint().toInt()
            val field = tag ushr 3
            val wireType = tag and 7
            if (field == 1 && wireType == 2) {
                return parseHapticsSetting(settingReader.readLengthDelimited())
            }
            settingReader.skip(wireType)
        }
        return null
    }

    private fun parseHapticsSetting(haptics: ByteArray): Int? {
        val hapticsReader = ProtoReader(haptics)
        while (!hapticsReader.exhausted()) {
            val tag = hapticsReader.readVarint().toInt()
            val field = tag ushr 3
            val wireType = tag and 7
            if (field == 1 && wireType == 0) {
                return hapticsReader.readVarint().toInt()
            }
            hapticsReader.skip(wireType)
        }
        return null
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var position = 0

        fun exhausted(): Boolean = position >= bytes.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < bytes.size) { "Truncated protobuf varint" }
                val value = bytes[position++].toInt() and 0xFF
                result = result or ((value and 0x7F).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Malformed protobuf varint")
        }

        fun readLengthDelimited(): ByteArray {
            val length = readVarint().toInt()
            require(length >= 0 && position + length <= bytes.size) {
                "Truncated protobuf length-delimited field"
            }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> advance(8)
                2 -> advance(readVarint().toInt())
                5 -> advance(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
            }
        }

        private fun advance(count: Int) {
            require(count >= 0 && position + count <= bytes.size) { "Truncated protobuf field" }
            position += count
        }
    }
}
