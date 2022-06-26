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
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Method
import java.util.*

sealed class CommOp(val status: Int) {
    object Disconnected : CommOp(-1)
    object ChangeMtu : CommOp(0)
    object ReadRequested : CommOp(0)
    object WriteRequested : CommOp(0)
    class Read(status: Int, val value: ByteArray?) : CommOp(status)
    class Write(status: Int) : CommOp(status)
    class ChangedChar(val value: ByteArray?) : CommOp(0)
    object OperationPending : CommOp(0)
    object Idle : CommOp(0)

    override fun toString(): String {
        return this.javaClass.kotlin.simpleName + ": status=" + status
    }
}

private const val MAC_ADDRESS_BASE_VALUE = "68:79:12:3"

private const val WRITE_TIMEOUT = 20000L
private const val READ_TIMEOUT = 20000L
private const val CHANGED_CHAR_FETCH_TIMEOUT = 2000L
private const val UUID_COMM_SERVICE = "2566af2c-91bd-49fd-8ebb-020fa873044f"
private const val UUID_CHAR_READ = "4c64e90a-5f9c-4d6b-9c29-bdaa6141f9f7"
private const val UUID_CHAR_WRITE = "fe8f1a02-6311-475f-a296-553e3566b895"
private const val UUID_DESCRIPTOR_CCC = "00002902-0000-1000-8000-00805f9b34fb"
private const val MTU_BYTES = 128
private const val N_RETRIES = 5

private fun filter(device: BluetoothDevice) = device.address.startsWith(MAC_ADDRESS_BASE_VALUE,true) && isConnected(device)

private fun isConnected(device: BluetoothDevice): Boolean {
    return try {
        val method: Method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as Boolean
    } catch (e: Exception) {
        Log.e("Mooltifill", "Paired Device Checking device.isConnected() failed with reason " + e.localizedMessage)
        throw IllegalStateException(e)
    }
}

private class MooltipassGatt(val gatt: BluetoothGatt) {
    fun service(): BluetoothGattService? = gatt.services.firstOrNull { it.uuid.toString() == UUID_COMM_SERVICE }
    fun characteristic(uuid: String) = service()?.characteristics?.firstOrNull { it.uuid.toString() == uuid }
    fun writeCharacteristic() = characteristic(UUID_CHAR_WRITE)
    fun readCharacteristic() = characteristic(UUID_CHAR_READ)
}

@ExperimentalCoroutinesApi
class MooltipassDevice(private val device: BluetoothDevice, private var debug: Int) {

    private var mIsDisconnected: Boolean = false
    private var mLocked: Boolean? = null
    private var mpGatt = CompletableDeferred<MooltipassGatt>()
    private val commFlow: MutableStateFlow<CommOp> = MutableStateFlow(CommOp.Disconnected)
    private val idleMutex = Mutex()
    private var changedCharAccept = CompletableDeferred<Unit>()

    fun ByteArray.toHexString(): String = this.joinToString("") {
        java.lang.String.format("%02x", it)
    }

    suspend fun hasCommService(): Boolean = mpGatt.await().service() != null

    private fun isDebug() = debug > 0
    private fun isVerboseDebug() = debug > 1

    private suspend fun awaitCommFlow(timeout: Long, label: String, predicate: suspend (CommOp) -> Boolean): CommOp? = withTimeoutOrNull(timeout) {
        val id = (0..Int.MAX_VALUE).random()
        if (isVerboseDebug()) Log.d("Mooltifill", Integer.toHexString(id) + " " + label)
        return@withTimeoutOrNull commFlow.firstOrNull(predicate).also {
            if (isVerboseDebug()) Log.d("Mooltifill", Integer.toHexString(id) + " " + label + " = " + it?.toString())
        }
    }

    private suspend inline fun <reified T> awaitCommFlowType(timeout: Long, crossinline predicate: (CommOp) -> Boolean = { true }): T? =
        awaitCommFlow(timeout, "awaitCommFlowType(" + T::class.simpleName + ")") { it is T && predicate(it) } as T?

