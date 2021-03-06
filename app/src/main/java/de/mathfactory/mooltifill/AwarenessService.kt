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
import kotlinx.coroutines.*


class AwarenessCallback(private val context: Context) : BluetoothGattCallback() {
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

        CoroutineScope(Dispatchers.Main).notify(context, msg)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        mConnectState = newState
        if(newState != BluetoothProfile.STATE_CONNECTED) mLocked = null
        else {
            // send query for lock status
            CoroutineScope(Dispatchers.IO).launch {
                val device = AwarenessService.mooltipassDevice(context)
                if(SettingsActivity.isDebugEnabled(context)) Log.d("Mooltifill", "reading lock status")
                // query will be parsed in AwarenessCallback::onCharacteristicChanged()
                device?.communicate(BleMessageFactory(), MooltipassMessage(MooltipassCommand.MOOLTIPASS_STATUS_BLE))
            }
        }
        if(newState == BluetoothProfile.STATE_DISCONNECTED) {
            AwarenessService.close()
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

        internal const val ONGOING_NOTIFICATION_ID = 100
        private const val CHANNEL_ID = "channel_mooltifill"
        private const val CHANNEL_NAME = "Mooltifill"
        private const val EXTRA_MESSAGE = "message"

        private var device: MooltipassDevice? = null
        internal var serviceStarted = CompletableDeferred<Unit>()

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
            if(device == null || device?.isDisconnected() != false) {
                device = MooltipassDevice.connect(context, debug, AwarenessCallback(context))
            }
            return device
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

        fun setDebug(debug: Int) {
            device?.setDebug(debug)
        }

        fun close() {
            device?.let {
                CoroutineScope(Dispatchers.IO).launch {
                    it.close()
                }
            }
            device = null
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    private val baReceiver = object :BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = when(intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF -> {
                            close()
                            "Bluetooth Off"
                        }
                        BluetoothAdapter.STATE_ON -> "Disconnected"
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