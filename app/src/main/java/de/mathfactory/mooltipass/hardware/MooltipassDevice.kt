package de.mathfactory.mooltipass.hardware

abstract class MooltipassDevice(val comm: IMooltipassCommunication) : IMooltipassDevice {
    fun communicate(msg: MooltipassMessage, acquire: Boolean = true): MooltipassMessage? {
        if(acquire) comm.acquire()
        val pkts = getMessageFactory().serialize(msg)
        for(pkt in pkts) {
            comm.transmit(pkt)
        }
        val result = comm.receive()
        if(result == null) return null
        if(acquire) comm.release()
        return getMessageFactory().deserialize(arrayOf(result))
    }
}