    private suspend fun readNotified(predicate: (CommOp) -> Boolean = { true }): CommOp.ChangedChar? {
        if (mIsDisconnected) Log.e("Mooltifill", "readNotified() with mIsDisconnected == true")
        // signal to accept CommOp.ChangedChar
        changedCharAccept.complete(Unit)
        return awaitCommFlowType<CommOp.ChangedChar>(READ_TIMEOUT, predicate)
    }

    private suspend fun readGatt(): ByteArray? {
        if(mIsDisconnected) Log.e("Mooltifill", "readGatt() with mIsDisconnected == true")
        val mp = mpGatt.await()
        mp.readCharacteristic()?.let { c ->
            mp.gatt.readCharacteristic(c)
        } ?: return null
        setCommOp(CommOp.ReadRequested)

        return awaitCommFlowType<CommOp.Read>(READ_TIMEOUT)?.value
    }

    private suspend fun flushRead(): ByteArray? {
        if(mIsDisconnected) Log.e("Mooltifill", "flushRead() with mIsDisconnected == true")
        var pp:ByteArray? = null
        var p = readGatt()
        while (!p.contentEquals(pp)) {
            pp = p
            p = readGatt()
        }
        return p
    }

    private suspend fun sendNoLock(pkts: Array<ByteArray>): Int? {
        var r:Int? = null
        for(pkt in pkts) {
            r = sendNoLock(pkt)
            if(r != 0) return r
        }
        return r
    }

    private suspend fun sendNoLock(pkt: ByteArray): Int? {
        if(mIsDisconnected) Log.e("Mooltifill", "send() with mIsDisconnected == true")
        val mp = mpGatt.await()
        mp.writeCharacteristic()?.let { c ->
            c.value = pkt
            setCommOp(CommOp.WriteRequested)
            if(!mp.gatt.writeCharacteristic(c)) return null
        } ?: return null

        return awaitCommFlowType<CommOp.Write>(WRITE_TIMEOUT)?.status
    }

    private suspend fun readMessage(): Array<ByteArray>? {
        fun nPkts(pkt: ByteArray) = (pkt[1].toUByte().toInt() % 16) + 1
        fun id(pkt: ByteArray) = pkt[1].toUByte().toInt() shr 4

        if(mIsDisconnected) Log.e("Mooltifill", "readMessage() with mIsDisconnected == true")
        val initial = readNotified() ?: return null
        val expectedPkts = nPkts(initial.value ?: return null)

        fun check(pkt: ByteArray, expectedId: Int): Boolean {
            val id = id(pkt)
            val nPkts = nPkts(pkt)
            if (id != expectedId) {
                Log.e("Mooltifill", "Packet $expectedId should have id $expectedId, but has id $id")
                return false
            }
            if (nPkts != expectedPkts) {
                Log.e("Mooltifill", "Packet count mismatch: $nPkts != $expectedPkts")
                return false
            }
            return true
        }
        if(!check(initial.value, 0)) return null
        return (1 until expectedPkts).fold(listOf(initial)) { l, expectedId ->
            val lastNotif = l.last()
            val notif = readNotified { it != lastNotif } ?: return null
            val pkt = notif.value ?: return null
            if(!check(pkt, expectedId)) return null
            l + notif
        }.map { it.value!! }.toTypedArray()
    }

    private suspend fun <T> awaitIdle(timeout: Long, op: CommOp, block: suspend () -> T?): T? = idleMutex.withLock {
        awaitCommFlowType<CommOp.Idle>(timeout) ?: run {
            Log.e("Mooltifill", "awaitIdle($timeout, $op, ...) failed with: " + commFlow.value);
            return@withLock null
        }
        setCommOp(op)
        return block().also {
            setCommOp(CommOp.Idle)
        }
    }

