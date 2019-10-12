package de.mathfactory.mooltipass.hardware

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import de.mathfactory.mooltipass.MpMini
import de.mathfactory.mooltipass.MpBle
import de.mathfactory.mooltipass.util.Util.logd
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.support.v4.app.ActivityCompat.requestPermissions
import android.content.DialogInterface
import android.annotation.TargetApi
import android.R.attr.name
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.BluetoothProfile
import android.R.attr.name
import java.util.*


class DeviceLocator(val context: Context) {

    private var bleDev: BluetoothGatt? = null
    private val MP_MINI_VENDOR_ID = 5840
    private val MP_MINI_PRODUCT_ID = 2464

    private val MP_BLE_VENDOR_ID = 4617
    private val MP_BLE_PRODUCT_ID = 17185

    private val TAG = "Mooltipass"
    private val ACTION_USB_PERMISSION = "de.mathfactory.mooltipass.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device == null) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        logd(TAG, "permission granted for device $device")
                    } else {
                        logd(TAG, "permission denied for device $device")
                    }
                }
            }
        }
    }

    fun findMooltipassDevice(context: Context): IMooltipassDevice? {
        // search usb first, then ble
        return findMooltipassOnUsb(context) ?: findMooltipassOnBle(context)
    }

    private fun findMooltipassOnUsb(context: Context): IMooltipassDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        val deviceList = manager.deviceList
        val deviceIterator = deviceList.values.iterator()
        while (deviceIterator.hasNext()) {
            val device = deviceIterator.next()
            if((device.vendorId == MP_MINI_VENDOR_ID && device.productId == MP_MINI_PRODUCT_ID) ||
                (device.vendorId == MP_BLE_VENDOR_ID && device.productId == MP_BLE_PRODUCT_ID)){

                // request permission
                requestPermission(context, manager, device)

                val comm = MooltipassUsb(context, device)
                if(device.vendorId == MP_MINI_VENDOR_ID && device.productId == MP_MINI_PRODUCT_ID) {
                    return MpMini(comm)
                } else {
                    return MpBle(comm)
                }
            }
        }
        return null
    }

    private fun requestPermission(
        context: Context,
        manager: UsbManager,
        device: UsbDevice?
    ) {
        if(!manager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), 0)
            manager.requestPermission(device, permissionIntent)
        }
    }

    private val BluetoothAdapter.isDisabled: Boolean
        get() = !isEnabled

    private fun findMooltipassOnBle(context: Context): IMooltipassDevice? {
        val comm = MooltipassBle(context, bleDev ?: return null)
        return MpBle(comm)
    }

    private val bleScan = object: ScanCallback() {
        private fun handleScanResult(result: ScanResult) {
            if(result.device?.name == "Mooltipass BLE") {
                Log.d(TAG, result.toString())
                result.device.connectGatt(context, false, bleConnect)
            }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            handleScanResult(result ?: return)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            if (results != null) {
                for(result in results) {
                    handleScanResult(result)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.d(TAG, "Ble scan failed: " + errorCode)
        }
    }
    private val bleConnect = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "Connection state changed: " + gatt?.toString() + " status: " + status + " newState: " + newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "STATE_CONNECTED")
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_CONNECTING -> Log.i(TAG, "STATE_CONNECTING")
                BluetoothProfile.STATE_DISCONNECTED -> Log.e(TAG, "STATE_DISCONNECTED")
                else -> Log.e(TAG, "STATE_OTHER")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(TAG, "Services discovered: status: " + status)
            bleDev = gatt
            if(gatt != null) {
                for (service in gatt.services) {
                    Log.d(TAG, "Service: " + service.uuid)
                    for (char in service.characteristics) {
                        Log.d(TAG, "Characteristic: " + char.uuid.toString())
                        if(char.uuid.toString().startsWith("00002a00")) {
                            // device name
                            Log.d(TAG, "Reading Characteristic: " + char.uuid.toString())
                            gatt.readCharacteristic(char)
                        }
                    }
                }
            }

        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if(status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "error")
            }
            Log.d(TAG, "Characteristic "+ characteristic?.uuid.toString() + " read: " + characteristic?.value?.toHexString())
        }
    }


    public fun startBleScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        // TODO: check if bluetooth is enabled, else bluetoothLeScanner is NULL!
        bluetoothManager.adapter.bluetoothLeScanner.startScan(bleScan)
        Log.d(TAG, "Starting ble scan")
    }

    public fun stopBleScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter.bluetoothLeScanner.stopScan(bleScan)
        Log.d(TAG, "Stopping ble scan")
    }

    fun ByteArray.toHexString() : String {
        return this.joinToString("") {
            java.lang.String.format("%02x", it)
        }
    }
}