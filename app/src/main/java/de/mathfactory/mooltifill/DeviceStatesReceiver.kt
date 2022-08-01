package de.mathfactory.mooltifill

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

private const val MAC_ADDRESS_BASE_VALUE = "68:79:12:3"

class DeviceStatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        if (BluetoothDevice.ACTION_ACL_CONNECTED == intent.action) {
            val currentDevice: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if(currentDevice?.address?.startsWith(MAC_ADDRESS_BASE_VALUE,true) == true)
            {
                saveDevice(currentDevice,context)
            }
        }
    }

    private fun saveDevice(currentDevice: BluetoothDevice?, context: Context)
    {
        val sharedPreference =  context.getSharedPreferences(context.getText(R.string.last_device).toString(),Context.MODE_PRIVATE)
        var editor = sharedPreference?.edit()
        editor?.putString(context.getText(R.string.device_mac).toString(),currentDevice?.address)
        editor?.commit()
    }
}