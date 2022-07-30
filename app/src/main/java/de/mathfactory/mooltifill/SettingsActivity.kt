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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.*
import kotlinx.coroutines.*
import okhttp3.internal.publicsuffix.PublicSuffixDatabase

enum class UrlSubstitutionPolicies : SubstitutionPolicy {
    Nochange, PreferWww, PreferNowww, AddWww, RemoveWww, PublicSuffix, PublicSuffixWithSubDomain;

    override fun policies(query: String): List<String> =
        when(this) {
            Nochange -> listOf(query)
            PreferWww -> listOf(withWww(query), withoutWww(query))
            PreferNowww -> listOf(withoutWww(query), withWww(query))
            AddWww -> listOf(withWww(query))
            RemoveWww -> listOf(withoutWww(query))
            PublicSuffix -> listOf(publicSuffix(query))
            PublicSuffixWithSubDomain -> publicSuffixWithSubDomain(query)
        }.map(SubstitutionPolicy::transform)

    private fun withWww(query: String): String {
        if(query.startsWith("www.")) return query
        return "www.$query";
    }

    private fun withoutWww(query: String): String {
        if(query.startsWith("www.")) return query.substring(4)
        return query
    }

    private fun publicSuffix(query: String): String {
        val tld = PublicSuffixDatabase.get().getEffectiveTldPlusOne(query)
        if (tld != null)
        {
            return tld
        }
        return query
    }

    private fun publicSuffixWithSubDomain(query: String): List<String> {
        val tld = PublicSuffixDatabase.get().getEffectiveTldPlusOne(query)
        if (tld != null)
        {
            val strippedDomain = query.replace("www.","",true)
            val compareResult = tld.compareTo(strippedDomain)
            when (compareResult) {
                0 ->  {
                    return listOf(tld)
                }
                else -> {
                    // Explicitly combining TLD+one part with next level one subdomain
                    val strippedTokens = query.substringBefore("." + tld).split(".")
                    return listOf(strippedTokens.last() + "." + tld, tld)
                }
            }
        }
        return listOf(query)
    }
}

class PkgSubstitutionPolicies(private val reverse: Boolean, private val maxComponentCount: Int) : SubstitutionPolicy {
    override fun policies(query: String): List<String> =
        listOf(query
            // truncate components
            .let(this::truncated)
            // reverse components
            .let { if (reverse) reversed(it) else it }
            // base transform
            .let(SubstitutionPolicy::transform))

    private fun reversed(query: String): String = query.split(".").reversed().joinToString(".")
    private fun truncated(query: String): String = query.split(".").take(maxComponentCount).joinToString(".")
}

class SettingsActivity : AppCompatActivity() {

    companion object {
        fun debugLevel(context: Context): Int = parsedIntSetting(context, "debug_level", 0)
        fun isDebugEnabled(context: Context): Boolean = parsedIntSetting(context, "debug_level", 0) > 0
        fun isDebugVerbose(context: Context): Boolean = parsedIntSetting(context, "debug_level", 0) > 1
        fun isAwarenessEnabled(context: Context): Boolean = booleanSetting(context, "awareness", true)
        fun isChosenDeviceAddress(context: Context, mac: String?, default: Boolean) = stringSetting(context, "chosen_device", null)
                ?.let { it == mac } ?: default
        fun setChosenDeviceAddress(context: Context, mac: String?) = if(mac == null) removeKey(context, "chosen_device") else putString(context, "chosen_device", mac)

        fun getSubstitutionPolicy(context: Context, isWebRq: Boolean): SubstitutionPolicy =
            if(isWebRq) { getUrlSubstitutionPolicy(context) } else { getPackageSubstitutionPolicy(context) }

        private fun getUrlSubstitutionPolicy(context: Context): SubstitutionPolicy = stringSetting(context, "www_substitution", null)
            ?.let { UrlSubstitutionPolicies.valueOf(it) }
            ?: UrlSubstitutionPolicies.PublicSuffix
        private fun getPackageSubstitutionPolicy(context: Context): SubstitutionPolicy =
            PkgSubstitutionPolicies(
                booleanSetting(context, "pkg_substitution_reverse", true),
                intSetting(context, "pkg_substitution_max_components", 2))

        private fun <T> castChecked(block: () -> T): T? =
            try { block() } catch(e: ClassCastException) { null }

        private fun putString(context: Context, key: String, value: String) =
            PreferenceManager.getDefaultSharedPreferences(context).edit { putString(key, value) }

        private fun removeKey(context: Context, key: String) =
            PreferenceManager.getDefaultSharedPreferences(context).edit{ remove(key) }

        private fun booleanSetting(context: Context, key: String, default: Boolean) =
            castChecked { PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key, default) } ?: default

