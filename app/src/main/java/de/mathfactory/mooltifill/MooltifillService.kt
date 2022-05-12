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
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.collection.ArrayMap
import kotlinx.coroutines.*
import java.util.*

private class AutofillInfo(val query: String, private val autofillIds: Map<String, Pair<AutofillId, AutofillValue?>>, val isWebRequest: Boolean) {
    public fun username() = autofillIds[View.AUTOFILL_HINT_USERNAME] ?: autofillIds[View.AUTOFILL_HINT_EMAIL_ADDRESS]
    public fun password() = autofillIds[View.AUTOFILL_HINT_PASSWORD]
    fun substitutedQuery(context: Context) = SettingsActivity.getSubstitutionPolicy(context, isWebRequest).let {
            substitution -> query.let(substitution::policies).first()
    }
}

@FlowPreview
@ExperimentalCoroutinesApi
class MooltifillService : AutofillService() {
    private fun logDebug(message: String) {
        if(SettingsActivity.isDebugEnabled(applicationContext)) {
            Log.d(TAG, message)
        }
    }

    private fun logVerbose(message: String) {
        if(SettingsActivity.isDebugEnabled(applicationContext)) {
            Log.v(TAG, message)
        }
    }

    private fun getInfo(fillContexts: MutableList<FillContext>): AutofillInfo {
        // Find autofillable fields
        val structure = getLatestAssistStructure(fillContexts)
        val webDomain = StringBuilder()
        //val isManual = (request.flags and FillRequest.FLAG_MANUAL_REQUEST) != 0

        val autofillIds = getAutofillableFields(structure, webDomain)//.filterKeys { it.contains("password") }.values.take(1)
        logDebug(TAG, "autofillable fields:$autofillIds")

        val packageName = applicationContext.packageName // this package
        val clientPackage = structure.activityComponent.packageName // app package

        val isWebDomain = webDomain.isNotEmpty()
        // substitute the webDomain or clientPacakge according to configured policies
        val query = if(isWebDomain) {webDomain.toString()} else {clientPackage}
        return AutofillInfo(query, autofillIds, isWebDomain)
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        logDebug(TAG, "onFillRequest()")

        AwarenessService.onFillRequest(applicationContext)

        val info = getInfo(request.fillContexts)

        val username = info.username()?.first
        val password = info.password()?.first
        if (username == null || password == null) {
            if(SettingsActivity.isDebugEnabled(applicationContext)) {
                if (username == null) {
                    Log.e(TAG, "No username field found.")
                }
                if (password == null) {
                    Log.e(TAG, "No password field found.")
                }
            }

            callback.onSuccess(null)
            return
        }

        // Create the base response
        val response = FillResponse.Builder()

        val dataset = Dataset.Builder()
        val presentation = remoteViews(packageName, "Mooltipass", info.substitutedQuery(applicationContext))
        val intent = Intent(applicationContext, MooltifillActivity::class.java)
        intent.putExtra(MooltifillActivity.EXTRA_QUERY, info.query)
        intent.putExtra(MooltifillActivity.EXTRA_IS_WEB_REQUEST, info.isWebRequest)
        intent.putExtra(MooltifillActivity.EXTRA_USERNAME, username)
        intent.putExtra(MooltifillActivity.EXTRA_PASSWORD, password)
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // reuse pending intent only if query is the same (through hashCode())
        val pi = PendingIntent.getActivity(applicationContext, info.query.hashCode(), intent, flags)
        dataset.setAuthentication(pi.intentSender)
        dataset.setValue(username, null, presentation)
        response.addDataset(dataset.build())

        // Add save info
        response.setSaveInfo(
            SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME, arrayOf(username, password)).build()
        )

