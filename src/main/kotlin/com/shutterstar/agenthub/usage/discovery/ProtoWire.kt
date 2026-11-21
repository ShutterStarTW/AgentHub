package com.shutterstar.agenthub.usage.discovery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/** One field of a protobuf message: [varint] for wire type 0, [bytes] for the fixed and length-delimited types. */
internal class ProtoField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray)

/** Minimal protobuf wire reader, enough for the small flat messages of the Cursor dashboard RPCs. */
internal object ProtoWire {
    /** Parses [input] into fields, or returns null when it is malformed or uses a group wire type. */
    fun readFields(input: ByteArray): List<ProtoField>? {
        val fields = mutableListOf<ProtoField>()
        var offset = 0
        while (offset < input.size) {
            val tag = readVarint(input, offset) ?: return null
            offset = tag.second
            val number = (tag.first ushr 3).toInt()
            val wireType = (tag.first and 0x07).toInt()
            if (number <= 0) return null
            when (wireType) {
                0 -> {
                    val value = readVarint(input, offset) ?: return null
                    fields += ProtoField(number, wireType, value.first, EMPTY)
                    offset = value.second
                }
                1 -> {
                    if (offset + 8 > input.size) return null
                    fields += ProtoField(number, wireType, 0, input.copyOfRange(offset, offset + 8))
                    offset += 8
                }
                2 -> {
                    val length = readVarint(input, offset) ?: return null
                    offset = length.second
                    val size = length.first
                    if (size < 0 || size > input.size - offset) return null
                    fields += ProtoField(number, wireType, 0, input.copyOfRange(offset, offset + size.toInt()))
                    offset += size.toInt()
                }
                5 -> {
                    if (offset + 4 > input.size) return null
                    fields += ProtoField(number, wireType, 0, input.copyOfRange(offset, offset + 4))
                    offset += 4
                }
                else -> return null
            }
        }
        return fields
    }

    fun doubleValue(field: ProtoField): Double? =
        if (field.wireType == 1 && field.bytes.size == 8) {
            ByteBuffer.wrap(field.bytes).order(ByteOrder.LITTLE_ENDIAN).double.takeIf { it.isFinite() }
        } else {
            null
        }

    /** Decodes a `google.protobuf.Timestamp` message. */
    fun timestamp(field: ProtoField): Instant? {
        if (field.wireType != 2) return null
        val parts = readFields(field.bytes) ?: return null
        val seconds = parts.firstOrNull { it.number == 1 && it.wireType == 0 }?.varint ?: return null
        val nanos = parts.firstOrNull { it.number == 2 && it.wireType == 0 }?.varint ?: 0L
        if (nanos !in 0..999_999_999L || seconds !in 0..MAX_EPOCH_SECONDS) return null
        return Instant.ofEpochSecond(seconds, nanos)
    }

    private fun readVarint(input: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = start
        while (index < input.size && index < start + 10) {
            val byte = input[index].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            index++
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private val EMPTY = ByteArray(0)
    private const val MAX_EPOCH_SECONDS = 32_503_680_000L
}
