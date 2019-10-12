package de.mathfactory.mooltipass

import de.mathfactory.mooltipass.hardware.IMooltipassCommunication
import de.mathfactory.mooltipass.hardware.MessageFactory
import de.mathfactory.mooltipass.hardware.MooltipassDevice
import de.mathfactory.mooltipass.hardware.MooltipassMessage
import kotlin.random.Random

class MpMini (comm: IMooltipassCommunication) : MooltipassDevice(comm) {
    override fun getMessageFactory(): MessageFactory {
        return object: MessageFactory {
            private val PACKET_LEN_OFFSET = 0
            private val PACKET_CMD_OFFSET = 1
            private val PACKET_DATA_OFFSET = 2
            override fun deserialize(data: Array<ByteArray>): MooltipassMessage? {
                // TODO: only first packet is considered!
                val len = data[0].get(PACKET_LEN_OFFSET).toUByte().toInt() - 1
                val cmdInt = data[0].get(PACKET_CMD_OFFSET).toUByte().toInt()

                MooltipassCommand.fromInt(cmdInt)?.let {
                    val e = kotlin.math.min(len, data[0].size)
                    //assert(e == len) // TODO
                    val d = data[0].sliceArray(PACKET_DATA_OFFSET..(e + PACKET_DATA_OFFSET))
                    return MooltipassMessage(it, d)
                }
                return null
            }

            override fun serialize(msg: MooltipassMessage): Array<ByteArray> {
                // TODO consider multi-packet messages
                val len = msg.data?.size ?: 0
                val bytes = ByteArray(2 + len)
                bytes.set(PACKET_LEN_OFFSET, len.toByte())
                bytes.set(PACKET_CMD_OFFSET, msg.cmd.cmd.toByte())
                msg.data?.copyInto(bytes, PACKET_DATA_OFFSET)
                return arrayOf(bytes)
            }
        }
    }

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
        val data = List(4) { Random.nextInt(0, 256) }
        //val data = listOf( 0xDE,0xAD,0xBE,0xEF )
        val answer = communicate(
            MooltipassMessage(
                MooltipassCommand.PING_MINI,
                data
            )
        )
        if(answer?.data?.size != data.size) return false
        for (i in 0..3) {
            if(answer.data[i] != data[i].toByte()) {
                return false
            }
        }
        return true
    }

    override fun getVersion(): String? {
        val answer = communicate(MooltipassMessage(MooltipassCommand.VERSION))
        val mem = answer?.data?.get(0)?.toUByte()?.toInt() ?: 0
        return answer?.dataAsString(1)
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

}