        logDebug(TAG, "onFillRequest Success")
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val info = getInfo(request.fillContexts)
        val username = info.username()?.second
        val password = info.password()?.second
        if(username == null) {
            callback.onFailure("Missing username")
            return
        }
        if(password == null) {
            callback.onFailure("Missing password")
            return
        }
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            if(MooltifillActivity.setCredentials(context, info.substitutedQuery(context), username.textValue.toString(), password.textValue.toString())) {
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

    private fun inferHint(s: String?): String? {
        return s?.lowercase()?.let {
            when {
                it.contains("label") -> null
                it.contains("container") -> null
                it.contains("password") -> View.AUTOFILL_HINT_PASSWORD
                it.contains("passwort") -> View.AUTOFILL_HINT_PASSWORD
                it.contains("user") -> View.AUTOFILL_HINT_USERNAME
                it.contains("login") -> View.AUTOFILL_HINT_USERNAME
                //it.contains("id") -> View.AUTOFILL_HINT_USERNAME
                it.contains("email") -> View.AUTOFILL_HINT_EMAIL_ADDRESS
                it.contains("e-mail") -> View.AUTOFILL_HINT_EMAIL_ADDRESS
                //it.contains("name") -> View.AUTOFILL_HINT_NAME
                //it.contains("phone") -> View.AUTOFILL_HINT_PHONE
                else -> null
            }
        }
    }

    private fun getHint(node: AssistStructure.ViewNode): String? {
        // return first autofill hint, if present
        node.autofillHints?.firstOrNull()?.let {
            logDebug(TAG, "Found pre-existing hint $it")

            inferHint(it)?.let {
                logDebug(TAG, "Inferred hint from pre-existing hint: $it")
                return it
            }
        }

        // ensure we are an EditText
        if(node.className?.contains("EditText") == true) {
            logDebug(TAG, "Node is an EditText")

            // infer hint from getHint()
            inferHint(node.hint)?.let {
                logDebug(TAG, "Inferred hint from hint: $it")
                return it
            }

            // infer hint from id
            inferHint(node.idEntry)?.let {
                logDebug(TAG, "Inferred hint from id: $it")
                return it
            }
        }

        // infer hint from input type
        when(node.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> when (node.inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> View.AUTOFILL_HINT_PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> View.AUTOFILL_HINT_USERNAME
                else -> null
            }
            InputType.TYPE_CLASS_NUMBER -> when(node.inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> View.AUTOFILL_HINT_PASSWORD
                else -> null
            }
            else -> null
        }?.let {
            logDebug(TAG, "Inferred hint from inputType: $it")
            return it
        }

        // infer hint from html type=password
        if(node.htmlInfo?.attributes?.any { it.first == "type" && it.second == "password" } == true) {
            logDebug(TAG, "Inferred hint from HTML attribute where type='password': AUTOFILL_HINT_PASSWORD")
            return View.AUTOFILL_HINT_PASSWORD
        }

        // infer hint from type='text' and the HTML element's name or id
        if(node.htmlInfo?.attributes?.any {it.first == "type" && it.second == "text"} == true) {
            return inferHint(node.htmlInfo?.attributes?.firstOrNull { it.first == "name" }?.second)
                ?: inferHint(node.htmlInfo?.attributes?.firstOrNull { it.first == "id" }?.second)
        }

        // give up
        Log.w(TAG, "Couldn't figure out what this view was!")
        return null
    }

    /**
     * Adds any autofillable view from the [ViewNode] and its descendants to the map.
     */
    private fun addAutofillableFields(
        fields: ArrayMap<String, Pair<AutofillId, AutofillValue?>>,
        node: AssistStructure.ViewNode, webDomainBuilder: StringBuilder
    ) {
        fun addAutofillableField(hint: String, id: AutofillId?, value: AutofillValue?) {
            if (!fields.containsKey(hint)) {
                logVerbose(TAG, "Setting hint '$hint' on $id")
                fields[hint] = Pair(id!!, value)
            } else {
                logVerbose(TAG, "Ignoring hint '$hint' on $id because it was already set")
            }
        }
        getHint(node)?.let { hint ->
            addAutofillableField(hint, node.autofillId, node.autofillValue)
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
        internal fun remoteViews(packageName: String, remoteViewsText: String, remoteViewsText2: String): RemoteViews {
            val presentation = RemoteViews(packageName, R.layout.multidataset_service_list_item)
            presentation.setTextViewText(R.id.text, remoteViewsText)
            presentation.setTextViewText(R.id.text2, remoteViewsText2)
            presentation.setImageViewResource(R.id.icon, R.mipmap.ic_launcher)
            return presentation
        }
    }
}
