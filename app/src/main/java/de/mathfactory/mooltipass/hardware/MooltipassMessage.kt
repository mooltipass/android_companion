package de.mathfactory.mooltipass.hardware

import de.mathfactory.mooltipass.MooltipassCommand
import java.nio.charset.Charset

data class MooltipassMessage(val cmd: MooltipassCommand, val data: ByteArray?) {
    constructor(cmd: MooltipassCommand) : this(cmd, null)
    constructor(cmd: MooltipassCommand, s: String) : this(cmd, (s + Char.MIN_VALUE).toByteArray(
        Charset.forName(
            "ASCII"
        )
    ))
    constructor(cmd: MooltipassCommand, vararg ints: Int) : this(cmd, ByteArray(ints.size) { pos -> ints[pos].toByte() })
    constructor(cmd: MooltipassCommand, ints: List<Int>) : this(cmd, ByteArray(ints.size) { pos -> ints[pos].toByte() })

    fun dataAsString(start: Int = 0) : String? {
        return data?.dropLast(1)?.drop(start)?.toByteArray()?.toString(Charset.forName("ASCII"))
    }
}