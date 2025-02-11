package corewala.buran.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabsIntent
import androidx.databinding.DataBindingUtil
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import corewala.*
import corewala.buran.BuildConfig
import corewala.buran.Buran
import corewala.buran.OmniTerm
import corewala.buran.R
import corewala.buran.databinding.ActivityGemBinding
import corewala.buran.io.GemState
import corewala.buran.io.database.BuranDatabase
import corewala.buran.io.database.bookmarks.BookmarksDatasource
import corewala.buran.io.gemini.Datasource
import corewala.buran.io.gemini.GeminiResponse
import corewala.buran.io.keymanager.BuranBiometricManager
import corewala.buran.io.update.BuranUpdates
import corewala.buran.ui.bookmarks.BookmarkDialog
import corewala.buran.ui.bookmarks.BookmarksDialog
import corewala.buran.ui.content_image.ImageDialog
import corewala.buran.ui.content_text.TextDialog
import corewala.buran.ui.gemtext_adapter.AbstractGemtextAdapter
import corewala.buran.ui.modals_menus.history.HistoryDialog
import corewala.buran.ui.modals_menus.overflow.OverflowPopup
import corewala.buran.ui.settings.SettingsActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI


const val CREATE_IMAGE_FILE_REQ = 628
const val CREATE_BINARY_FILE_REQ = 630
const val CREATE_BOOKMARK_EXPORT_FILE_REQ = 631
const val CREATE_BOOKMARK_IMPORT_FILE_REQ = 632

class GemActivity : AppCompatActivity() {

    lateinit var prefs: SharedPreferences
    private var inSearch = false
    private lateinit var bookmarkDatasource: BookmarksDatasource
    private lateinit var db: BuranDatabase
    private var bookmarksDialog: BookmarksDialog? = null

    private val model by viewModels<GemViewModel>()
    private lateinit var binding: ActivityGemBinding

    private val omniTerm = OmniTerm(object : OmniTerm.Listener {
        override fun request(address: String) {
            gemRequest(address)
        }

        override fun openExternal(address: String) = openExternalLink(address)
    })

    private var certPassword: String? = null

    private var proxiedAddress: String? = null

    private var previousPosition: Int = 0

    private var initialised: Boolean = false

    private var goingBack: Boolean = false

    lateinit var adapter: AbstractGemtextAdapter

    private lateinit var home: String

    private lateinit var searchBase: String

