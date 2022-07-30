@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package de.mathfactory.mooltifill

import android.app.*
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import de.mathfactory.mooltifill.AwarenessService.Companion.notify
import de.mathfactory.mooltifill.AwarenessService.Companion.setActive
import kotlinx.coroutines.*


class AwarenessCallback(private val context: Context, private val device: MooltipassDevice, private val connectedCallback: (MooltipassDevice) -> Unit) : BluetoothGattCallback() {
    private var mLocked: Boolean? = null
    private var mConnectState: Int = BluetoothProfile.STATE_DISCONNECTED

    private fun sendNotification() {
        val msg = when (mConnectState) {
            BluetoothProfile.STATE_CONNECTED -> "Connected"
            BluetoothProfile.STATE_DISCONNECTED -> "Disconnected"
            BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
            BluetoothProfile.STATE_CONNECTING -> "Connecting"
            else -> "Unknown State: $mConnectState"
        } + when(mLocked) {
            true -> " (locked)"
            false -> " (unlocked)"
            null -> ""
        }
        if(AwarenessService.isActive(device)) {
            // only send notifications for the active device
            CoroutineScope(Dispatchers.Main).notify(context, msg)
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        mConnectState = newState
        if(newState != BluetoothProfile.STATE_CONNECTED) mLocked = null
        else {
            connectedCallback(device)
            // send query for lock status
            CoroutineScope(Dispatchers.IO).launch {
                if(SettingsActivity.isDebugEnabled(context)) Log.d("Mooltifill", "reading lock status")
                // query will be parsed in AwarenessCallback::onCharacteristicChanged()
                device.communicate(BleMessageFactory(), MooltipassMessage(MooltipassCommand.MOOLTIPASS_STATUS_BLE))
            }
        }
        if(newState == BluetoothProfile.STATE_DISCONNECTED) {
            CoroutineScope(Dispatchers.IO).launch {
                device.close()
                if(AwarenessService.isActive(device)) {
                    setActive(null)
                }
            }
        }
        sendNotification()
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
        // be aware of lock status
        // TODO this could be read from MooltipassDevice, implement a callback there instead of reparsing here
        characteristic?.value?.let { data ->
            MooltipassPayload.tryParseIsLocked(data)?.let {
                mLocked = it
                sendNotification()
            }
        }
    }
}

class AwarenessService : Service() {
    companion object {
        internal fun CoroutineScope.notify(context: Context, msg: String) = launch {
            if(!SettingsActivity.isAwarenessEnabled(context)) return@launch
            serviceStarted.await()
            if(SettingsActivity.isDebugEnabled(context)) {
                Log.d("Mooltifill", "Aware: $msg")
            }
            val notification = createNotification(context, msg)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ONGOING_NOTIFICATION_ID, notification)
        }

        private const val DEVICE_CONNECT_TIMEOUT: Long = 20000
        internal const val ONGOING_NOTIFICATION_ID = 100
        private const val CHANNEL_ID = "channel_mooltifill"
        private const val CHANNEL_NAME = "Mooltifill"
        private const val EXTRA_MESSAGE = "message"

        private var allDevices = CompletableDeferred<Array<MooltipassDevice>>()
        private var device = CompletableDeferred<MooltipassDevice>()
        internal val serviceStarted = CompletableDeferred<Unit>()

        fun deviceList() = allDevices

        fun onFillRequest(context: Context) {
            ensureService(context)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AwarenessService::class.java)
            context.stopService(intent)
        }

        fun ensureService(context: Context, msg: String? = null, force: Boolean = false) {
            if(!force && !SettingsActivity.isAwarenessEnabled(context)) return
            val intent = Intent(context, AwarenessService::class.java)
            if(msg != null) intent.putExtra(EXTRA_MESSAGE, msg)
            // start service
            context.startForegroundService(intent)
            // try to connect to ble device
            CoroutineScope(Dispatchers.IO).launch {
                mooltipassDevice(context)
            }
        }

        suspend fun mooltipassDevice(context: Context, debug: Int = SettingsActivity.debugLevel(context)): MooltipassDevice? {
            if(allDevices.isActive) { // complete deferred
                val bonded = MooltipassDevice.bondedDevices(context, debug)
                allDevices.complete(bonded.map { dev ->
                    dev.connect(CoroutineScope(Dispatchers.IO), context, AwarenessCallback(context, dev) {
                        // if device has been chosen, filter accordingly
                        if(SettingsActivity.isChosenDeviceAddress(context, dev.address(), true)) {
                            // the first device that connects will be taken
                            device.complete(it)
                        }
                    })
                    dev
                }.toTypedArray())
            }
            return withTimeoutOrNull(DEVICE_CONNECT_TIMEOUT) {
                return@withTimeoutOrNull device.await()
            }
        }

        fun unlearnDevices() {
            allDevices = CompletableDeferred()
            device = CompletableDeferred()
        }

        internal fun createNotification(context: Context, text: CharSequence?): Notification {
            val pendingIntent: PendingIntent =
                Intent(context, SettingsActivity::class.java).let { notificationIntent ->
                    PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
                }
            createNotificationChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getText(R.string.awareness_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                //            .setOngoing(true)
                .build()
        }

        private fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance)

            channel.description = "Mooltipass autofill service"

            manager.createNotificationChannel(channel)
        }

        fun setDebug(debug: Int) = try {
            allDevices.getCompleted().forEach { it.setDebug(debug) }
        } catch (_: IllegalStateException) {}

        fun closeAll() = try {
            allDevices.getCompleted().forEach {
                CoroutineScope(Dispatchers.IO).launch {
                    it.close()
                }
            }
        } catch (_: IllegalStateException) {}

        fun setActive(d: MooltipassDevice?) {
            device = d?.let(::CompletableDeferred) ?: CompletableDeferred()
        }

        fun isActive(d: MooltipassDevice) = try {
            device.getCompleted() == d
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    private val baReceiver = object :BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = when(intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF -> {
                            closeAll()
                            unlearnDevices()
                            "Bluetooth Off"
                        }
                        BluetoothAdapter.STATE_ON -> {
                            ensureService(context)
                            "Disconnected"
                        }
                        else -> null
                    }
                }
                else -> "Unknown"
            }
            msg?.let { CoroutineScope(Dispatchers.Main).notify(context, it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter()
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(baReceiver, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(baReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(SettingsActivity.isDebugEnabled(this)) {
            Log.d("Mooltifill", "onStartCommand")
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val msg = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: manager.activeNotifications.firstOrNull()?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?: getString(R.string.default_awareness_message)
        val notification = createNotification(this, msg)

        startForeground(ONGOING_NOTIFICATION_ID, notification)
        serviceStarted.complete(Unit)
        return START_STICKY
    }

}