    private suspend fun communicateNoLock(pkt: Array<ByteArray>): Array<ByteArray>? {
        // flush all pending messages TODO is this necessary?
        flushRead() ?: return null
        sendNoLock(pkt) ?: return null
        //waitForChange() ?: return null
        return readMessage()
    }

    private suspend fun communicateNoLock(f: BleMessageFactory, msg: MooltipassMessage): MooltipassMessage? = f.serialize(msg).let {
        (0 until N_RETRIES).asFlow().map { _ ->
            communicateNoLock(it)?.let(f::deserialize)
        }.firstOrNull { it?.cmd != MooltipassCommand.PLEASE_RETRY_BLE }
    }

    suspend fun send(pkts: Array<ByteArray>): Int? = awaitIdle(WRITE_TIMEOUT, CommOp.OperationPending) {
        sendNoLock(pkts)
    }

    suspend fun send(pkt: ByteArray): Int? = awaitIdle(WRITE_TIMEOUT, CommOp.OperationPending) {
        sendNoLock(pkt)
    }

    suspend fun communicate(pkt: Array<ByteArray>): Array<ByteArray>? = awaitIdle(WRITE_TIMEOUT, CommOp.OperationPending) {
        communicateNoLock(pkt)
    }

    suspend fun communicate(f: BleMessageFactory, msg: MooltipassMessage): MooltipassMessage? = awaitIdle(WRITE_TIMEOUT, CommOp.OperationPending) {
        communicateNoLock(f, msg)
    }

    fun connect(scope: CoroutineScope, context: Context, bleCallback: BluetoothGattCallback? = null) = scope.launch {
        if(isDebug()) {
            Log.d("Mooltifill", "connect() entered")
        }
        callbackFlow<CommOp> {
            val cb = object : BluetoothGattCallback() {
                private var mtuRequested: Boolean = false

                override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                    if(mIsDisconnected) {
                        Log.e("Mooltifill", "onCharacteristicRead() with mIsDisconnected == true")
                    }
                    if(isVerboseDebug()) {
                        Log.d("Mooltifill", "onCharacteristicRead $status " + characteristic?.value?.toHexString())
                    } else if(isDebug()) {
                        Log.d("Mooltifill", "onCharacteristicRead $status")
                    }
                    trySend(CommOp.Read(status, characteristic?.value))
                    bleCallback?.onCharacteristicRead(gatt, characteristic, status)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int
                ) {
                    if(mIsDisconnected) {
                        Log.e("Mooltifill", "onCharacteristicWrite() with mIsDisconnected == true")
                    }
                    if(isVerboseDebug()) {
                        Log.d("Mooltifill", "onCharacteristicWrite $status " + characteristic?.value?.toHexString())
                    } else if(isDebug()) {
                        Log.d("Mooltifill", "onCharacteristicWrite $status")
                    }
                    trySend(CommOp.Write(status))
                    bleCallback?.onCharacteristicWrite(gatt, characteristic, status)
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    if(mIsDisconnected) {
                        Log.e("Mooltifill", "onCharacteristicChanged() with mIsDisconnected == true")
                    }
                    if(isVerboseDebug()) {
                        Log.d("Mooltifill", "onCharacteristicChanged " + characteristic?.value?.toHexString())
                    } else if(isDebug()) {
                        Log.d("Mooltifill", "onCharacteristicChanged")
                    }
                    trySend(CommOp.ChangedChar(characteristic?.value))
                    bleCallback?.onCharacteristicChanged(gatt, characteristic)
                    // be aware of lock status
                    characteristic?.value?.let { data ->
                        MooltipassPayload.tryParseIsLocked(data)?.let {
                            mLocked = it
                        }
                    }
                }

                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if(mIsDisconnected) {
                        Log.e("Mooltifill", "onConnectionStateChange() with mIsDisconnected == true")
                    }
                    if(isDebug()) {
                        Log.d("Mooltifill", "onConnectionStateChange $status $newState")
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED ->
                            when(status) {
                                BluetoothGatt.GATT_SUCCESS ->
                                    gatt?.discoverServices()
                            }
                        BluetoothProfile.STATE_DISCONNECTED ->
                            channel.close()
                    }
                    bleCallback?.onConnectionStateChange(gatt, status, newState)
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    if(mtuRequested) {
                        mtuRequested = false
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            trySend(CommOp.Idle)
                        } else {
                            Log.e("Mooltifill", "gatt.requestMtu() failed, giving up: $status")
                            channel.close()
                        }
                    }
                    bleCallback?.onMtuChanged(gatt, mtu, status)
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt?,
                    descriptor: BluetoothGattDescriptor?,
                    status: Int
                ) {
                    if(isDebug()) {
                        Log.d("Mooltifill", "onDescriptorWrite")
                    }
                    if (descriptor?.uuid.toString() == UUID_DESCRIPTOR_CCC) {
                        if (status == BluetoothGatt.GATT_SUCCESS && gatt?.requestMtu(MTU_BYTES) == true) {
                            mtuRequested = true
                            trySend(CommOp.ChangeMtu)
                        } else {
                            Log.e("Mooltifill", "gatt.requestMtu() or requestNotifications() failed, giving up: $status")
                            channel.close()
                        }
                    }
                    bleCallback?.onDescriptorWrite(gatt, descriptor, status)
                }

