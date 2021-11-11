/*
 * Copyright (C) 2021 Bernhard Rauch.
 *
 * This file is part of Mooltifill.
 *
 * Mooltifill is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Mooltifill is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mooltifill.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.mathfactory.mooltifill

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
