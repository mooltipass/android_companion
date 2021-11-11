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

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

class MooltifillActivity : AppCompatActivity() {

    @ExperimentalCoroutinesApi
    @FlowPreview
    companion object {
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_ID = "extra_id"
        const val EXTRA_SAVE = "extra_save"
        private const val SERVICE_NAME = "android-autofill"

        private suspend fun connect(context: Context): MooltipassDevice? {
            val device =
                MooltipassScan().deviceFlow(context).firstOrNull { it.bondState == BluetoothDevice.BOND_BONDED }

            val dev = MooltipassDevice(context, device ?: return null)
            dev.connect(CoroutineScope(Dispatchers.IO))
            return dev
        }

        private suspend fun getPassword(context: Context, query: String): String? {
            if(query.isBlank()) return null
            val f = BleMessageFactory()
            val device = connect(context) ?: return null // "Mooltipass device not accessible"
            device.send(MooltipassPayload.FLIP_BIT_RESET_PACKET)
            val credGet = MooltipassMessage(MooltipassCommand.GET_CREDENTIAL_BLE, MooltipassPayload.getCredentials(
                SERVICE_NAME, query))
            val credGetAnswer = device.communicate(f.serialize(credGet))?.let(f::deserialize)
            if(MooltipassCommand.GET_CREDENTIAL_BLE != credGetAnswer?.cmd) return null // "Reading failed"
            if(credGetAnswer.data?.isEmpty() == true) return null // "No item found"
            val pass = credGetAnswer.data?.let { MooltipassPayload.answerGetCredentials(query, it) }
            return pass?.password
        }

        suspend fun setPassword(context: Context, query: String, pass: String): Boolean {
            if(query.isBlank()) return false
            val f = BleMessageFactory()
            val device = connect(context) ?: return false // "Mooltipass device not accessible"
            device.send(MooltipassPayload.FLIP_BIT_RESET_PACKET)
            val cred = MooltipassMessage(MooltipassCommand.STORE_CREDENTIAL_BLE, MooltipassPayload.storeCredentials(SERVICE_NAME, query, null, null, pass))
            val credAnswer = device.communicate(f.serialize(cred))?.let(f::deserialize)

            if(MooltipassCommand.STORE_CREDENTIAL_BLE != credAnswer?.cmd) return false // "Command failed"
            if(credAnswer.data?.size != 1) return false
            if(credAnswer.data[0].toInt() != 1) return false // "Item save failed"

            return true
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mooltifill)
        intent?.getStringExtra(EXTRA_QUERY)?.let { query ->
            val save = intent?.getBooleanExtra(EXTRA_SAVE, false) ?: false
            if (save) {

            } else /* query */ {
                findViewById<TextView>(R.id.textView)?.text = "Query: " + (query ?: "<?>")
                CoroutineScope(Dispatchers.IO).launch {
                    val reply = getPassword(applicationContext, query)
                    sendReply(reply)
                }
            }
        }
    }

    private fun sendReply(reply: String?) {
        if(reply == null) {
            setResult(RESULT_CANCELED)
            finish()
        } else {
            val myIntent = intent
            val replyIntent = Intent()
            myIntent.getParcelableExtra<AutofillId>(EXTRA_ID)?.let { id ->
                val builder = Dataset.Builder().setValue(id, AutofillValue.forText(reply))
                replyIntent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build())

                setResult(RESULT_OK, replyIntent)
                finish()
            }
        }
    }
}
