package de.mathfactory.mooltipass.hardware

interface IMooltipassCommunication {
    fun acquire()
    fun transmit(pkt: MooltipassPacket)
    fun receive(): MooltipassPacket?
    fun release()
    fun communicate(pkt: MooltipassPacket): MooltipassPacket?
}