                fun requestNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
                    if (!gatt.setCharacteristicNotification(char, true)) return false
                    val d = char.getDescriptor(UUID.fromString(UUID_DESCRIPTOR_CCC))
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    return gatt.writeDescriptor(d)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if(isDebug()) {
                        Log.d("Mooltifill", "onServiceDiscover $status")
                    }
                    if(gatt != null && status == 0) {
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
                    bleCallback?.onServicesDiscovered(gatt, status)
                }
            }
            mIsDisconnected = false
            val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            awaitClose {
                if(isDebug()) {
                    Log.d("Mooltifill", "awaitClose")
                }
                close(gatt)
            }
        }.collect {
            if(it is CommOp.ChangedChar) {
                // wait period for receiver to register for ChangedChar
                withTimeoutOrNull(CHANGED_CHAR_FETCH_TIMEOUT) {
                    changedCharAccept.await()
                    true
                } ?: return@collect // do not propagate ChangedChar if there is no receiver listening
                changedCharAccept = CompletableDeferred()
            }
            setCommOp(it)
        }
        if(isDebug()) {
            Log.d("Mooltifill", "connect() finished")
        }
    }

    private suspend fun setCommOp(op: CommOp) {
        if (isDebug()) {
            Log.d("Mooltifill", "commFlow $op")
        }
        commFlow.emit(op)
    }

    private fun close(gatt: BluetoothGatt) {
        if(isDebug()) {
            Log.d("Mooltifill", "disconnect()")
        }
        gatt.also(BluetoothGatt::disconnect).close()
        mIsDisconnected = true
    }

    suspend fun close() {
        close(mpGatt.await().gatt)
    }

    fun setDebug(debug: Int) {
        this.debug = debug
    }

    fun isLocked(): Boolean? {
        return mLocked
    }

    fun isDisconnected(): Boolean = mIsDisconnected

    @FlowPreview
    companion object {
        suspend fun connect(context: Context, debug: Int, bleCallback: BluetoothGattCallback? = null): MooltipassDevice? {
            val device = MooltipassScan().deviceFlow(context)
                .firstOrNull { it.bondState == BluetoothDevice.BOND_BONDED }
                ?: return null

            val dev = MooltipassDevice(device, debug)
            dev.connect(CoroutineScope(Dispatchers.IO), context, bleCallback)
            return dev
        }
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
class MooltipassScan {

    private fun pairedDevice(context: Context): BluetoothDevice? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter.bondedDevices.filter(::filter).firstOrNull()
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
