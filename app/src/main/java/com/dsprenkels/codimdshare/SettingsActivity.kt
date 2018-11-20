package com.dsprenkels.codimdshare

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.preference.*
import android.util.Log
import java.util.logging.Logger


/**
 * This [SettingsActivity] lists all the settings of the CodiMDShare app.
 */
class SettingsActivity : AppCompatPreferenceActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the default values (if not set)
        PreferenceManager.setDefaultValues(this, R.xml.pref_all, false);

        // Handle preference change events
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)

        // Replace this activity with AllPreferenceFragment
        fragmentManager.beginTransaction().replace(android.R.id.content, AllPreferenceFragment()).commit()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == "base_url") {
            val value = sharedPreferences.getString(key, null)!!;

            // Prepend "https://" if needed
            if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) {
                val newValue = "https://$value"
                Log.i(this::class.java.name,"Setting $key preference to $newValue (added 'https://')")
                sharedPreferences.edit().putString(key, newValue).apply()
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    override fun onBuildHeaders(target: List<PreferenceActivity.Header>) {
        loadHeadersFromResource(R.xml.pref_headers, target)
    }

    /**
     * This method stops fragment injection in malicious applications.
     * Make sure to deny any unknown fragments here.
     */
    override fun isValidFragment(fragmentName: String): Boolean {
        return AllPreferenceFragment::class.java.name == fragmentName
    }

    /**
     * {@inheritDoc}
     */
    override fun onIsMultiPane(): Boolean {
        return isXLargeTablet(this)
    }

    /**
     * This fragment shows all the preferences. It is advised to skip the
     * headers and immediately go this this fragment when viewing the settings.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    class AllPreferenceFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.pref_all)
            setHasOptionsMenu(true)

            // Bind the summary of the preferences to their values
            bindPreferenceSummaryToValue(findPreference("base_url"))
        }

        private fun bindPreferenceSummaryToValue(bindPreference: Preference) {
            bindPreference.setOnPreferenceChangeListener{ preference: Preference?, newValue: Any? ->
                preference?.summary = newValue.toString()
                Log.i(this::class.java.name, "Setting changed to $newValue")
                true
            }

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (bindPreference is EditTextPreference) {
                bindPreference.summary = sharedPreferences.getString("base_url", null)
            } else {
                val cls = bindPreference.javaClass.name
                Log.w(this::class.java.name, "bindPreferenceSummaryToValue not implemented for $cls")
            }
        }
    }

    companion object {
        /**
         * Helper method to determine if the device has an extra-large screen. For
         * example, 10" tablets are extra-large.
         */
        private fun isXLargeTablet(context: Context): Boolean {
            return context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK >= Configuration.SCREENLAYOUT_SIZE_XLARGE
        }
    }
}
