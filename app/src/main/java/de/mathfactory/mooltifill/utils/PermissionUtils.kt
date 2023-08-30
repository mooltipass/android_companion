package de.mathfactory.mooltifill.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

object PermissionUtils {
    private fun hasPermission(permission: String, context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT, context)
        }
    }

    fun hasPostNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || hasPermission(Manifest.permission.POST_NOTIFICATIONS, context)
    }
}