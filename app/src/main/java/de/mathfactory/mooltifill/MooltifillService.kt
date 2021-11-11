/*
 * Copyright (C) 2021 Bernhard Rauch.
 *
 * This file is part of Mooltifill.
 *
 * Mooltifill is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Mooltifill is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mooltifill.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.mathfactory.mooltifill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.*
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.collection.ArrayMap
import kotlinx.coroutines.*
import java.util.*

private class AutofillInfo(val query: String, val autofillIds: List<Pair<AutofillId, AutofillValue?>>) {

}

@FlowPreview
@ExperimentalCoroutinesApi
class MooltifillService : AutofillService() {
    private fun getInfo(fillContexts: MutableList<FillContext>): AutofillInfo {
        // Find autofillable fields
        val structure = getLatestAssistStructure(fillContexts)
        val webDomain = StringBuilder()
        //val isManual = (request.flags and FillRequest.FLAG_MANUAL_REQUEST) != 0

        // Currently only use the first password field!
        val autofillIds = getAutofillableFields(structure, webDomain).filterKeys { it.contains("password") }.values.take(1)
        if(SettingsActivity.isDebugEnabled(applicationContext)) {
            Log.d(TAG, "autofillable fields:$autofillIds")
        }

        val packageName = applicationContext.packageName // this package
        val clientPackage = structure.activityComponent.packageName // app package

        val query = if(webDomain.isEmpty()) {clientPackage} else {webDomain.toString()}
        return AutofillInfo(query, autofillIds)
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if(SettingsActivity.isDebugEnabled(applicationContext)) {
            Log.d(TAG, "onFillRequest()")
        }
        val info = getInfo(request.fillContexts)

        if (info.autofillIds.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        // Create the base response
        val response = FillResponse.Builder()

        val ids = info.autofillIds.map(Pair<AutofillId, AutofillValue?>::first)
        ids.forEach { id ->
            val dataset = Dataset.Builder()
            val presentation = remoteViews(packageName, "Mooltipass")
            val intent = Intent(applicationContext, MooltifillActivity::class.java)
            intent.putExtra(MooltifillActivity.EXTRA_QUERY, info.query.takeLast(31))
            intent.putExtra(MooltifillActivity.EXTRA_ID, id)
            val pi = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            dataset.setAuthentication(pi.intentSender)
            dataset.setValue(id, null, presentation)
            response.addDataset(dataset.build())
        }

        // Add save info
        response.setSaveInfo(
            // For now only save passwords
            SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, ids.toTypedArray()).build()
        )

        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val info = getInfo(request.fillContexts)
        if(info.autofillIds.size != 1) {
            callback.onFailure("Only single ids supported for now")
            return
        }
        val id = info.autofillIds.first()
        val context = applicationContext
        id.second?.let {
            CoroutineScope(Dispatchers.IO).launch {
                if(MooltifillActivity.setPassword(context, info.query.takeLast(31), it.textValue.toString())) {
                    callback.onSuccess()
                } else {
                    callback.onFailure("Mooltifill save failed")
                }
//                val intent = Intent(context, MooltifillActivity::class.java)
//                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                intent.putExtra(MooltifillActivity.EXTRA_QUERY, info.query)
//                intent.putExtra(MooltifillActivity.EXTRA_ID, id)
//                intent.putExtra(MooltifillActivity.EXTRA_SAVE, true)
//                val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
//                callback.onSuccess(pi)
            }
        }
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
    ): Map<String, Pair<AutofillId, AutofillValue?>> {
        val fields = ArrayMap<String, Pair<AutofillId, AutofillValue?>>()
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
        fields: ArrayMap<String, Pair<AutofillId, AutofillValue?>>,
        node: AssistStructure.ViewNode, webDomainBuilder: StringBuilder
    ) {
        val hints = node.autofillHints
        if (hints != null && hints.isNotEmpty()) {
            // TODO evaluate all hints
            val hint = hints[0].lowercase(Locale.getDefault())

            val id = node.autofillId
            val value = node.autofillValue
            if (!fields.containsKey(hint)) {
                if(SettingsActivity.isDebugEnabled(applicationContext)) {
                    Log.v(TAG, "Setting hint '$hint' on $id")
                }
                fields[hint] = Pair(id!!, value)
            } else {
                if(SettingsActivity.isDebugEnabled(applicationContext)) {
                    Log.v(TAG, "Ignoring hint '$hint' on $id because it was already set")
                }
            }
        }
        val webDomain = node.webDomain
        if (webDomain != null) {
            if (webDomainBuilder.isNotEmpty()) {
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


    companion object {
        private const val TAG = "MooltifillService"

        /**
         * Helper method to get the [AssistStructure] associated with the latest request
         * in an autofill context.
         */
        internal fun getLatestAssistStructure(fillContexts: MutableList<FillContext>): AssistStructure {
            return fillContexts[fillContexts.size - 1].structure
        }

        /**
         * Helper method to create a dataset presentation with the given text.
         */
        internal fun remoteViews(packageName: String, remoteViewsText: String): RemoteViews {
            val presentation = RemoteViews(packageName, R.layout.multidataset_service_list_item)
            presentation.setTextViewText(R.id.text, remoteViewsText)
            presentation.setImageViewResource(R.id.icon, R.mipmap.ic_launcher)
            return presentation
        }
    }
}
