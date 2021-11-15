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

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.*

sealed class CommOp(val status: Int, val busy: Boolean) {
    class Disconnected : CommOp(-1, true)
    class ChangeMtu : CommOp(0, true)
    class Idle : CommOp(0, false)
    class ReadRequested(status: Int) : CommOp(status, true)
    class WriteRequested(status: Int) : CommOp(status, true)
    class Read(status: Int, val value: ByteArray?) : CommOp(status, false)
    class Write(status: Int) : CommOp(status, false)
    class ChangedChar(val value: ByteArray?) : CommOp(0, false)
}

private const val DEVICE_NAME = "Mooltipass BLE"
private const val SCAN_TIMEOUT = 20000L
private const val WRITE_TIMEOUT = 20000L
private const val READ_TIMEOUT = 20000L
private const val UUID_COMM_SERVICE = "2566af2c-91bd-49fd-8ebb-020fa873044f"
private const val UUID_CHAR_READ = "4c64e90a-5f9c-4d6b-9c29-bdaa6141f9f7"
private const val UUID_CHAR_WRITE = "fe8f1a02-6311-475f-a296-553e3566b895"
private const val UUID_DESCRIPTOR_CCC = "00002902-0000-1000-8000-00805f9b34fb"
private const val MTU_BYTES = 128

private fun filter(device: BluetoothDevice) = device.name == DEVICE_NAME /* && device.bondState == BluetoothDevice.BOND_BONDED*/

private class MooltipassGatt(val gatt: BluetoothGatt) {
    fun service(): BluetoothGattService? = gatt.services.firstOrNull { it.uuid.toString() == UUID_COMM_SERVICE }
    fun characteristic(uuid: String) = service()?.characteristics?.firstOrNull { it.uuid.toString() == uuid }
    fun writeCharacteristic() = characteristic(UUID_CHAR_WRITE)
    fun readCharacteristic() = characteristic(UUID_CHAR_READ)
}

@ExperimentalCoroutinesApi
class MooltipassDevice(private val context: Context, private val device: BluetoothDevice) {
    private var mpGatt = CompletableDeferred<MooltipassGatt>()

    private suspend fun waitBusy() {
        commFlow.first { !it.busy }
    }

    suspend fun readNotified(): ByteArray? {
        return withTimeoutOrNull(READ_TIMEOUT) {
            val r = (commFlow.firstOrNull { it is CommOp.ChangedChar } as CommOp.ChangedChar).value
            commFlow.value = CommOp.Idle()
            r
        }
    }

    suspend fun readGatt(): ByteArray? {
        waitBusy()
        val mp = mpGatt.await()
        mp.readCharacteristic()?.let { c ->
            mp.gatt.readCharacteristic(c)
        } ?: return null
        commFlow.value = CommOp.ReadRequested(0)

        return withTimeoutOrNull(READ_TIMEOUT) {
            (commFlow.firstOrNull { it is CommOp.Read } as CommOp.Read).value
        }
    }

    suspend fun flushRead(): ByteArray? {
        var pp:ByteArray? = null
        var p = readGatt()
        while (!p.contentEquals(pp)) {
            pp = p
            p = readGatt()
        }
        return p
    }

    suspend fun send(pkts: Array<ByteArray>): Int? {
        var r:Int? = null
        for(pkt in pkts) {
            r = send(pkt)
            if(r != 0) return r
        }
        return r
    }

    suspend fun send(pkt: ByteArray): Int? {
        waitBusy()
        val mp = mpGatt.await()
        mp.writeCharacteristic()?.let { c ->
            c.value = pkt
            if(!mp.gatt.writeCharacteristic(c)) return null
        } ?: return null

        commFlow.value = CommOp.WriteRequested(0)
        return withTimeoutOrNull(WRITE_TIMEOUT) {
            (commFlow.first { it is CommOp.Write } as CommOp.Write).status
        }
    }

    suspend fun waitForChange(): CommOp.ChangedChar? {
        return withTimeoutOrNull(READ_TIMEOUT) {
            commFlow.firstOrNull { it is CommOp.ChangedChar } as CommOp.ChangedChar
        }
    }

    suspend fun readMessage(): Array<ByteArray>? {
        val pkt = readNotified() ?: return null
        val nPkts = (pkt[1].toUByte().toInt() % 16) + 1
        val id = pkt[1].toUByte().toInt() shr 4
        if(id != 0) {
            Log.e("Mooltifill", "First packet should have id 0, but was $id")
            return null
        }
        return arrayOf(pkt) + (1 until nPkts).map { readNotified() ?: return null }.toTypedArray()
    }

    suspend fun communicate(pkt: Array<ByteArray>): Array<ByteArray>? {
        // flush all pending messages TODO is this necessary?
        flushRead() ?: return null
        send(pkt) ?: return null
        //waitForChange() ?: return null
        return readMessage()

    }

    private val commFlow: MutableStateFlow<CommOp> = MutableStateFlow(CommOp.Disconnected())

