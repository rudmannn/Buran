package corewala.buran.ui.settings

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.*
import corewala.buran.Buran
import corewala.buran.R
import java.security.SecureRandom
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory


const val PREFS_SET_CLIENT_CERT_REQ = 20

class SettingsFragment: PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {

    lateinit var prefs: SharedPreferences
    lateinit var protocols: Array<String>

    private lateinit var clientCertPref: Preference
    private lateinit var useClientCertPreference: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {

        prefs = preferenceManager.sharedPreferences

        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        /**
         * Buran App Settings
         */
        val appCategory = PreferenceCategory(context)
        appCategory.key = "app_category"
        appCategory.title = getString(R.string.configure_buran)
        screen.addPreference(appCategory)

        //Home ---------------------------------------------
        val homePreference = EditTextPreference(context)
        homePreference.title = getString(R.string.home_capsule)
        homePreference.key = "home_capsule"
        homePreference.dialogTitle = getString(R.string.home_capsule)

        val homecapsule = preferenceManager.sharedPreferences.getString(
            "home_capsule",
            Buran.DEFAULT_HOME_CAPSULE
        )

        homePreference.summary = homecapsule
        homePreference.positiveButtonText = getString(R.string.update)
        homePreference.negativeButtonText = getString(R.string.cancel)
        homePreference.title = getString(R.string.home_capsule)
        homePreference.setOnPreferenceChangeListener { _, newValue ->
            homePreference.summary = newValue.toString()
            true
        }
        homePreference.setOnBindEditTextListener{ editText ->
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.setSelection(editText.text.toString().length)//Set caret position to end
        }
        appCategory.addPreference(homePreference)

        //Home - Certificates
        buildClientCertificateSection(context, appCategory)

        //Theme --------------------------------------------
        buildThemeSection(context, appCategory)

        //Accessibility ------------------------------------
        buildsAccessibility(context, screen)

        //Web ----------------------------------------------
        buildWebSection(context, screen)

        //TLS ----------------------------------------------
        buildTLSSection(context, screen)

        preferenceScreen = screen
    }

    private fun buildWebSection(context: Context?, screen: PreferenceScreen){
        val webCategory = PreferenceCategory(context)
        webCategory.key = "web_category"
        webCategory.title = getString(R.string.web_content)
        screen.addPreference(webCategory)

        val customTabInfo = Preference(context)
        customTabInfo.summary = getString(R.string.web_content_label)
        webCategory.addPreference(customTabInfo)

        val useCustomTabsPreference = SwitchPreferenceCompat(context)
        useCustomTabsPreference.setDefaultValue(true)
        useCustomTabsPreference.key = Buran.PREF_KEY_USE_CUSTOM_TAB
        useCustomTabsPreference.title = getString(R.string.web_content_switch_label)
        webCategory.addPreference(useCustomTabsPreference)

    }

    private fun buildThemeSection(context: Context?, appCategory: PreferenceCategory) {
        val themeCategory = PreferenceCategory(context)
        themeCategory.key = "theme_category"
        themeCategory.title = getString(R.string.theme)
        appCategory.addPreference(themeCategory)

        val themeFollowSystemPreference = SwitchPreferenceCompat(context)
        themeFollowSystemPreference.key = "theme_FollowSystem"
        themeFollowSystemPreference.title = getString(R.string.system_default)
        themeFollowSystemPreference.onPreferenceChangeListener = this
        themeCategory.addPreference(themeFollowSystemPreference)

        val themeLightPreference = SwitchPreferenceCompat(context)
        themeLightPreference.key = "theme_Light"
        themeLightPreference.title = getString(R.string.light)
        themeLightPreference.onPreferenceChangeListener = this
        themeCategory.addPreference(themeLightPreference)

        val themeDarkPreference = SwitchPreferenceCompat(context)
        themeDarkPreference.key = "theme_Dark"
        themeDarkPreference.title = getString(R.string.dark)
        themeDarkPreference.onPreferenceChangeListener = this
        themeCategory.addPreference(themeDarkPreference)


        val isThemePrefSet =
            prefs.getBoolean("theme_FollowSystem", false) ||
                    prefs.getBoolean("theme_Light", false) ||
                    prefs.getBoolean("theme_Dark", false)
        if (!isThemePrefSet) themeFollowSystemPreference.isChecked = true

        val coloursCSV = resources.openRawResource(R.raw.colours).bufferedReader().use { it.readLines() }

        val labels = mutableListOf<String>()
        val values = mutableListOf<String>()

        coloursCSV.forEach{ line ->
            val colour = line.split(",")
            labels.add(colour[0])
            values.add(colour[1])
        }

        val backgroundColourPreference = ListPreference(context)
        backgroundColourPreference.key = "background_colour"
        backgroundColourPreference.setDialogTitle(R.string.prefs_override_page_background_dialog_title)
        backgroundColourPreference.setTitle(R.string.prefs_override_page_background_title)
        backgroundColourPreference.setSummary(R.string.prefs_override_page_background)
        backgroundColourPreference.setDefaultValue("#XXXXXX")
        backgroundColourPreference.entries = labels.toTypedArray()
        backgroundColourPreference.entryValues = values.toTypedArray()

        backgroundColourPreference.setOnPreferenceChangeListener { _, colour ->
            when (colour) {
                "#XXXXXX" -> this.view?.background = null
                else -> this.view?.background = ColorDrawable(Color.parseColor("$colour"))
            }

            true
        }

        themeCategory.addPreference(backgroundColourPreference)
    }

