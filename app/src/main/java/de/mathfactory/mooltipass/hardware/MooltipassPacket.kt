package de.mathfactory.mooltipass.hardware

import de.mathfactory.mooltipass.MooltipassCommand
import java.nio.charset.Charset

data class MooltipassPacket(val cmd: MooltipassCommand, val data: ByteArray?) {
    constructor(cmd: MooltipassCommand) : this(cmd, null)
    constructor(cmd: MooltipassCommand, s: String) : this(cmd, (s + Char.MIN_VALUE).toByteArray(
        Charset.forName(
            "ASCII"
        )
    ))
    constructor(cmd: MooltipassCommand, vararg ints: Int) : this(cmd, ByteArray(ints.size) { pos -> ints[pos].toByte() })
    constructor(cmd: MooltipassCommand, ints: List<Int>) : this(cmd, ByteArray(ints.size) { pos -> ints[pos].toByte() })

    companion object {
        private val PACKET_LEN_OFFSET = 0
        private val PACKET_CMD_OFFSET = 1
        private val PACKET_DATA_OFFSET = 2
        @ExperimentalUnsignedTypes
        fun fromData(rcvData: ByteArray): MooltipassPacket? {
            val len = rcvData.get(PACKET_LEN_OFFSET).toUByte().toInt() - 1
            val cmdInt = rcvData.get(PACKET_CMD_OFFSET).toUByte().toInt()

            MooltipassCommand.fromInt(cmdInt)?.let {
                val e = kotlin.math.min(len, rcvData.size)
                //assert(e == len) // TODO
                val data = rcvData.sliceArray(PACKET_DATA_OFFSET..(e + PACKET_DATA_OFFSET))
                return MooltipassPacket(it, data)
            }
            return null
        }
    }

    fun toByteArray(): ByteArray{
        val len = data?.size ?: 0
        val bytes = ByteArray(2 + len)
        bytes.set(0, len.toByte())
        bytes.set(1, cmd.cmd.toByte())
        data?.copyInto(bytes, 2)
        return bytes
    }

    fun dataAsString(start: Int = 0) : String? {
        return data?.dropLast(1)?.drop(start)?.toByteArray()?.toString(Charset.forName("ASCII"))
    }
}