package com.datacall.ccp.protocol

object Crc32 {
    private val table = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
        }
        crc
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset until offset + length) {
            val index = (crc xor data[i].toInt()) and 0xFF
            crc = table[index] xor (crc ushr 8)
        }
        return crc.inv()
    }

    fun writeInt(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    fun readInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }
}
