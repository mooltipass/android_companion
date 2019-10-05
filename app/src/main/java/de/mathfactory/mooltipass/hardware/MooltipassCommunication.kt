package de.mathfactory.mooltipass.hardware

import android.content.Context

abstract class MooltipassCommunication(val ctx: Context) : IMooltipassCommunication {

    override fun communicate(pkt: MooltipassPacket): MooltipassPacket? {
        acquire()
        transmit(pkt)
        val result = receive()
        release()
        return result
    }
}