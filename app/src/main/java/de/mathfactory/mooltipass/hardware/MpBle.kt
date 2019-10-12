package de.mathfactory.mooltipass

import de.mathfactory.mooltipass.hardware.IMooltipassCommunication
import de.mathfactory.mooltipass.hardware.MessageFactory
import de.mathfactory.mooltipass.hardware.MooltipassDevice
import de.mathfactory.mooltipass.hardware.MooltipassMessage
import java.lang.RuntimeException
import kotlin.random.Random

class MpBle (comm: IMooltipassCommunication) : MooltipassDevice(comm) {

    var flip = false

    override fun getMessageFactory(): MessageFactory {
        return object: MessageFactory {
            private val HID_HEADER_SIZE = 2
            private val PACKET_LEN_OFFSET = 2
            private val PACKET_CMD_OFFSET = 0
            private val PACKET_DATA_OFFSET = 4
            private val LAST_MESSAGE_ACK_FLAG = 0x40
            private val HID_PACKET_DATA_PAYLOAD = 62
            override fun deserialize(data: Array<ByteArray>): MooltipassMessage? {
                // TODO: only first packet is considered!
                val len = data[0].get(HID_HEADER_SIZE + PACKET_LEN_OFFSET).toUByte().toInt() - 1
                + (data[0].get(HID_HEADER_SIZE + PACKET_LEN_OFFSET + 1).toUByte().toInt() shl 8)
                val cmdInt = data[0].get(HID_HEADER_SIZE + PACKET_CMD_OFFSET).toUByte().toInt()
                + (data[0].get(HID_HEADER_SIZE + PACKET_CMD_OFFSET + 1).toUByte().toInt() shl 8)

                MooltipassCommand.fromInt(cmdInt)?.let {
                    val e = kotlin.math.min(len, data[0].size)
                    //assert(e == len) // TODO
                    val d = data[0].sliceArray(HID_HEADER_SIZE + PACKET_DATA_OFFSET..(e + HID_HEADER_SIZE + PACKET_DATA_OFFSET))
                    return MooltipassMessage(it, d)
                }
                return null
            }

            override fun serialize(msg: MooltipassMessage): Array<ByteArray> {
                // TODO consider multi-packet messages
                val id = 0
                val nPkts = 1
                val len = msg.data?.size ?: 0
                val bytes = ByteArray(6 + len)
                val ack = 0x00
                // TODO: ack / msg flip bit
                val flipbit = if(flip) 0x80 else 0x00
                flip = !flip
                bytes.set(0, (flipbit + ack + 4 + len).toByte())
                bytes.set(1, (id shl 4 + (nPkts - 1)).toByte())
                bytes.set(HID_HEADER_SIZE + PACKET_LEN_OFFSET, (len and 255).toByte())
                bytes.set(HID_HEADER_SIZE + PACKET_LEN_OFFSET + 1, (len shr 8).toByte())
                bytes.set(HID_HEADER_SIZE + PACKET_CMD_OFFSET, (msg.cmd.cmd and 255).toByte())
                bytes.set(HID_HEADER_SIZE + PACKET_CMD_OFFSET + 1, (msg.cmd.cmd shr 8).toByte())
                msg.data?.copyInto(bytes, HID_HEADER_SIZE + PACKET_DATA_OFFSET)
                return arrayOf(bytes)
            }
        }
    }

    val FLIP_BIT_RESET_PACKET = byteArrayOf(0xFF.toByte(), 0xFF.toByte())

    internal fun intCommand(cmd: MooltipassCommand, arg: String): Int {
        val answer = communicate(MooltipassMessage(cmd, arg))
        return answer?.data?.get(0)?.toUByte()?.toInt() ?: -1
    }

    internal fun boolCommand(cmd: MooltipassCommand, arg: String): Boolean {
        val answer = intCommand(cmd, arg)
        return answer == 1
    }

    internal fun stringCommand(cmd: MooltipassCommand): String? {
        val answer = communicate(MooltipassMessage(cmd))
        return answer?.dataAsString()
    }

    override fun sendPing(): Boolean {
        comm.acquire()
        try {
            comm.transmit(FLIP_BIT_RESET_PACKET)
            flip = false
            val data = List(4) { Random.nextInt(0, 256) }
            //val data = listOf( 0xDE,0xAD,0xBE,0xEF )
            val answer = communicate(
                MooltipassMessage(
                    MooltipassCommand.PING_BLE,
                    data
                ), false
            )
            if (answer?.data?.size != data.size) return false
            for (i in 0..3) {
                if (answer.data[i] != data[i].toByte()) {
                    return false
                }
            }
        } finally {
            comm.release()
        }
        return true
    }

    override fun getVersion(): String? {
        val answer = communicate(MooltipassMessage(MooltipassCommand.GET_PLAT_INFO_BLE))
        if(answer?.data == null) return null
        if(answer.data.size < 12) return null
        val serial = answer.data.slice(8..11).toByteArray()
        return serial.toHexString()
    }

    override fun setContext(context: String): Int {
        return intCommand(MooltipassCommand.CONTEXT, context)
    }

    override fun getLogin(): String? {
        return stringCommand(MooltipassCommand.GET_LOGIN)
    }

    override fun getPassword(): String? {
        return stringCommand(MooltipassCommand.GET_PASSWORD)
    }

    override fun setLogin(login: String): Boolean {
        return boolCommand(MooltipassCommand.SET_LOGIN, login)
    }

    override fun setPassword(password: String): Boolean {
        return boolCommand(MooltipassCommand.SET_PASSWORD, password)
    }

    override fun addContext(context: String): Boolean {
        return boolCommand(MooltipassCommand.ADD_CONTEXT, context)
    }

    fun ByteArray.toHexString() : String {
        return this.joinToString("") {
            java.lang.String.format("%02x", it)
        }
    }
}