    fun connect(scope: CoroutineScope) = scope.launch {
        callbackFlow<CommOp> {
            val cb = object : BluetoothGattCallback() {
                private var mtuRequested: Boolean = false

                fun ByteArray.toHexString() : String {
                    return this.joinToString("") {
                        java.lang.String.format("%02x", it)
                    }
                }

                override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                    if(SettingsActivity.isDebugEnabled(context)) {
                        Log.d("Mooltifill", "onCharacteristicRead  $status " + characteristic?.value?.toHexString())
                    }
                    trySend(CommOp.Read(status, characteristic?.value))
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int
                ) {
                    if(SettingsActivity.isDebugEnabled(context)) {
                        Log.d("Mooltifill", "onCharacteristicWrite $status " + characteristic?.value?.toHexString())
                    }
                    trySend(CommOp.Write(status))
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    if(SettingsActivity.isDebugEnabled(context)) {
                        Log.d("Mooltifill", "onCharacteristicChanged " + characteristic?.value?.toHexString())
                    }
                    trySend(CommOp.ChangedChar(characteristic?.value))
                }

                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if(SettingsActivity.isDebugEnabled(context)) {
                        Log.d("Mooltifill", "onConnectionStateChange $status $newState")
                    }
                    when(status) {
                        BluetoothGatt.GATT_SUCCESS ->
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED ->
                                    gatt?.discoverServices()
                                BluetoothProfile.STATE_DISCONNECTED ->
                                    channel.close()
                            }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    if(mtuRequested) {
                        mtuRequested = false
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            trySend(CommOp.Idle())
                        } else {
                            Log.e("Mooltifill", "gatt.requestMtu() failed, giving up: $status")
                            channel.close()
                        }
                    }
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt?,
                    descriptor: BluetoothGattDescriptor?,
                    status: Int
                ) {
                    if(SettingsActivity.isDebugEnabled(context)) {
                        Log.d("Mooltifill", "onDescriptorWrite")
                    }
                    if (descriptor?.uuid.toString() == UUID_DESCRIPTOR_CCC) {
                        if (status == BluetoothGatt.GATT_SUCCESS && gatt?.requestMtu(MTU_BYTES) == true) {
                            mtuRequested = true
                            trySend(CommOp.ChangeMtu())
                        } else {
                            Log.e("Mooltifill", "gatt.requestMtu() or requestNotifications() failed, giving up: $status")
                            channel.close()
                        }
                    }
                }

                fun requestNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
                    if (!gatt.setCharacteristicNotification(char, true)) return false
                    val d = char.getDescriptor(UUID.fromString(UUID_DESCRIPTOR_CCC))
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    return gatt.writeDescriptor(d)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if(SettingsActivity.isDebugEnabled(context)) {
                        Log.d("Mooltifill", "onServiceDiscover $status")
                    }
                    if(gatt != null && status == 0) {
                        gatt.services.firstOrNull {it.uuid.toString() == UUID_COMM_SERVICE}?.let {
                            val mp = MooltipassGatt(gatt)
                            this@MooltipassDevice.mpGatt.complete(mp)
                            mp.readCharacteristic()?.let { read_char ->
                                launch {
                                    for(i in 1..20) {
                                        if(!requestNotifications(gatt, read_char)) {
                                            Log.w("Mooltifill", "requestNotifications() failed [$i]")
                                            delay(500L)
                                        } else return@launch
                                    }
                                    Log.e("Mooltifill", "failed to subscribe to notifications")
                                    channel.close()
                                }
                            }
                        }
                    }
                }
            }
            val gatt = device.connectGatt(context, true, cb, BluetoothDevice.TRANSPORT_LE)
            awaitClose {
                gatt.disconnect()
            }
        }.collect {
            if(SettingsActivity.isDebugEnabled(context)) {
                Log.d("Mooltifill", "commFlow $it")
            }
            commFlow.emit(it)
        }
    }

    suspend fun disconnect() {
        mpGatt.await().gatt.disconnect()
    }

    @FlowPreview
    companion object {
        suspend fun connect(context: Context): MooltipassDevice? {
            val device = MooltipassScan().deviceFlow(context)
                .firstOrNull { it.bondState == BluetoothDevice.BOND_BONDED }
                ?: return null

            val dev = MooltipassDevice(context, device)
            dev.connect(CoroutineScope(Dispatchers.IO))
            return dev
        }
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
class MooltipassScan {

    private fun pairedDevice(context: Context): BluetoothDevice? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter.bondedDevices.firstOrNull(::filter)
    }

    fun deviceFlow(context: Context): Flow<BluetoothDevice> {
        return pairedDevice(context)
            ?.let(::flowOf) // use paired device, if available...
            ?: emptyFlow() // do not scan, as paired device is necessary
//            ?:scanFlow(context).map(ScanResult::getDevice) // ... else scan devices
    }

//    private fun scanFlow(context: Context): Flow<ScanResult> {
//        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
//        return bluetoothManager.adapter.bluetoothLeScanner
//            ?.let(::scanFlow)
//            ?:emptyFlow() // Bluetooth not enabled?
//    }
//
//    private fun scanFlow(scanner: BluetoothLeScanner): Flow<ScanResult> = callbackFlow {
//        val cb = object : ScanCallback() {
//
//            private fun handleScanResult(result: ScanResult) {
//                if(filter(result.device ?: return)) {
//                    trySend(result)
//                }
//            }
//
//            override fun onScanResult(callbackType: Int, result: ScanResult?) {
//                handleScanResult(result ?: return)
//            }
//
//            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
//                results?.forEach(::handleScanResult)
//            }
//
//            override fun onScanFailed(errorCode: Int) = cancel("Scan failed: $errorCode")
//
//        }
//
//        scanner.startScan(listOf(ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()), ScanSettings.Builder().build(), cb)
//        launch {
//            delay(SCAN_TIMEOUT)
//            channel.close()
//        }
//
//        awaitClose {
//            scanner.stopScan(cb)
//        }
//    }
}