    private val onLink: (link: URI, longTap: Boolean, adapterPosition: Int) -> Unit = { uri, longTap, _: Int ->
        if(longTap){
            val globalURI = omniTerm.getGlobalUri(uri.toString())
            Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, globalURI)
                type = "text/plain"
                startActivity(Intent.createChooser(this, null))
            }
        }else{
            //Reset input text hint after user has been searching
            if(inSearch) {
                binding.addressEdit.hint = getString(R.string.main_input_hint)
                inSearch = false
            }

            omniTerm.navigation(uri.toString())
        }
    }

    private val inlineImage: (link: URI, adapterPosition: Int) -> Unit = { uri, position: Int ->
        if(getInternetStatus()){
            omniTerm.imageAddress(uri.toString())
            val clientCertPassword = if(isHostSigned(uri)){
                certPassword
            }else{
                null
            }
            omniTerm.uri.let{
                model.requestInlineImage(URI.create(it.toString()), clientCertPassword){ imageUri ->
                    imageUri?.let{
                        runOnUiThread {
                            loadImage(position, imageUri)
                            loadingView(false)
                        }
                    }
                }
            }
        }
    }

    private fun loadImage(position: Int, uri: Uri) {
        adapter.loadImage(position, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_gem)
        binding.viewmodel = model
        binding.lifecycleOwner = this

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        binding.gemtextRecycler.layoutManager = LinearLayoutManager(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        when (prefs.getString("theme", "theme_FollowSystem")) {
            "theme_FollowSystem" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            "theme_Light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "theme_Dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        adapter = AbstractGemtextAdapter.getAdapter(onLink, inlineImage)

        binding.gemtextRecycler.adapter = adapter

        home = prefs.getString(
            "home_capsule",
            Buran.DEFAULT_HOME_CAPSULE
        ) ?: Buran.DEFAULT_HOME_CAPSULE

        if(
            !home.startsWith("gemini://")
            or home.contains(" ")
            or !home.contains(".")
        ){
            home = ""
        }

        searchBase = prefs.getString(
            "search_base",
            Buran.DEFAULT_SEARCH_BASE
        ) ?: Buran.DEFAULT_SEARCH_BASE

        if(
            !searchBase.startsWith("gemini://")
            or searchBase.contains(" ")
            or !searchBase.contains(".")
            or !searchBase.endsWith("?")
        ){
            searchBase = Buran.DEFAULT_SEARCH_BASE
        }

        if(getInternetStatus()){
            initialise()
        }else{
            loadingView(false)
            val home = PreferenceManager.getDefaultSharedPreferences(this).getString(
                "home_capsule",
                Buran.DEFAULT_HOME_CAPSULE
            )
            val title = "# ${this.getString(R.string.no_internet)}"
            val text = this.getString(R.string.retry)
            omniTerm.set(home!!)
            adapter.render(listOf(title, text))
            binding.addressEdit.inputType = InputType.TYPE_NULL
        }

        binding.addressEdit.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_GO -> {
                    omniTerm.input(binding.addressEdit.text.toString().trim(), searchBase)
                    binding.addressEdit.clearFocus()
                    return@setOnEditorActionListener true
                }
                else -> return@setOnEditorActionListener false
            }
        }

        binding.addressEdit.setOnClickListener {
            binding.addressEdit.requestFocus()
        }

        binding.addressEdit.setOnFocusChangeListener { _, hasFocus ->

            val addressPaddingRight = resources.getDimensionPixelSize(R.dimen.def_address_right_margin)

            if(!hasFocus) {
                binding.addressEdit.hideKeyboard()
            }

            binding.addressEdit.setPadding(
                binding.addressEdit.paddingLeft,
                binding.addressEdit.paddingTop,
                addressPaddingRight,
                binding.addressEdit.paddingBottom,
            )
        }

        binding.more.setOnClickListener {
            OverflowPopup.show(binding.more){ menuId ->
                when (menuId) {
                    R.id.overflow_menu_search -> {
                        binding.addressEdit.hint = getString(R.string.main_input_search_hint)
                        binding.addressEdit.text?.clear()
                        binding.addressEdit.requestFocus()
                        inSearch = true
                    }
                    R.id.overflow_menu_sign -> {
                        if (prefs.getBoolean("use_biometrics", false) and certPassword.isNullOrEmpty()) {
                            biometricSecureRequest(binding.addressEdit.text.toString())
                        }else if(certPassword.isNullOrEmpty()){
                            if (certPassword.isNullOrEmpty()) {
                                certPassword = prefs.getString(
                                    Buran.PREF_KEY_CLIENT_CERT_PASSWORD,
                                    null
                                )
                            }
                            refresh()
                            Toast.makeText(this, this.getString(R.string.cert_loaded), Toast.LENGTH_SHORT).show()
                        }else{
                            certPassword = null
                            refresh()
                            Toast.makeText(this, this.getString(R.string.cert_unloaded), Toast.LENGTH_SHORT).show()
                        }
                    }

                    R.id.overflow_menu_bookmark -> {
                        val name = adapter.inferTitle()
                        BookmarkDialog(
                            this,
                            BookmarkDialog.mode_new,
                            bookmarkDatasource,
                            binding.addressEdit.text.toString(),
                            name ?: ""
                        ) { _, _ ->
                        }.show()
                    }
                    R.id.overflow_menu_bookmarks -> {
                        bookmarksDialog = BookmarksDialog(this, bookmarkDatasource) { bookmark ->
                            gemRequest(bookmark.uri.toString())
                        }
                        bookmarksDialog?.show()
                    }
                    R.id.overflow_menu_share -> {
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, binding.addressEdit.text.toString())
                            type = "text/plain"
                            startActivity(Intent.createChooser(this, null))
                        }
                    }
                    R.id.overflow_menu_history -> HistoryDialog.show(
                        this,
                        db.history(),
                        omniTerm
                    ) { historyAddress ->
                        gemRequest(historyAddress)
                    }
                    R.id.overflow_menu_about -> gemRequest("")
                    R.id.overflow_menu_settings -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                }
            }
            if(!prefs.getString(Buran.PREF_KEY_CLIENT_CERT_URI, null).isNullOrEmpty()){
                OverflowPopup.setItemVisibility(R.id.overflow_menu_sign, true)
                if(certPassword.isNullOrEmpty()){
                    OverflowPopup.setItemTitle(R.id.overflow_menu_sign, getString(R.string.load_cert))
                }else{
                    OverflowPopup.setItemTitle(R.id.overflow_menu_sign, getString(R.string.unload_cert))
                }
            }else{
                OverflowPopup.setItemVisibility(R.id.overflow_menu_sign, false)
            }
        }

        binding.home.setOnClickListener {
            omniTerm.history.clear()
            gemRequest(home, false)
        }

        binding.pullToRefresh.setOnRefreshListener {
            if(getInternetStatus()){
                refresh()
            }else{
                binding.pullToRefresh.isRefreshing = false
                Snackbar.make(binding.root, getString(R.string.no_internet), Snackbar.LENGTH_LONG).show()
            }
        }

        checkIntentExtras(intent)
    }

    private fun refresh(){
        omniTerm.getCurrent().run{
            binding.addressEdit.setText(this)
            focusEnd()
            gemRequest(this)
        }
    }

    override fun onResume() {
        super.onResume()

        home = prefs.getString(
            "home_capsule",
            Buran.DEFAULT_HOME_CAPSULE
        ) ?: Buran.DEFAULT_HOME_CAPSULE

        if(
            !home.startsWith("gemini://")
            or home.contains(" ")
            or !home.contains(".")
        ){
            home = ""
        }

        searchBase = prefs.getString(
            "search_base",
            Buran.DEFAULT_SEARCH_BASE
        ) ?: Buran.DEFAULT_SEARCH_BASE

        if(
            !searchBase.startsWith("gemini://")
            or searchBase.contains(" ")
            or !searchBase.contains(".")
            or !searchBase.endsWith("?")
        ){
            searchBase = Buran.DEFAULT_SEARCH_BASE
        }

        when {
            prefs.contains("background_colour") -> {
                when (val backgroundColor = prefs.getString("background_colour", "#XXXXXX")) {
                    "#XXXXXX" -> binding.rootCoord.background = null
                    else -> binding.rootCoord.background = ColorDrawable(Color.parseColor("$backgroundColor"))
                }
            }
        }

        val showInlineIcons = prefs.getBoolean(
            "show_inline_icons",
            true
        )
        adapter.inlineIcons(showInlineIcons)

        val showLinkButtons = prefs.getBoolean(
            "show_link_buttons",
            false
        )
        adapter.linkButtons(showLinkButtons)

        val useAttentionGuides = prefs.getBoolean(
            "use_attention_guides",
            false
        )
        adapter.attentionGuides(useAttentionGuides)


        val showInlineImages = prefs.getBoolean(
            "show_inline_images",
            true
        )
        adapter.inlineImages(showInlineImages)

        if(getInternetStatus()){
            model.invalidateDatasource()
        }
    }

    private fun updateClientCertIcon(){
        if (!prefs.getString(
                Buran.PREF_KEY_CLIENT_CERT_URI,
                null
            ).isNullOrEmpty()){
            if(certPassword.isNullOrEmpty()){
                binding.addressEdit.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    0,
                    0,
                    0
                )
            }else{
                binding.addressEdit.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.vector_client_cert,
                    0,
                    0,
                    0
                )
            }
        }
    }

    private fun handleState(state: GemState) {
        binding.pullToRefresh.isRefreshing = false

        when (state) {
            is GemState.AppQuery -> runOnUiThread { showAlert("App backdoor/query not implemented yet") }

            is GemState.ResponseInput -> runOnUiThread {
                val builder = AlertDialog.Builder(this, R.style.AppDialogTheme)
                val inflater: LayoutInflater = layoutInflater
                val dialogLayout: View = inflater.inflate(R.layout.dialog_input_query, null)
                val editText: EditText = dialogLayout.findViewById(R.id.query_input)
                editText.requestFocus()
                editText.showKeyboard()
                loadingView(false)
                builder
                    .setTitle(state.header.meta)
                    .setPositiveButton(getString(R.string.confirm).toUpperCase()){ _, _ ->
                        gemRequest("${state.uri}?${Uri.encode(editText.text.toString())}")
                        editText.hideKeyboard()
                    }
                    .setNegativeButton(getString(R.string.cancel).toUpperCase()){ _, _ ->
                        editText.hideKeyboard()
                    }
                    .setView(dialogLayout)
                    .show()
            }

            is GemState.Redirect -> gemRequest(omniTerm.getGlobalUri(state.uri))

            is GemState.ClientCertRequired -> runOnUiThread {
                loadingView(false)
                val builder = AlertDialog.Builder(this, R.style.AppDialogTheme)
                builder
                    .setTitle(getString(R.string.client_certificate_required))
                    .setMessage(state.header.meta)

                if(!prefs.getString(Buran.PREF_KEY_CLIENT_CERT_URI, null).isNullOrEmpty()){
                    builder
                        .setPositiveButton(getString(R.string.use_client_certificate).toUpperCase()) { _, _ ->
                            if(prefs.getBoolean("use_biometrics", false) and certPassword.isNullOrEmpty()){
                                biometricSecureRequest(state.uri.toString())
                            }else{
                                if(certPassword.isNullOrEmpty()){
                                    certPassword = prefs.getString(
                                        Buran.PREF_KEY_CLIENT_CERT_PASSWORD,
                                        null
                                    )
                                }
                                gemRequest(state.uri.toString(), true)
                                Toast.makeText(this, this.getString(R.string.cert_loaded), Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel).toUpperCase()) { _, _ -> }
                        .show()
                }else{
                    builder
                        .setNegativeButton(getString(R.string.close).toUpperCase()) { _, _ -> }
                        .show()
                }
            }

            is GemState.Requesting -> {
                loadingView(true)
            }
            is GemState.NotGeminiRequest -> {
                externalProtocol(state.uri)
            }
            is GemState.ResponseError -> {
                omniTerm.reset()
                showAlert("${GeminiResponse.getCodeString(state.header.code)}:\n\n${state.header.meta}")
            }
            is GemState.ClientCertError -> {
                certPassword = null
                updateClientCertIcon()
                showAlert("${GeminiResponse.getCodeString(state.header.code)}:\n\n${state.header.meta}")
            }
            is GemState.ResponseGemtext -> {
                if(state.uri.scheme != "gemini"){
                    Snackbar.make(binding.root, getString(R.string.proxied_content), Snackbar.LENGTH_LONG).setAction(getString(R.string.open_original)) {
                        externalProtocol(state.uri)
                    }.show()
                }
                renderGemtext(state)
            }
            is GemState.ResponseText -> renderText(state)
            is GemState.ResponseImage -> renderImage(state)
            is GemState.ResponseBinary -> renderBinary(state)
            is GemState.Blank -> {
                binding.addressEdit.setText("")
                adapter.render(arrayListOf())
            }
            is GemState.ResponseUnknownMime -> {
                runOnUiThread {
                    loadingView(false)

                    val download = getString(R.string.download)

                    if(getInternetStatus()) {
                        val clientCertPassword = if(isHostSigned(state.uri)){
                            certPassword
                        }else{
                            null
                        }
                        AlertDialog.Builder(this, R.style.AppDialogTheme)
                            .setTitle("$download: ${state.header.meta}")
                            .setMessage("${state.uri}")
                            .setPositiveButton(getString(R.string.download).toUpperCase()) { _, _ ->
                                loadingView(true)
                                model.requestBinaryDownload(state.uri, clientCertPassword, null)
                            }
                            .setNegativeButton(getString(R.string.cancel).toUpperCase()) { _, _ -> }
                            .show()
                    }else{
                        Snackbar.make(binding.root, getString(R.string.no_internet), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            is GemState.ResponseUnknownHost -> {
                omniTerm.reset()
                runOnUiThread {
                    loadingView(false)
                    AlertDialog.Builder(this, R.style.AppDialogTheme)
                        .setTitle(getString(R.string.unknown_host))
                        .setMessage("${getString(R.string.unknown_host)}: ${state.uri}\n\n${getString(R.string.search_instead)}")
                        .setPositiveButton(getString(R.string.search).toUpperCase()) { _, _ ->
                            loadingView(true)
                            omniTerm.search(state.uri.toString(), searchBase)
                        }
                        .setNegativeButton(getString(R.string.cancel).toUpperCase()) { _, _ -> }
                        .show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        intent?.let{
            checkIntentExtras(intent)
        }
    }

    /**
     *
     * Checks intent to see if Activity was opened to handle selected text
     *
     */
    private fun checkIntentExtras(intent: Intent) {

        //From clicking a gemini:// address
        val uri = intent.data
        if(uri != null){
            binding.addressEdit.setText(uri.toString())
            gemRequest(uri.toString())
            return
        }
    }

    private fun biometricSecureRequest(address: String){
        val biometricManager = BuranBiometricManager()

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                println("Authentication error: $errorCode: $errString")
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                println("Authentication failed")
            }
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                println("Authentication succeeded")

                val ciphertext = biometricManager.decodeByteArray(
                    prefs.getString(
                        "password_ciphertext",
                        null
                    )!!
                )

                certPassword = biometricManager.decryptData(ciphertext, result.cryptoObject?.cipher!!)
                gemRequest(address, true)
                Toast.makeText(applicationContext, applicationContext.getString(R.string.cert_loaded), Toast.LENGTH_SHORT).show()
            }
        }

        val initializationVector = biometricManager.decodeByteArray(
            prefs.getString(
                "password_init_vector",
                null
            )!!
        )

        biometricManager.createBiometricPrompt(this, null, this, callback)
        biometricManager.authenticateToDecryptData(initializationVector)
    }

    private fun showAlert(message: String) = runOnUiThread{
        loadingView(false)

        if(message.length > 40){
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.error))
                .setMessage(message)
                .setPositiveButton(getString(R.string.close).toUpperCase()){ _, _ ->

                }
                .show()
        }else {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun externalProtocol(uri: URI) = runOnUiThread {
        loadingView(false)
        val uri = uri.toString()

        when {
            (uri.startsWith("http://") || uri.startsWith("https://")) -> openExternalLink(uri)
            else -> {
                val viewIntent = Intent(Intent.ACTION_VIEW)
                viewIntent.data = Uri.parse(uri.toString())

                try {
                    startActivity(viewIntent)
                }catch (e: ActivityNotFoundException){
                    showAlert(
                        String.format(
                            getString(R.string.no_app_installed_that_can_open),
                            uri
                        )
                    )
                }
            }
        }
    }

    private fun openExternalLink(address: String){
        if(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                Buran.PREF_KEY_USE_CUSTOM_TAB,
                true
            )or !address.startsWith("http")) {
            val builder = CustomTabsIntent.Builder()
            val intent = builder.build()

            try {
                intent.launchUrl(this, Uri.parse(address))
            }catch (e: ActivityNotFoundException){
                showAlert(
                    String.format(
                        getString(R.string.no_app_installed_that_can_open),
                        address
                    )
                )
            }
        }else{
            val viewIntent = Intent(Intent.ACTION_VIEW)
            viewIntent.data = Uri.parse(address)
            startActivity(viewIntent)
        }
    }

    private fun renderGemtext(state: GemState.ResponseGemtext) = runOnUiThread {
        loadingView(false)

        omniTerm.set(proxiedAddress ?: state.uri.toString())

        //todo - colours didn't change when switching themes, so disabled for now
        //val addressSpan = SpannableString(state.uri.toString())
        //addressSpan.set(0, 9, ForegroundColorSpan(resources.getColor(R.color.protocol_address)))
        binding.addressEdit.setText(state.uri.toString())

        if(!goingBack){
            previousPosition = (binding.gemtextRecycler.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
        }

        adapter.render(state.lines)

        //Scroll to correct position
        if(goingBack){
            println("Returning to previous position: $previousPosition")
            binding.gemtextRecycler.scrollToPosition(previousPosition)
            previousPosition = 0
            goingBack = false
        }else{
            binding.gemtextRecycler.post {
                binding.gemtextRecycler.scrollToPosition(0)
            }
        }

        focusEnd()
    }

    private fun renderText(state: GemState.ResponseText) = runOnUiThread {
        loadingView(false)
        TextDialog.show(this, state)
    }

    var imageState: GemState.ResponseImage? = null
    var binaryState: GemState.ResponseBinary? = null

    private fun renderImage(state: GemState.ResponseImage) = runOnUiThread{
        loadingView(false)
        ImageDialog.show(this, state){ state ->
            imageState = state
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_TITLE, File(state.uri.path).name)
            startActivityForResult(intent, CREATE_IMAGE_FILE_REQ)
        }
    }

    private fun renderBinary(state: GemState.ResponseBinary) = runOnUiThread{
        loadingView(false)
        binaryState = state
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = state.header.meta
        intent.putExtra(Intent.EXTRA_TITLE, File(state.uri.path).name)
        startActivityForResult(intent, CREATE_BINARY_FILE_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(resultCode == RESULT_OK && (requestCode == CREATE_IMAGE_FILE_REQ || requestCode == CREATE_BINARY_FILE_REQ)){
            //todo - tidy this mess up... refactor - none of this should be here
            if(imageState == null && binaryState == null) return
            data?.data?.let{ uri ->
                val cachedFile = when {
                    imageState != null -> File(imageState!!.cacheUri.path ?: "")
                    binaryState != null -> File(binaryState!!.cacheUri.path ?: "")
                    else -> {
                        println("File download error - no state object exists")
                        showAlert(getString(R.string.no_state_object_exists))
                        null
                    }
                }

                cachedFile?.let{
                    contentResolver.openFileDescriptor(uri, "w")?.use { fileDescriptor ->
                        FileOutputStream(fileDescriptor.fileDescriptor).use { destOutput ->
                            val sourceChannel = FileInputStream(cachedFile).channel
                            val destChannel = destOutput.channel
                            sourceChannel.transferTo(0, sourceChannel.size(), destChannel)
                            sourceChannel.close()
                            destChannel.close()

                            cachedFile.deleteOnExit()

                            if(binaryState != null){
                                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                            }else{
                                Snackbar.make(
                                    binding.root,
                                    getString(R.string.file_saved_to_device),
                                    Snackbar.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }

            imageState = null
            binaryState = null
        }else if(resultCode == RESULT_OK && requestCode == CREATE_BOOKMARK_EXPORT_FILE_REQ){
            data?.data?.let{ uri ->
                bookmarksDialog?.bookmarksExportFileReady(uri)
            }
        }else if(resultCode == RESULT_OK && requestCode == CREATE_BOOKMARK_IMPORT_FILE_REQ){
            data?.data?.let{ uri ->
                bookmarksDialog?.bookmarksImportFileReady(uri)
            }
        }
    }

    private fun loadingView(visible: Boolean) = runOnUiThread {
        binding.progressBar.visibleRetainingSpace(visible)
        if(visible) binding.appBar.setExpanded(true)
    }

    private fun getInternetStatus(): Boolean {
        val connectivityManager = this.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if(capabilities != null){
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> { return true }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> { return true }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> { return true }
                }
            }
        }else{
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            if(activeNetworkInfo != null && activeNetworkInfo.isConnected){
                return true
            }
        }
        return false
    }

    private fun initialise(){
        db = BuranDatabase(applicationContext)
        bookmarkDatasource = db.bookmarks()

        if(intent.data == null){
            model.initialise(
                home = home,
                gemini = Datasource.factory(this, db.history()),
                db = db,
                onState = this::handleState
            )
            if(home.isEmpty()){
                loadLocalHome()
            }
        }else{
            model.initialise(
                home = intent.data.toString(),
                gemini = Datasource.factory(this, db.history()),
                db = db,
                onState = this::handleState
            )
        }

        if(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                "check_for_updates",
                false
            )){
            val updates = BuranUpdates()
            val latestVersion = updates.getLatestVersion()

            if (latestVersion == BuildConfig.VERSION_NAME){
                println("No new version available")
            } else {
                println("New version available")

                Snackbar.make(binding.root, getString(R.string.new_version_available), Snackbar.LENGTH_LONG).setAction(getString(R.string.update)) {
                    updates.installUpdate(this, latestVersion)
                }.show()
            }
        }

        initialised = true
    }

    private fun loadLocalHome(){
        loadingView(false)
        binding.pullToRefresh.isRefreshing = false
        binding.addressEdit.text?.clear()

        val title = "# ${getString(R.string.app_name)}"
        val sourceLink = "=> https://github.com/Corewala/Buran ${getString(R.string.source)}"
        adapter.render(listOf(
            title,
            "",
            getString(R.string.about_body),
            "",
            getString(R.string.about_ariane_source),
            "",
            getString(R.string.about_font),
            "",
            getString(R.string.about_glyphs),
            "",
            sourceLink,
            getString(R.string.copyright)
        ))
        omniTerm.set("")
    }

    private fun isHostSigned(uri: URI): Boolean{
        if((uri.host != omniTerm.getCurrent().toURI().host) && !certPassword.isNullOrEmpty()) {
            return false
        }
        return true
    }

    private fun gemRequest(address: String, sign: Boolean?){
        if(sign == null){
            if(!isHostSigned(address.toURI())) certPassword = null
        }else if(!sign){
            certPassword = null
        }
        updateClientCertIcon()

        if(address.startsWith("http://") or address.startsWith("https://")){
            val httpProxy = prefs.getString("http_proxy", null) ?: ""

            if(
                httpProxy.isNullOrEmpty()
                or !httpProxy.startsWith("gemini://")
                or httpProxy.contains(" ")
                or !httpProxy.contains(".")
            ){
                openExternalLink(address)
            }else{
                model.request(httpProxy, certPassword, address)
            }
        }

        if(getInternetStatus()){
            if(initialised){
                if(address.isEmpty()){
                    loadLocalHome()
                }else{
                    model.request(address, certPassword, null)
                }
            }else{
                initialise()
            }
        }else{
            Snackbar.make(binding.root, getString(R.string.no_internet), Snackbar.LENGTH_LONG).show()
            loadingView(false)
        }
    }

    private fun gemRequest(address: String){
        gemRequest(address, null)
    }

    override fun onBackPressed() {
        if(model.isRequesting()){
            model.cancel()
            loadingView(false)
        }else if(omniTerm.canGoBack()){
            goingBack = true
            gemRequest(omniTerm.goBack())
        }else{
            println("Buran history is empty - exiting")
            super.onBackPressed()
            cacheDir.deleteRecursively()
        }
    }

    private fun focusEnd(){
        binding.addressEdit.setSelection(binding.addressEdit.text?.length ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val uri = binding.addressEdit.text.toString()
        outState.putString("uri", uri)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString("uri")?.run {
            omniTerm.set(this)
            binding.addressEdit.setText(this)
            gemRequest(this)
        }
    }
}