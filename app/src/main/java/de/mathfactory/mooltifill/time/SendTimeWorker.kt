@file:OptIn(ExperimentalCoroutinesApi::class)
package de.mathfactory.mooltifill.time

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.mathfactory.mooltifill.AwarenessService
import de.mathfactory.mooltifill.BleMessageFactory
import de.mathfactory.mooltifill.MooltipassCommand
import de.mathfactory.mooltifill.MooltipassMessage
import de.mathfactory.mooltifill.MooltipassPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

class SendTimeWorker(context: Context, params: WorkerParameters): Worker(context, params) {

    override fun doWork(): Result {
        CoroutineScope(Dispatchers.IO).launch {
            sendTimeToDevice()
        }

        return Result.success()
    }
    private suspend fun sendTimeToDevice() {
        val device = AwarenessService.mooltipassDevice(context = applicationContext) ?: return // "Mooltipass device not accessible"
        val hasCommService = device.hasCommService()

        if(!hasCommService || device.isLocked() == true) {
            return
        }

        device.communicate(BleMessageFactory(),
            MooltipassMessage(MooltipassCommand.SET_DATE_BLE, MooltipassPayload.getDate()))
    }
}