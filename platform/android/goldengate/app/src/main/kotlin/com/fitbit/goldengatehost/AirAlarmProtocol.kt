// Copyright 2026
// SPDX-License-Identifier: Apache-2.0

package com.fitbit.goldengatehost

import java.io.ByteArrayOutputStream

/** Minimal protobuf codec for the Fitbit Air /alarms resource. */
internal object AirAlarmProtocol {

    data class AlarmRecord(
        val id: Long,
        val hour: Int?,
        val minute: Int?,
        val second: Int?,
        val enabled: Boolean,
        val repeats: Boolean
    )

    /**
     * Encodes DeviceAlarms.Configuration exactly as Google Health does:
     * enabled, repeats, smart-wake, recurring, AlarmTime and daysOfWeek.
     */
    fun encodeOneShotConfiguration(hour: Int, minute: Int, second: Int): ByteArray {
        require(hour in 0..23)
        require(minute in 0..59)
        require(second in 0..59)

        val alarmTime = ProtoWriter().apply {
            writeVarintField(1, hour.toLong())
            writeVarintField(2, minute.toLong())
            writeVarintField(3, second.toLong())
        }.toByteArray()

        return ProtoWriter().apply {
            writeVarintField(1, 1) // enabled
            writeVarintField(2, 0) // repeats/snooze disabled
            writeVarintField(3, 0) // smart wake disabled
            writeVarintField(4, 0) // non-recurring
            writeBytesField(5, alarmTime)
            writeBytesField(6, byteArrayOf(0)) // no recurring weekdays
        }.toByteArray()
    }

    fun parseDeviceAlarms(data: ByteArray): List<AlarmRecord> {
        val alarms = mutableListOf<AlarmRecord>()
        val reader = ProtoReader(data)
        while (!reader.isAtEnd()) {
            val tag = reader.readVarint().toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            if (fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED) {
                alarms += parseAlarmEntry(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }
        return alarms
    }

    private fun parseAlarmEntry(data: ByteArray): AlarmRecord {
        var id: Long? = null
        var config = ParsedConfiguration()
        val reader = ProtoReader(data)
        while (!reader.isAtEnd()) {
            val tag = reader.readVarint().toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == 1 && wireType == WIRE_VARINT -> id = reader.readVarint()
                fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED -> {
                    config = parseConfiguration(reader.readBytes())
                }
                else -> reader.skipField(wireType)
            }
        }
        return AlarmRecord(
            id = id ?: throw IllegalArgumentException("AlarmEntry is missing id"),
            hour = config.hour,
            minute = config.minute,
            second = config.second,
            enabled = config.enabled,
            repeats = config.repeats
        )
    }

    private data class ParsedConfiguration(
        val hour: Int? = null,
        val minute: Int? = null,
        val second: Int? = null,
        val enabled: Boolean = false,
        val repeats: Boolean = false
    )

    private fun parseConfiguration(data: ByteArray): ParsedConfiguration {
        var hour: Int? = null
        var minute: Int? = null
        var second: Int? = null
        var enabled = false
        var repeats = false
        val reader = ProtoReader(data)
        while (!reader.isAtEnd()) {
            val tag = reader.readVarint().toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == 1 && wireType == WIRE_VARINT -> enabled = reader.readVarint() != 0L
                fieldNumber == 2 && wireType == WIRE_VARINT -> repeats = reader.readVarint() != 0L
                fieldNumber == 5 && wireType == WIRE_LENGTH_DELIMITED -> {
                    val time = parseAlarmTime(reader.readBytes())
                    hour = time.first
                    minute = time.second
                    second = time.third
                }
                else -> reader.skipField(wireType)
            }
        }
        return ParsedConfiguration(hour, minute, second, enabled, repeats)
    }

    private fun parseAlarmTime(data: ByteArray): Triple<Int?, Int?, Int?> {
        var hour: Int? = null
        var minute: Int? = null
        var second: Int? = null
        val reader = ProtoReader(data)
        while (!reader.isAtEnd()) {
            val tag = reader.readVarint().toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 7
            when {
                fieldNumber == 1 && wireType == WIRE_VARINT -> hour = reader.readVarint().toInt()
                fieldNumber == 2 && wireType == WIRE_VARINT -> minute = reader.readVarint().toInt()
                fieldNumber == 3 && wireType == WIRE_VARINT -> second = reader.readVarint().toInt()
                else -> reader.skipField(wireType)
            }
        }
        return Triple(hour, minute, second)
    }

    private class ProtoWriter {
        private val output = ByteArrayOutputStream()

        fun writeVarintField(fieldNumber: Int, value: Long) {
            writeVarint(((fieldNumber shl 3) or WIRE_VARINT).toLong())
            writeVarint(value)
        }

        fun writeBytesField(fieldNumber: Int, value: ByteArray) {
            writeVarint(((fieldNumber shl 3) or WIRE_LENGTH_DELIMITED).toLong())
            writeVarint(value.size.toLong())
            output.write(value)
        }

        private fun writeVarint(value: Long) {
            var remaining = value
            while (true) {
                if ((remaining and -128L) == 0L) {
                    output.write(remaining.toInt())
                    return
                }
                output.write(((remaining and 0x7f) or 0x80).toInt())
                remaining = remaining ushr 7
            }
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private class ProtoReader(private val data: ByteArray) {
        private var position = 0

        fun isAtEnd(): Boolean = position >= data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < data.size) { "Truncated protobuf varint" }
                val value = data[position++].toInt() and 0xff
                result = result or ((value and 0x7f).toLong() shl shift)
                if ((value and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Malformed protobuf varint")
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            require(length >= 0 && position + length <= data.size) { "Truncated protobuf bytes" }
            return data.copyOfRange(position, position + length).also { position += length }
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED_64 -> skip(8)
                WIRE_LENGTH_DELIMITED -> skip(readVarint().toInt())
                WIRE_FIXED_32 -> skip(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
            }
        }

        private fun skip(length: Int) {
            require(length >= 0 && position + length <= data.size) { "Truncated protobuf field" }
            position += length
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED_64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED_32 = 5
}
