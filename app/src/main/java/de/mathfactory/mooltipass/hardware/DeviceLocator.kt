package de.mathfactory.mooltipass.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import de.mathfactory.mooltipass.MpMini
import de.mathfactory.mooltipass.MpBle
import de.mathfactory.mooltipass.util.Util.logd

class DeviceLocator {

    private val MP_MINI_VENDOR_ID = 5840
    private val MP_MINI_PRODUCT_ID = 2464

    private val MP_BLE_VENDOR_ID = 4617
    private val MP_BLE_PRODUCT_ID = 17185

    private val TAG = "DeviceLocator"
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

    private fun findMooltipassOnBle(context: Context): IMooltipassDevice? {
        // TODO
        return null
    }
}