package de.mathfactory.mooltipass

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import de.mathfactory.mooltipass.hardware.DeviceLocator
import de.mathfactory.mooltipass.util.Util
import de.mathfactory.mooltipass.util.Util.logd

class SettingsActivity : Activity() {
    private val TAG = "SettingsActivity"
    private val REQUEST_CODE_SET_DEFAULT = 1;
    private var mAutofillManager: AutofillManager? = null

    private fun toast(message: CharSequence) {
        Util.logd(TAG, message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    val deviceLocator = DeviceLocator(this)

    private fun permissionSetup() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_COARSE_LOCATION)) {
                val builder = AlertDialog.Builder(this)
                builder.setTitle("This app needs location access")
                builder.setMessage("Please grant location access so this app can detect beacons in the background.")
                builder.setPositiveButton(android.R.string.ok, null)
                builder.setOnDismissListener(DialogInterface.OnDismissListener {
                    requestPermissions(
                        arrayOf<String>(Manifest.permission.ACCESS_COARSE_LOCATION),
                        1
                    )
                })
                builder.show()
            } else {
                // No explanation needed, we can request the permission.
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    1)

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }

        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "permission: " + permissions[0] + " result: " + grantResults[0])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        permissionSetup()
        deviceLocator.startBleScan(this)
        setupSettingsButton(R.id.settings_container_1, R.id.settings_test_connection, View.OnClickListener {
            val dev = deviceLocator.findMooltipassDevice(this)
            if(dev == null) {
                toast("Device not found")
            } else {
                if(!dev.sendPing()) {
                    toast("Ping failed")
                } else {
                    val version = dev.getVersion()
                    toast("Device found: " + version)
                }
            }
        })

        mAutofillManager = getSystemService(AutofillManager::class.java)
        startEnableService()
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceLocator.stopBleScan(this)
    }

    private fun startEnableService() {
        if (mAutofillManager != null && !mAutofillManager!!.hasEnabledAutofillServices()) {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            intent.data = Uri.parse("package:de.mathfactory.mooltipass")
            logd(TAG, "enableService(): intent=%s", intent)
            startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT)
        } else {
            logd("Mooltifill service already enabled.")
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        logd(TAG, "onActivityResult(): req=%s", requestCode)
        when (requestCode) {
            REQUEST_CODE_SET_DEFAULT -> onDefaultServiceSet(resultCode)
        }
    }

    private fun onDefaultServiceSet(resultCode: Int) {
        logd(TAG, "resultCode=%d", resultCode)
        when (resultCode) {
            Activity.RESULT_OK -> {
                logd("Autofill service set.")
                Snackbar.make(
                    findViewById(R.id.settings_layout),
                    R.string.settings_autofill_service_set, Snackbar.LENGTH_SHORT
                )
                    .show()
            }
            Activity.RESULT_CANCELED -> {
                logd("Autofill service not selected.")
                Snackbar.make(
                    findViewById(R.id.settings_layout),
                    R.string.settings_autofill_service_not_set, Snackbar.LENGTH_SHORT
                )
                    .show()
            }
        }
    }

    private fun setupSettingsButton(
        containerId: Int, labelId: Int, /*imageViewId: Int,*/
        onClickListener: View.OnClickListener
    ) {
        val container = findViewById<ViewGroup>(containerId)
        val buttonLabel = container.findViewById<TextView>(labelId)
        val buttonLabelText = buttonLabel.text.toString()
        //val imageView = container.findViewById<ImageView>(imageViewId)
        //imageView.contentDescription = buttonLabelText
        container.setOnClickListener(onClickListener)
    }
}