        private fun intSetting(context: Context, key: String, default: Int) =
            castChecked { PreferenceManager.getDefaultSharedPreferences(context).getInt(key, default) } ?: default

        private fun stringSetting(context: Context, key: String, default: String?) =
            castChecked { PreferenceManager.getDefaultSharedPreferences(context).getString(key, default) } ?: default

        private fun parsedIntSetting(context: Context, key: String, default: Int) =
            stringSetting(context, key, null)?.toIntOrNull() ?: default
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, SettingsFragment())
                    .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setHomeButtonEnabled(false)
        permissionSetup(this)
        AwarenessService.ensureService(this)
    }

    private fun permissionSetup(context: Context) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                if(isDebugEnabled(context)) {
                    Log.d("Mooltifill", "permission result: $it")
                }
            }
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
//        if (ContextCompat.checkSelfPermission(
//                this,
//                Manifest.permission.ACCESS_COARSE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            if (ActivityCompat.shouldShowRequestPermissionRationale(
//                    this,
//                    Manifest.permission.ACCESS_COARSE_LOCATION
//                )
//            ) {
//                val builder = AlertDialog.Builder(this)
//                builder.setTitle("This app needs location access")
//                builder.setMessage("Please grant location access so this app can detect beacons in the background.")
//                builder.setPositiveButton(android.R.string.ok, null)
//                builder.setOnDismissListener {
//                    requestPermissions(
//                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
//                        1
//                    )
//                }
//                builder.show()
//            } else {
//                // No explanation needed, we can request the permission.
//                requestPermissions(
//                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
//                    1
//                )
//
//                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
//                // app-defined int constant. The callback method gets the
//                // result of the request.
//            }
//
//        }
    }

    class DeviceFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.device_preferences, rootKey)

            findPreference<Preference>("test_ping")?.setOnPreferenceClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    it.summary = "Waiting for device access..."
                    val ping = withContext(Dispatchers.IO) {
                        MooltifillActivity.ping(requireContext())
                    }
                    it.summary = ping.getOrNull() ?: ping.exceptionOrNull()?.message
                }
                true
            }

            val rescan: () -> Unit = {
                CoroutineScope(Dispatchers.Main).launch {
                    findPreference<PreferenceCategory>("device_list")?.let { cat ->
                        cat.removeAll()
                        cat.addPreference(Preference(requireContext()).apply {
                            title = "Performing scan..."
                        })
                        val devices = AwarenessService.deviceList().await()
                        // get context after suspend. If we went out of view, return
                        val ctx = context ?: return@let
                        cat.removeAll()
                        if (devices.isEmpty()) {
                            cat.addPreference(Preference(ctx).apply {
                                title =
                                    "No device found. Please ensure bluetooth is enabled and the device is bonded in the Android system settings"
                            })
                        } else {
                            devices.forEach { device ->
                                cat.addPreference(Preference(ctx).also {
                                    val chosen = isChosenDeviceAddress(ctx, device.address(), false)
                                    if(chosen) it.setSummary(R.string.device_is_chosen)
                                    it.title = device.description()
                                    it.setOnPreferenceClickListener { p ->
                                        Toast.makeText(
                                            context,
                                            "Using device " + device.description(),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        // save preference
                                        setChosenDeviceAddress(ctx, device.address())
                                        for(pd in cat) {
                                            pd.summary = null
                                        }
                                        p.setSummary(R.string.device_is_chosen)
                                        // switch device
                                        AwarenessService.setActive(device)
                                        true
                                    }
                                })
                            }
                        }
                    }
                }
            }

            rescan()
        }
    }

    @FlowPreview
    @ExperimentalCoroutinesApi
    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val PING_TIMEOUT = 20000L
        }
        private lateinit var mEnableService: ActivityResultLauncher<Intent>
        var mDefaultServiceSet = false

        override fun onCreate(savedInstanceState: Bundle?) {
            mEnableService = registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onDefaultServiceSet)
            super.onCreate(savedInstanceState)
        }

        override fun onResume() {
            super.onResume()
            findPreference<SwitchPreference>("enable_mooltifill")?.isChecked = mDefaultServiceSet || hasEnabledMooltifill(requireActivity())
            // only use info from onDefaultServiceSet once, as it may change later manually by the user
            mDefaultServiceSet = false
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
//            findPreference<Preference>("enable_mooltifill")?.setOnPreferenceClickListener {
//                enableService(requireContext().applicationContext)
//                true
//            }
            findPreference<SwitchPreference>("enable_mooltifill")?.setOnPreferenceChangeListener { _, newValue ->
                val activity = requireActivity()
                if (newValue == true) {
                    enableMooltifill(activity)
                    hasEnabledMooltifill(activity)
                } else {
                    disableMooltifill(activity)
                    true
                }
            }
            findPreference<ListPreference>("debug_level")?.setOnPreferenceChangeListener { _, newValue ->
                if(newValue is String) {
                    newValue.toIntOrNull()?.let(AwarenessService::setDebug)
                }
                true
            }
            findPreference<SwitchPreference>("awareness")?.setOnPreferenceChangeListener { _, newValue ->
                if(newValue == false) AwarenessService.stopService(requireContext())
                else AwarenessService.ensureService(requireContext(), null, true)
                true
            }
            findPreference<Preference>("device_settings")?.setOnPreferenceClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settings, DeviceFragment())
                    .setReorderingAllowed(true)
                    .addToBackStack(null)
                    .commit()
                true
            }
        }

        private fun hasEnabledMooltifill(context: Activity): Boolean {
            val autofillManager = context.getSystemService(AutofillManager::class.java)
            return autofillManager != null && autofillManager.hasEnabledAutofillServices()
        }

        private fun disableMooltifill(context: Activity) {
            val autofillManager = context.getSystemService(AutofillManager::class.java)
            autofillManager.disableAutofillServices()
        }

        private fun enableMooltifill(context: Activity) {
            if (!hasEnabledMooltifill(context)) {
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                intent.data = Uri.parse("package:de.mathfactory.mooltifill")
                if(isDebugEnabled(context)) {
                    Log.d("Mooltifill", "enableService(): intent=$intent")
                }
                mEnableService.launch(intent)
            } else {
                Toast.makeText(context, "Mooltifill is already enabled. Great!", Toast.LENGTH_SHORT).show()
            }
        }

        private fun onDefaultServiceSet(resultCode: ActivityResult) {
            if(isDebugEnabled(requireContext())) {
                Log.d("Mooltifill", "onDefaultServiceSet() resultCode=$resultCode")
            }
            when (resultCode.resultCode) {
                Activity.RESULT_OK -> {
                    Toast.makeText(requireContext(), "Mooltifill enabled. Great!", Toast.LENGTH_SHORT).show()
                    mDefaultServiceSet = true
                }
                Activity.RESULT_CANCELED -> {

                }
            }
        }
    }
}
