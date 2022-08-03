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

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.PermissionChecker
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.random.Random


/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(Parameterized::class)
class MooltifillInstrumentedTest(private val useAwarenessService: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun data() = listOf(
            arrayOf(true),
            arrayOf(false),
        )

    }

//    @get:org.junit.Rule
//    var bluetoothPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
//        android.Manifest.permission.ACCESS_FINE_LOCATION,
//        android.Manifest.permission.ACCESS_COARSE_LOCATION)


//    @Before
//    fun grantPhonePermission() {
//        // In M+, trying to call a number will trigger a runtime dialog. Make sure
//        // the permission is granted before running this test.
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
//                getApplicationContext<Context>().packageName,
//                        "android.permission.ACCESS_FINE_LOCATION")
//        }
//    }

    @Before
    fun before() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        for (p in listOf(
            //Manifest.permission.ACCESS_COARSE_LOCATION,
            //Manifest.permission.ACCESS_FINE_LOCATION,
            //Manifest.permission.BLUETOOTH_SCAN,
            //Manifest.permission.BLUETOOTH_CONNECT,
            //Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH,

            )) {
            assertEquals(
                p,
                PackageManager.PERMISSION_GRANTED,
                PermissionChecker.checkSelfPermission(appContext, p)
            )
        }

        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        assert(bluetoothManager.adapter.isEnabled)
    }

    private suspend fun connect(): MooltipassDevice {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        return if(useAwarenessService) {
            val device = AwarenessService.mooltipassDevice(appContext, 2)
            assertNotNull(device)
            device!!
        } else {
            val device = MooltipassScan.devices(appContext).firstOrNull()
            assertNotNull("Expected to find ble device", device)
            assert(device?.bondState == BluetoothDevice.BOND_BONDED) {"Device not bonded"}
            MooltipassDevice(device!!, 2).also { it.connect(CoroutineScope(Dispatchers.IO), appContext) }
        }
    }

    @FlowPreview
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testDeviceAccess() = runBlocking {
        val dev = connect()
        val f = BleMessageFactory()
        //assertEquals(0, dev.send(MooltipassPayload.FLIP_BIT_RESET_PACKET))
        val random = List(4) { Random.nextInt(0, 256) }
        val ping = MooltipassMessage(MooltipassCommand.PING_BLE, random)
        val answer = dev.communicate(f, ping)
        assertEquals(MooltipassCommand.PING_BLE, answer?.cmd)
        assertArrayEquals(ping.data, answer?.data)

        dev.close()
    }

    @FlowPreview
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi

    @Test
    fun testCredentials() = runBlocking {
        val dev = connect()
        val f = BleMessageFactory()
        //assertEquals(0, dev.send(MooltipassPayload.FLIP_BIT_RESET_PACKET))
        val pw = ByteArray(32) {Random.nextInt(0, 256).toByte()}.toHexString()
        val cred = MooltipassMessage(MooltipassCommand.STORE_CREDENTIAL_BLE, MooltipassPayload.storeCredentials("test", "login", null, null, pw))
        val credAnswer = dev.communicate(f, cred)
        assertEquals(MooltipassCommand.STORE_CREDENTIAL_BLE, credAnswer?.cmd)
        assertArrayEquals(ByteArray(1) { 1 }, credAnswer?.data)

        val credGet = MooltipassMessage(MooltipassCommand.GET_CREDENTIAL_BLE, MooltipassPayload.getCredentials("test", "login"))
        val credGetAnswer = dev.communicate(f, credGet)
        assertEquals(MooltipassCommand.GET_CREDENTIAL_BLE, credGetAnswer?.cmd)
        assertEquals(pw, credGetAnswer?.data?.let { MooltipassPayload.answerGetCredentials("test", it)?.password})
        dev.close()
    }

    @FlowPreview
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testParallel() = runBlocking {
        val dev = connect()
        (0 until 5).map {
            launch {
                val f = BleMessageFactory()
                //assertEquals(0, dev.send(MooltipassPayload.FLIP_BIT_RESET_PACKET))
                val req = MooltipassMessage(MooltipassCommand.MOOLTIPASS_STATUS_BLE)
                val answer = dev.communicate(f, req)
                assertEquals(MooltipassCommand.MOOLTIPASS_STATUS_BLE, answer?.cmd)
            }
        }.joinAll()
        dev.close()
    }

    fun ByteArray.toHexString() : String = this.joinToString("") {
        java.lang.String.format("%02x", it)
    }

}

