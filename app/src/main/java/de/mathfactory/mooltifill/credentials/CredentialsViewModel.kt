package de.mathfactory.mooltifill.credentials

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.mathfactory.mooltifill.AwarenessService
import de.mathfactory.mooltifill.BleMessageFactory
import de.mathfactory.mooltifill.MooltipassCommand
import de.mathfactory.mooltifill.MooltipassMessage
import de.mathfactory.mooltifill.utils.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CredentialsViewModel(app: Application): AndroidViewModel(app) {
    fun cancelRequest() {
        val context = getApplication<Application>().applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            if (!PermissionUtils.hasBluetoothPermission(context)) return@launch
            AwarenessService.closeCurrent()

            val device = AwarenessService.mooltipassDevice(context) ?: return@launch // "Mooltipass device not accessible"
            val hasCommService = device.hasCommService()
            if(!hasCommService || device.isLocked() == true) {
                return@launch
            }

            device.communicate(BleMessageFactory(), MooltipassMessage(MooltipassCommand.CANCEL_USER_REQUEST_BLE))
        }
    }
}