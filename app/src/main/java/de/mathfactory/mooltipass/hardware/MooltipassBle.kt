package de.mathfactory.mooltipass.hardware

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.util.Log
import java.util.*

class MooltipassBle(ctx: Context, val device: BluetoothGatt): MooltipassCommunication(ctx) {
    override fun getPktSize(): Int {
        return 64 //??
    }

    private val DEFAULT_TIMEOUT = 6000

    override fun acquire() {
        Log.d("Mooltipass", "Acquiring BLE device")

//        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
//        c = manager.openDevice(device)
//        device.getInterface(0).also { intf ->
//            c?.claimInterface(intf, true)
//        }
    }

    override fun transmit(pkt: ByteArray) {
//        device.writeCharacteristic(mWrChar)
//        getOutput()?.also { endpoint ->
//            val rcvBytes = c?.bulkTransfer(endpoint, pkt, pkt.size, DEFAULT_TIMEOUT) //do in another thread

//            Log.d("Mooltipass", "Sent " + rcvBytes + " bytes :" + pkt.toHexString())
//        }
    }

    override fun receive(): ByteArray? {
//        getInput()?.also { endpoint ->
//            for(i in 1..3) {
//                val rcvData = ByteArray(endpoint.maxPacketSize)
//                val rcvBytes = c?.bulkTransfer(endpoint, rcvData, rcvData.size, DEFAULT_TIMEOUT / 6)
//                if(rcvBytes == -1) {
//                    Thread.sleep((DEFAULT_TIMEOUT / 6).toLong())
//                    continue
//                }
//                Log.d("Mooltipass", "Received " + rcvBytes + " bytes :" + rcvData.toHexString())
//                return rcvData
//            }
//            Log.d("Mooltipass", "Giving up receiving")
//        }
        return null
    }

    override fun release() {
        Log.d("Mooltipass", "Releasing USB device")
//        device.getInterface(0).also { intf ->
//            c?.releaseInterface(intf)
//            c?.close()
//        }
//        c = null
    }

    private fun getOutput(): UsbEndpoint? {
        return getEndpoint(UsbConstants.USB_DIR_OUT)
    }

    private fun getInput(): UsbEndpoint? {
        return getEndpoint(UsbConstants.USB_DIR_IN)
    }

    private fun getEndpoint(direction: Int): UsbEndpoint? {
//        device.getInterface(0).also { intf ->
//            for (j in 0 until intf.endpointCount) {
//                if (intf.getEndpoint(j).direction == direction) {
//                    return intf.getEndpoint(j)
//                }
//            }
//        }
        return null
    }

    fun ByteArray.toHexString() : String {
        return this.joinToString("") {
            java.lang.String.format("%02x", it)
        }
    }
}