    private fun buildsAccessibility(context: Context?, screen: PreferenceScreen){
        val accessibilityCategory = PreferenceCategory(context)
        accessibilityCategory.key = "accessibility_category"
        accessibilityCategory.title = getString(R.string.accessibility)
        screen.addPreference(accessibilityCategory)

        //Accessibility - code blocks
        val aboutCodeBlocksPref = Preference(context)
        aboutCodeBlocksPref.summary = getString(R.string.collapse_code_blocks_about)
        accessibilityCategory.addPreference(aboutCodeBlocksPref)

        val collapseCodeBlocksPreference = SwitchPreferenceCompat(context)
        collapseCodeBlocksPreference.key = "collapse_code_blocks"
        collapseCodeBlocksPreference.title = getString(R.string.collapse_code_blocks)
        accessibilityCategory.addPreference(collapseCodeBlocksPreference)

        //Accessibility - large text and buttons
        val largeGemtextPreference = SwitchPreferenceCompat(context)
        largeGemtextPreference.key = "use_large_gemtext_adapter"
        largeGemtextPreference.title = getString(R.string.large_gemtext_and_button)
        accessibilityCategory.addPreference(largeGemtextPreference)

        //Accessibility - inline icons
        val showInlineIconsPreference = SwitchPreferenceCompat(context)
        showInlineIconsPreference.setDefaultValue(true)
        showInlineIconsPreference.key = "show_inline_icons"
        showInlineIconsPreference.title = getString(R.string.show_inline_icons)
        accessibilityCategory.addPreference(showInlineIconsPreference)
    }

