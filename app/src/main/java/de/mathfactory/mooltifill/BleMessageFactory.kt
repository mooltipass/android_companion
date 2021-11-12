/*
 * Copyright (C) 2021 Bernhard Rauch.
 *
 * This file is part of Mooltifill.
 *
 * Mooltifill is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Mooltifill is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mooltifill.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.mathfactory.mooltifill

import android.util.Log
import kotlin.math.min

private const val HID_HEADER_SIZE = 2
private const val PACKET_CMD_OFFSET = 0
private const val PACKET_LEN_OFFSET = 2
private const val PACKET_DATA_OFFSET = 4
private const val LAST_MESSAGE_ACK_FLAG = 0x40
private const val HID_PACKET_SIZE = 64
private const val HID_PACKET_DATA_PAYLOAD = HID_PACKET_SIZE - HID_HEADER_SIZE
private const val MP_PACKET_DATA_PAYLOAD = HID_PACKET_DATA_PAYLOAD - PACKET_DATA_OFFSET

class BleMessageFactory : MessageFactory {

    var flip = false

    override fun deserialize(data: Array<ByteArray>): MooltipassMessage? {
        val nPkts = (data[0][1].toUByte().toInt() % 16) + 1
        val id = data[0][1].toUByte().toInt() shr 4
        if(nPkts != data.size) {
            Log.e("Mooltifill", "Wrong number of reported packages: $nPkts != ${data.size}")
            return null
        }
        val len = getShort(data[0], HID_HEADER_SIZE + PACKET_LEN_OFFSET)
        val cmdInt = getShort(data[0], HID_HEADER_SIZE + PACKET_CMD_OFFSET)
        val hidPayload = data.fold(ByteArray(0)) {a, chunk -> a + chunk.sliceArray(2 until 64)}
        if(len > hidPayload.size - PACKET_DATA_OFFSET) {
            Log.e("Mooltifill", "Not enough data for reported length: $len > ${hidPayload.size - PACKET_DATA_OFFSET}")
            return null
        }
        MooltipassCommand.fromInt(cmdInt)?.let {
            val d = hidPayload.sliceArray(PACKET_DATA_OFFSET until (len + PACKET_DATA_OFFSET))
            return MooltipassMessage(it, d)
        }
        return null
    }

    companion object {
        fun setShort(bytes: ByteArray, index: Int, v: Int) {
            bytes[index] = (v and 255).toByte()
            bytes[index + 1] = ((v shr 8) and 255).toByte()
        }

        fun getShort(bytes: ByteArray, index: Int): Int =
            bytes[index].toUByte().toInt() + (bytes[index + 1].toUByte().toInt() shl 8)

        fun strlenutf16(bytes: ByteArray): Int? {
            for (i in 0..bytes.size step 2) {
                if(getShort(bytes, i) == 0) return i
            }
            return null
        }

        fun chunks(bytes: ByteArray?, chunkSize: Int): Array<ByteArray> = bytes?.let { bytes ->
            (0..((bytes.size - 1) / chunkSize)).map { i ->
                bytes.copyOfRange(i * chunkSize, min(bytes.size, (i + 1) * chunkSize))
            }
        }?.toTypedArray() ?: arrayOf(ByteArray(0))

    }

    override fun serialize(msg: MooltipassMessage): Array<ByteArray> {
        val len = msg.data?.size ?: 0
        // TODO: ack
        val ack = 0x00
        val flipbit = if(flip) 0x80 else 0x00
        flip = !flip
        val hidPayload = ByteArray(len + PACKET_DATA_OFFSET)
        setShort(hidPayload, PACKET_CMD_OFFSET, msg.cmd.cmd)
        setShort(hidPayload, PACKET_LEN_OFFSET, len)
        msg.data?.copyInto(hidPayload, PACKET_DATA_OFFSET)
        val chunks = chunks(hidPayload, HID_PACKET_DATA_PAYLOAD)
        val nPkts = chunks.size
        return chunks.mapIndexed() { id, chunk ->
            val bytes = ByteArray(HID_PACKET_SIZE)
            bytes[0] = (flipbit + ack + chunk.size).toByte()
            bytes[1] = ((id shl 4) + (nPkts - 1)).toByte()
            chunk.copyInto(bytes, HID_HEADER_SIZE)
            bytes
        }.toTypedArray()
    }
}
