package de.mathfactory.mooltipass

import de.mathfactory.mooltipass.hardware.IMooltipassCommunication
import de.mathfactory.mooltipass.hardware.MooltipassDevice
import de.mathfactory.mooltipass.hardware.MooltipassPacket
import java.lang.RuntimeException
import kotlin.random.Random

class MpBle (val comm: IMooltipassCommunication) : MooltipassDevice() {

    val LAST_MESSAGE_ACK_FLAG = 0x40
    val FLIP_BIT_RESET_PACKET = listOf(0xFF, 0xFF)

    internal fun intCommand(cmd: MooltipassCommand, arg: String): Int {
        val answer = comm.communicate(MooltipassPacket(cmd, arg))
        return answer?.data?.get(0)?.toUByte()?.toInt() ?: -1
    }

    internal fun boolCommand(cmd: MooltipassCommand, arg: String): Boolean {
        val answer = intCommand(cmd, arg)
        return answer == 1
    }

    internal fun stringCommand(cmd: MooltipassCommand): String? {
        val answer = comm.communicate(MooltipassPacket(cmd))
        return answer?.dataAsString()
    }

    override fun sendPing(): Boolean {
        // TODO
        throw RuntimeException("TODO")
    }

    override fun getVersion(): String? {
        val answer = comm.communicate(MooltipassPacket(MooltipassCommand.VERSION))
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