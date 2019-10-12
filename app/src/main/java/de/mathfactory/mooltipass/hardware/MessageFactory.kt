package de.mathfactory.mooltipass.hardware

interface MessageFactory {
    fun deserialize(data: Array<ByteArray>): MooltipassMessage?
    fun serialize(msg: MooltipassMessage): Array<ByteArray>
}