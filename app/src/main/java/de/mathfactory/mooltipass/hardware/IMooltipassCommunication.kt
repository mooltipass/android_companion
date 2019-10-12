package de.mathfactory.mooltipass.hardware

interface IMooltipassCommunication {
    fun acquire()
    fun transmit(pkt: ByteArray)
    fun receive(): ByteArray?
    fun release()
    fun getPktSize(): Int
}