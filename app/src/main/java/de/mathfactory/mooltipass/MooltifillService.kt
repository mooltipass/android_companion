/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mathfactory.mooltipass

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.os.CancellationSignal
import android.service.autofill.*
import android.support.v4.util.ArrayMap
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast
import de.mathfactory.mooltipass.hardware.DeviceLocator
import de.mathfactory.mooltipass.util.Util
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A very basic [AutofillService] implementation that only shows dynamic-generated datasets
 * and don't persist the saved data.
 *
 *
 * The goal of this class is to provide a simple autofill service implementation that is easy
 * to understand and extend, but it should **not** be used as-is on real apps because
 * it lacks fundamental security requirements such as data partitioning and package verification
 * &mdashthese requirements are fullfilled by MyAutofillService.
 */
class MooltifillService : AutofillService() {
    private val TAG = "MooltifillService"

    override fun onFillRequest(
        request: FillRequest, cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        Log.d(TAG, "onFillRequest()")

        // Find autofillable fields
        val structure = getLatestAssistStructure(request)
        val webDomain = StringBuilder()
        val fields = getAutofillableFields(structure, webDomain)
        Log.d(TAG, "autofillable fields:$fields")

        if (fields.isEmpty()) {
            toast("No autofill hints found")
            callback.onSuccess(null)
            return
        }

        val packageName = applicationContext.packageName // this package
        val clientPackage = structure.activityComponent.packageName // app package
        val (login, password, error) = getCredentials(webDomain)
        if(error != null || login == null || password == null || login == "") {
            callback.onFailure(error)
            return
        }

        // Create the base response
        val response = FillResponse.Builder()

        toast("username: " + login + " password.length: " + password.length)
        for (i in 1..1) { // FIXME: support multiple datasets
            val dataset = Dataset.Builder()
            for ((hint, id) in fields) {
                val value = if (hint.contains("password")) password else login
                val presentation = newDatasetPresentation(packageName, login)
                dataset.setValue(id, AutofillValue.forText(value), presentation)
            }
            response.addDataset(dataset.build())
        }
        // 2.Add save info
        val ids = fields.values
        response.setSaveInfo(
            // We're simple, so we're generic
            SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_GENERIC, ids.toTypedArray()).build()
        )

        // 3.Profit!
        callback.onSuccess(response.build())
    }

    private fun getCredentials(webDomain: StringBuilder): List<String?> {
        if(lock.tryLock(2000, TimeUnit.MILLISECONDS)) {
            try {

                val deviceLocator = DeviceLocator()
                val device = deviceLocator.findMooltipassDevice(this)
                if (device == null) {
                    return listOf(null, null, "Mooltipass device not accessible")
                }
                if (!device.sendPing()) {
                    return listOf(null, null, "Mooltipass: Ping failed")
                }
                val stat = device.setContext(webDomain.toString())
                if(stat == 0) {
                    // unknown context
                    return listOf(null, null, "Mooltipass: Unknown context")
                } else if(stat == 3) {
                    // no card
                    return listOf(null, null, "Mooltipass: No card")
                } else if(stat != 1) {
                    // TODO
                    return listOf(null, null, "Mooltipass: Unknown state")
                }
                val login = device.getLogin()
                val password = device.getPassword()
                return listOf(login, password, null)
            } finally {
                lock.unlock()
            }
        } else {
            return listOf(null, null, "Lock failed for Mooltipass device")
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.d(TAG, "onSaveRequest()")
        toast("Save not supported")
        callback.onSuccess()
    }

    /**
     * Parses the [AssistStructure] representing the activity being autofilled, and returns a
     * map of autofillable fields (represented by their autofill ids) mapped by the hint associate
     * with them.
     *
     *
     * An autofillable field is a [ViewNode] whose ViewNode metho
     */
    private fun getAutofillableFields(
        structure: AssistStructure,
        webDomainBuilder: StringBuilder
    ): Map<String, AutofillId> {
        val fields = ArrayMap<String, AutofillId>()
        val nodes = structure.windowNodeCount
        for (i in 0 until nodes) {
            val node = structure.getWindowNodeAt(i).rootViewNode
            addAutofillableFields(fields, node, webDomainBuilder)
        }
        return fields
    }

    /**
     * Adds any autofillable view from the [ViewNode] and its descendants to the map.
     */
    private fun addAutofillableFields(
        fields: MutableMap<String, AutofillId>,
        node: ViewNode, webDomainBuilder: StringBuilder
    ) {
        val hints = node.autofillHints
        if (hints != null) {
            // We're simple, we only care about the first hint
            val hint = hints[0].toLowerCase()

            if (hint != null) {
                val id = node.autofillId
                if (!fields.containsKey(hint)) {
                    Log.v(TAG, "Setting hint '$hint' on $id")
                    fields[hint] = id!!
                } else {
                    Log.v(
                        TAG, "Ignoring hint '" + hint + "' on " + id
                                + " because it was already set"
                    )
                }
            }
        }
        val webDomain = node.webDomain
        if (webDomain != null) {
            if (webDomainBuilder.length > 0) {
                if (webDomain != webDomainBuilder.toString()) {
                    throw SecurityException(
                        "Found multiple web domains: valid= "
                                + webDomainBuilder + ", child=" + webDomain
                    )
                }
            } else {
                webDomainBuilder.append(webDomain)
            }
        }

        val childrenSize = node.childCount
        for (i in 0 until childrenSize) {
            addAutofillableFields(fields, node.getChildAt(i), webDomainBuilder)
        }
    }

    /**
     * Displays a toast with the given message.
     */
    private fun toast(message: CharSequence) {
        Util.logd(TAG, message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {


        private val lock = ReentrantLock()
        /**
         * Helper method to get the [AssistStructure] associated with the latest request
         * in an autofill context.
         */
        internal fun getLatestAssistStructure(request: FillRequest): AssistStructure {
            val fillContexts = request.fillContexts
            return fillContexts[fillContexts.size - 1].structure
        }

        /**
         * Helper method to create a dataset presentation with the given text.
         */
        internal fun newDatasetPresentation(
            packageName: String,
            text: CharSequence
        ): RemoteViews {
            val presentation = RemoteViews(packageName, R.layout.service_list_item)
            presentation.setTextViewText(R.id.text, text)
            presentation.setImageViewResource(R.id.icon, R.mipmap.ic_launcher)
            return presentation
        }
    }
}