    private fun buildTLSSection(context: Context?, screen: PreferenceScreen) {
        val tlsCategory = PreferenceCategory(context)
        tlsCategory.key = "tls_category"
        tlsCategory.title = getString(R.string.tls_config)
        screen.addPreference(tlsCategory)

        val tlsDefaultPreference = SwitchPreferenceCompat(context)
        tlsDefaultPreference.key = "tls_Default"
        tlsDefaultPreference.title = getString(R.string.tls_default)
        tlsDefaultPreference.onPreferenceChangeListener = this
        tlsCategory.addPreference(tlsDefaultPreference)

        //This feel inelegant:
        var tlsPrefSet = false
        prefs.all.forEach { pref ->
            if (pref.key.startsWith("tls_")) tlsPrefSet = true
        }

        if (!tlsPrefSet) {
            tlsDefaultPreference.isChecked = true
        }

        val tlsAllSupportedPreference = SwitchPreferenceCompat(context)
        tlsAllSupportedPreference.key = "tls_All_Supported"
        tlsAllSupportedPreference.title = getString(R.string.tls_enable_all_supported)
        tlsAllSupportedPreference.onPreferenceChangeListener = this
        tlsCategory.addPreference(tlsAllSupportedPreference)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, SecureRandom())
        val factory: SSLSocketFactory = sslContext.socketFactory
        val socket = factory.createSocket() as SSLSocket
        protocols = socket.supportedProtocols
        protocols.forEach { protocol ->
            val tlsPreference = SwitchPreferenceCompat(context)
            tlsPreference.key = "tls_${protocol.toLowerCase(Locale.getDefault())}"
            tlsPreference.title = protocol
            tlsPreference.onPreferenceChangeListener = this
            tlsCategory.addPreference(tlsPreference)
        }
    }

    private fun buildClientCertificateSection(context: Context?, appCategory: PreferenceCategory) {
        if (Buran.FEATURE_CLIENT_CERTS) {

            val aboutPref = Preference(context)
            aboutPref.key = "unused_pref"
            aboutPref.summary = getString(R.string.pkcs_notice)
            aboutPref.isPersistent = false
            aboutPref.isSelectable = false
            appCategory.addPreference(aboutPref)

            clientCertPref = Preference(context)
            clientCertPref.title = getString(R.string.client_certificate)
            clientCertPref.key = Buran.PREF_KEY_CLIENT_CERT_HUMAN_READABLE

            val clientCertUriHumanReadable = preferenceManager.sharedPreferences.getString(
                Buran.PREF_KEY_CLIENT_CERT_HUMAN_READABLE,
                null
            )

            val hasCert = clientCertUriHumanReadable != null
            if (!hasCert) {
                clientCertPref.summary = getString(R.string.tap_to_select_client_certificate)
            } else {
                clientCertPref.summary = clientCertUriHumanReadable
            }

            clientCertPref.setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    type = "*/*"
                }
                startActivityForResult(intent, PREFS_SET_CLIENT_CERT_REQ)
                true
            }

            appCategory.addPreference(clientCertPref)


            val clientCertPassword = EditTextPreference(context)
            clientCertPassword.key = Buran.PREF_KEY_CLIENT_CERT_PASSWORD
            clientCertPassword.title = getString(R.string.client_certificate_password)

            val certPasword = preferenceManager.sharedPreferences.getString(
                Buran.PREF_KEY_CLIENT_CERT_PASSWORD,
                null
            )
            if (certPasword != null && certPasword.isNotEmpty()) {
                clientCertPassword.summary = getDots(certPasword)
            } else {
                clientCertPassword.summary = getString(R.string.no_password)
            }
            clientCertPassword.dialogTitle = getString(R.string.client_certificate_password)
            clientCertPassword.setOnPreferenceChangeListener { _, newValue ->
                val passphrase = "$newValue"
                if (passphrase.isEmpty()) {
                    clientCertPassword.summary = getString(R.string.no_password)
                } else {
                    clientCertPassword.summary = getDots(passphrase)
                }

                true//update the value
            }

            appCategory.addPreference(clientCertPassword)

            useClientCertPreference = SwitchPreferenceCompat(context)
            useClientCertPreference.key = Buran.PREF_KEY_CLIENT_CERT_ACTIVE
            useClientCertPreference.title = getString(R.string.use_client_certificate)
            appCategory.addPreference(useClientCertPreference)

            if (!hasCert) {
                useClientCertPreference.isVisible = false
            }
        }
    }

    private fun getDots(value: String): String {
        val sb = StringBuilder()
        repeat(value.length){
            sb.append("•")
        }
        return sb.toString()
    }

    override fun onPreferenceChange(preference: Preference?, newValue: Any?): Boolean {
        if(preference == null) return false

        if(preference.key.startsWith("tls")){
            tlsChangeListener(preference, newValue)
            return true
        }

        if(preference.key.startsWith("theme")){
            when(preference.key){
                "theme_FollowSystem" -> {
                    preferenceScreen.findPreference<SwitchPreferenceCompat>("theme_Light")?.isChecked =
                        false
                    preferenceScreen.findPreference<SwitchPreferenceCompat>("theme_Dark")?.isChecked =
                        false
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
                "theme_Light" -> {
                    preferenceScreen.findPreference<SwitchPreferenceCompat>("theme_FollowSystem")?.isChecked =
                        false
                    preferenceScreen.findPreference<SwitchPreferenceCompat>("theme_Dark")?.isChecked =
                        false
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                "theme_Dark" -> {
                    preferenceScreen.findPreference<SwitchPreferenceCompat>("theme_FollowSystem")?.isChecked =
                        false
                    preferenceScreen.findPreference<SwitchPreferenceCompat>("theme_Light")?.isChecked =
                        false
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
            return true
        }
        return false
    }

    private fun tlsChangeListener(
        preference: Preference?, newValue: Any?
    ) {
        if (preference is SwitchPreferenceCompat && newValue is Boolean && newValue == true) {
            preference.key?.let { key ->
                when {
                    key.startsWith("tls_") -> {
                        if (key != "tls_Default") {
                            val default = preferenceScreen.findPreference<SwitchPreferenceCompat>("tls_Default")
                            default?.isChecked = false
                        }
                        if (key != "tls_All_Supported") {
                            val all = preferenceScreen.findPreference<SwitchPreferenceCompat>("tls_All_Supported")
                            all?.isChecked = false
                        }
                        protocols.forEach { protocol ->
                            val tlsSwitchKey = "tls_${protocol.toLowerCase(Locale.getDefault())}"
                            if (tlsSwitchKey != key) {
                                val otherTLSSwitch =
                                    preferenceScreen.findPreference<SwitchPreferenceCompat>(
                                        tlsSwitchKey
                                    )
                                otherTLSSwitch?.isChecked = false
                            }
                        }
                    }
                }
            }

            when (preference.key) {
                "tls_Default" -> setTLSProtocol("TLS")
                "tls_All_Supported" -> setTLSProtocol("TLS_ALL")
                else -> {
                    val prefTitle = preference.title.toString()
                    setTLSProtocol(prefTitle)
                }
            }
        }
    }

    private fun setTLSProtocol(protocol: String) = preferenceManager.sharedPreferences.edit().putString(
        "tls_protocol",
        protocol
    ).apply()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(requestCode == PREFS_SET_CLIENT_CERT_REQ && resultCode == RESULT_OK){
            data?.data?.also { uri ->
                preferenceManager.sharedPreferences.edit().putString(
                    Buran.PREF_KEY_CLIENT_CERT_URI,
                    uri.toString()
                ).apply()
                persistPermissions(uri)
                findFilename(uri)
           }

        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun persistPermissions(uri: Uri) {
        val contentResolver = requireContext().contentResolver

        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)
    }

    private fun findFilename(uri: Uri) {

        var readableReference = uri.toString()
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    readableReference = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
        }

        preferenceManager.sharedPreferences.edit().putString(
            Buran.PREF_KEY_CLIENT_CERT_HUMAN_READABLE,
            readableReference
        ).apply()
        clientCertPref.summary = readableReference
        useClientCertPreference.isChecked = true
    }
}
