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

import java.util.Calendar
import java.util.TimeZone

data class Credentials(val service: String, val login: String?, val description: String?, val third: String?, val password: String?)

class MooltipassPayload {
    companion object {
        val FLIP_BIT_RESET_PACKET = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        private const val SIZE_DATE_DATA_FIELD = 12

        private fun tr(s: String): ByteArray = s.toByteArray(Charsets.UTF_16LE)
        private fun trr(b: ByteArray): String = b.toString(Charsets.UTF_16LE)

        fun storeCredentials(service: String, login: String?, description: String?, third: String?, password: String?): ByteArray {
            return storeCredentials(
                tr(service) + 0 + 0,
                login?.let { tr(it) + 0 + 0},
                description?.let { tr(it) + 0 + 0},
                third?.let { tr(it) + 0 + 0},
                password?.let { tr(it) + 0 + 0},
            )
        }

        fun storeCredentials(service: ByteArray, login: ByteArray?, description: ByteArray?, third: ByteArray?, password: ByteArray?): ByteArray {
            val loginOffset = service.size
            val descriptionOffset = loginOffset + (login?.size ?: 0)
            val thirdOffset = descriptionOffset + (description?.size ?: 0)
            val passwordOffset = thirdOffset + (third?.size ?: 0)
            val len = passwordOffset + (password?.size?:0) + 10
            val bytes = ByteArray(len)
            BleMessageFactory.setShort(bytes, 0, 0)
            BleMessageFactory.setShort(bytes, 2, if (login != null) { loginOffset / 2 } else { 65535 })
            BleMessageFactory.setShort(bytes, 4, if (description != null) { descriptionOffset / 2 } else { 65535 })
            BleMessageFactory.setShort(bytes, 6, if (third != null) { thirdOffset / 2 } else { 65535 })
            BleMessageFactory.setShort(bytes, 8, if (password != null) { passwordOffset / 2 } else { 65535 })
            service.copyInto(bytes, 10)
            login?.copyInto(bytes, 10 + loginOffset)
            description?.copyInto(bytes, 10 + descriptionOffset)
            third?.copyInto(bytes, 10 + thirdOffset)
            password?.copyInto(bytes, 10 + passwordOffset)
            return bytes
        }

        fun getCredentials(service: String, login: String?): ByteArray {
            return getCredentials(
                tr(service) + 0 + 0,
                login?.let { tr(it) + 0 + 0},
            )
        }

        fun getCredentials(service: ByteArray, login: ByteArray?): ByteArray {
            val loginOffset = service.size
            val len = loginOffset + (login?.size?:0) + 4
            val bytes = ByteArray(len)
            BleMessageFactory.setShort(bytes, 0, 0)
            BleMessageFactory.setShort(bytes, 2, if (login != null) { loginOffset / 2 } else { 65535 })
            service.copyInto(bytes, 4)
            login?.copyInto(bytes, 4 + loginOffset)
            return bytes
        }

        fun answerGetCredentials(service: String, data: ByteArray): Credentials? {
            fun p(idx: Int): String? {
                val offset = BleMessageFactory.getShort(data, idx) * 2 + 8
                val a = data.sliceArray(offset until data.size)
                // expect null terminated string
                return BleMessageFactory.strlenutf16(a)?.let { len ->
                    a.sliceArray(0 until len).let(::trr)
                }
            }
            return try {
                Credentials(service, p(0), p(2), p(4), p(6),)
            } catch (e: IndexOutOfBoundsException) {
                null
            }
        }

        fun tryParseIsLocked(data: ByteArray): Boolean? {
            val f = BleMessageFactory(false)
            f.deserialize(arrayOf(data))?.let { msg ->
                if(msg.cmd == MooltipassCommand.MOOLTIPASS_STATUS_BLE &&
                    msg.data != null &&
                    msg.data.size == 5) {
                    return msg.data[0].toInt() and 0x4 == 0x0
                }
            }
            return null
        }

        fun getDate(): ByteArray {
            val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

            //Ref: //https://github.com/mooltipass/minible/wiki/Mooltipass-Protocol#0x0004-set-current-date
            val bytes = ByteArray(SIZE_DATE_DATA_FIELD)
            BleMessageFactory.setShort(bytes, 0, now.get(Calendar.YEAR))
            BleMessageFactory.setShort(bytes, 2, (now.get(Calendar.MONTH) + 1))
            BleMessageFactory.setShort(bytes, 4, now.get(Calendar.DAY_OF_MONTH))
            BleMessageFactory.setShort(bytes, 6, now.get(Calendar.HOUR_OF_DAY))
            BleMessageFactory.setShort(bytes, 8, now.get(Calendar.MINUTE))
            BleMessageFactory.setShort(bytes, 10, now.get(Calendar.SECOND))

            return bytes
        }

    }
}
