// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.util.Patterns
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.core.debug.DebugRootDescription
import com.gitlab.mudlej.MjPdfReader.core.debug.MainThreadStallWatchdog
import com.gitlab.mudlej.MjPdfReader.core.debug.ReaderDebugStateSource
import com.gitlab.mudlej.MjPdfReader.core.ui.confirmDialog
import com.gitlab.mudlej.MjPdfReader.data.*
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PasswordDialogBinding
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.imageShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.pdfShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.plainTextShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.publicDownloadsCopy
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightMode
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlay
import com.gitlab.mudlej.MjPdfReader.core.ui.clearIncognitoNightMode
import com.gitlab.mudlej.MjPdfReader.core.ui.showOptionalIcons
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.PdfPropertiesSummary
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReaderAnnotationDebug
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentUnreachableException
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.LoadState
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsPage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.shockwave.pdfium.PdfPasswordException
import java.io.File
import java.io.FileNotFoundException
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), ReaderUi {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var pref: Preferences
    private lateinit var reader: ReaderComposition

    private val vm: ReaderViewModel by viewModels()
    private val pdf: DocumentState get() = vm.doc

    private lateinit var actionBarMenu: Menu
    private lateinit var appTitle: TextView
    private lateinit var appTitlePageNumber: TextView
    private lateinit var appTitleIncognitoIcon: ImageView

    private var doubleBackToExitPressedOnce = false
    private var savingProgressVisible = false
    private var taskDescriptionName: String? = null
    private val stallWatchdog by lazy { MainThreadStallWatchdog(activityOwned = true) }

    private val annotationController get() = reader.annotationController
    private val annotationSaveController get() = reader.annotationSaveController
    private val signatureController get() = reader.signatureController
    private val cropMarginsController get() = reader.cropMarginsController
    private val documentLoader get() = reader.documentLoader
    private val onlinePdfController get() = reader.onlinePdfController
    private val readerNavigationController get() = reader.readerNavigationController
    private val readerHistory get() = reader.readerHistory
    private val fullscreenController get() = reader.fullscreenController
    private val pdfRepository get() = reader.pdfRepository

    private val readerBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (annotationController.hasUnsavedAnnotations) {
                runAfterDirtyAnnotationPrompt(PostSaveAction.LEAVE_READER)
                return
            }
            if (!pref.getDoubleTapToExitEnabled()
                || intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)
                || doubleBackToExitPressedOnce
            ) {
                leaveReader(this)
            } else {
                AppSnackbar.make(binding.root, getString(R.string.press_back_again), Snackbar.LENGTH_LONG).show()
                doubleBackToExitPressedOnce = true

                lifecycleScope.launch {
                    delay(2500)
                    doubleBackToExitPressedOnce = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val incognito = if (savedInstanceState?.containsKey(PDF.incognitoKey) == true) {
            savedInstanceState.getBoolean(PDF.incognitoKey)
        } else {
            intent.getBooleanExtra(PDF.incognitoKey, false)
        }
        if (incognito) {
            applyIncognitoNightMode()
        } else {
            clearIncognitoNightMode()
        }
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(DEBUG_PROCESS_BOOTSTRAP_EXTRA, false)) {
            finishAndRemoveTask()
            return
        }
        if (incognito) {
            applyIncognitoOverlay()
        }
        pref = Preferences(PreferenceManager.getDefaultSharedPreferences(this))

        if (savedInstanceState == null) {
            vm.incognito = intent.getBooleanExtra(PDF.incognitoKey, false)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setCustomActionBar()
        ColorUtil.colorize(this, window, supportActionBar)

        // To avoid FileUriExposedException, (https://stackoverflow.com/questions/38200282/)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        reader = ReaderComposition(this, binding, vm, pref)
        installReaderDebugState()
        documentLoader.applyTileRenderingPreferences()

        openInitialDocument(savedInstanceState)
        reader.wireViews()
        overrideOnBackButtonPressed()
    }

    private fun installReaderDebugState() {
        if (!BuildConfig.DEBUG) {
            return
        }
        PDFView.enableDebugState(true)
        binding.pdfView.attachDebugStateSource(
            ReaderDebugStateSource(vm, documentLoader, readerHistory),
        )
        DebugRootDescription.attachLayoutDirection(binding.root)
        reader.publishDebugHistory()
    }

    @MainThread
    fun annotationDebug(): ReaderAnnotationDebug? {
        if (!BuildConfig.DEBUG || !::binding.isInitialized || isDestroyed) {
            return null
        }
        return ReaderAnnotationDebug(binding.pdfView, vm, this, annotationController)
    }

    private fun openInitialDocument(savedInstanceState: Bundle?) {
        var pendingCleartextUri: Uri? = null
        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState)
            pendingCleartextUri = externalIntentUri()
                ?.takeIf { it.isCleartextHttp() && !isDisplayingUri(it.toString()) }
        }
        else {
            val intentUri = intent.data
            if (intentUri == null) {
                if (intent.getBooleanExtra(HomeActivity.EXTRA_OPEN_ONLINE_DIALOG, false)) {
                    onlinePdfController.showOpenOnlinePdfDialog()
                } else if (intent.action == Intent.ACTION_SEND) {
                    if (openSharedTextLink()) {
                        return
                    }
                    AppSnackbar.make(binding.root, R.string.share_text_no_link, Snackbar.LENGTH_LONG).show()
                    reader.pickFile()
                } else {
                    reader.pickFile()
                }
            } else {
                openExternalUri(intentUri)
                return
            }
        }

        displayFromUri(pdf.uri, true)
        pendingCleartextUri?.let(::openExternalUri)
    }

    private fun openSharedTextLink(): Boolean {
        val link = sharedTextLink() ?: return false
        openExternalUri(link)
        return true
    }

    private fun externalIntentUri(): Uri? = if (intent.action == Intent.ACTION_SEND) {
        sharedTextLink()
    } else {
        intent.data
    }

    private fun sharedTextLink(): Uri? {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val trimChars = charArrayOf(
            '<', '>', '(', ')', '[', ']', '{', '}', '"', '\'', '.', ',', ';', ':', '!', '?',
        )
        val link = sharedText
            .split(Regex("\\s+"))
            .map { it.trim(*trimChars) }
            .firstOrNull { token ->
                (token.startsWith("http://", ignoreCase = true)
                        || token.startsWith("https://", ignoreCase = true))
                        && Patterns.WEB_URL.matcher(token).matches()
            } ?: return null
        return Uri.parse(link)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUri = intent.data
        if (intent.action == Intent.ACTION_SEND) {
            if (!openSharedTextLink()) {
                AppSnackbar.make(binding.root, R.string.share_text_no_link, Snackbar.LENGTH_LONG).show()
            }
        } else if (newUri != null) {
            if (!isDisplayingUri(newUri.toString())) {
                openExternalUri(newUri)
            }
        } else if (intent.getBooleanExtra(HomeActivity.EXTRA_OPEN_ONLINE_DIALOG, false)) {
            intent.removeExtra(HomeActivity.EXTRA_OPEN_ONLINE_DIALOG)
            onlinePdfController.showOpenOnlinePdfDialog()
        }
    }

    fun isDisplayingUri(uri: String): Boolean {
        return pdf.uri?.toString() == uri
    }

    private fun openExternalUri(uri: Uri) {
        if (uri.isCleartextHttp()) {
            onlinePdfController.showOpenOnlinePdfDialog(
                initialUri = uri,
                onCancel = { clearPendingExternalUri(uri) },
                onConfirmed = { clearPendingExternalUri(uri) },
            )
        } else {
            runAfterDirtyAnnotationPrompt(PostSaveAction.DISPLAY_URI, uri)
        }
    }

    private fun clearPendingExternalUri(uri: Uri) {
        if (externalIntentUri() != uri) return
        if (intent.action == Intent.ACTION_SEND) {
            intent.removeExtra(Intent.EXTRA_TEXT)
        } else {
            intent.data = null
        }
    }

    private fun Uri.isCleartextHttp(): Boolean = scheme.equals("http", ignoreCase = true)

    private fun setCustomActionBar() {
        val actionBar = supportActionBar
        // Disable the default and enable the custom
        actionBar?.setDisplayShowTitleEnabled(false)
        actionBar?.setDisplayShowCustomEnabled(true)
        actionBar?.elevation = 0F

        updateHomeUpIndicator()

        val customView: View = layoutInflater.inflate(R.layout.actionbar_title, null)
        appTitlePageNumber = customView.findViewById(R.id.actionbarPageNumber)
        appTitle = customView.findViewById(R.id.actionbarTitle)
        appTitleIncognitoIcon = customView.findViewById(R.id.actionbarIncognitoIcon)
        appTitleIncognitoIcon.visibility = if (vm.incognito) View.VISIBLE else View.GONE

        fun titleClickListener() {
            val title = pdf.getTitle()
            if (title.isNotBlank()) {
                AppSnackbar.make(binding.root, title, Snackbar.LENGTH_LONG).setTextMaxLines(5).show()
            }
        }
        appTitle.setOnClickListener { titleClickListener() }
        appTitlePageNumber.setOnClickListener { titleClickListener() }

        // Apply the custom view
        actionBar?.customView = customView
    }

    private fun updateHomeUpIndicator() {
        val actionBar = supportActionBar
        val homeEnabled = !pref.getHomeDisabled()
        actionBar?.setDisplayHomeAsUpEnabled(homeEnabled)
        if (homeEnabled) {
            actionBar?.setHomeAsUpIndicator(R.drawable.ic_home)
        }
    }

    override fun runAfterDirtyAnnotationPrompt(action: PostSaveAction, uri: Uri?) {
        if (!annotationController.hasUnsavedAnnotations) {
            performPostSaveAction(action, uri)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_highlights)
            .setMessage(R.string.unsaved_highlights_prompt)
            .setPositiveButton(R.string.save_highlights) { _, _ ->
                annotationSaveController.saveHighlights(action, uri)
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                clearUnsavedAnnotationState()
                performPostSaveAction(action, uri)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    internal fun runAfterAnnotationSaveGate(action: PostSaveAction, uri: Uri? = null) {
        if (!annotationController.hasUnsavedAnnotations) {
            performPostSaveAction(action, uri)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_highlights)
            .setMessage(R.string.unsaved_highlights_prompt)
            .setPositiveButton(R.string.save_highlights) { _, _ ->
                annotationSaveController.saveHighlights(action, uri)
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                clearUnsavedAnnotationState()
                reloadPdf()
                performPostSaveAction(action, uri)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    internal fun performPostSaveAction(action: PostSaveAction, uri: Uri?) {
        when (action) {
            PostSaveAction.DISPLAY_URI -> uri?.let { displayFromUri(it, savePassword = true) }
            PostSaveAction.OPEN_PICKER -> reader.openPickerWithoutPrompt()
            PostSaveAction.SHOW_USER_NOTES -> readerNavigationController.showUserNotes()
            PostSaveAction.SHOW_USER_HIGHLIGHTS -> readerNavigationController.showUserHighlights()
            PostSaveAction.GO_HOME -> goHomeNow()
            PostSaveAction.LEAVE_READER -> leaveReader(readerBackCallback)
        }
    }

    private fun clearUnsavedAnnotationState() {
        signatureController.cancelPlacement()
        annotationSaveController.clearPendingRequests()
        annotationController.clearJournal()
        updateDirtyUi()
    }

    internal fun confirmDiscardAnnotations() {
        confirmDialog(
            this,
            R.string.discard_unsaved_highlights_title,
            getString(R.string.discard_unsaved_highlights_message),
            R.string.discard,
        ) {
            clearUnsavedAnnotationState()
            reloadPdf()
        }
    }

    fun displayFromUri(uri: Uri?, savePassword: Boolean = false) {
        documentLoader.displayFromUri(uri, savePassword)
        updatePickFileButtonVisibility()
        if (uri != null) {
            closeOtherReaderWindows()
        }
    }

    override fun updateTitle() {
        if (pdf.name.isNotBlank() && taskDescriptionName != pdf.name) {
            taskDescriptionName = pdf.name
            setTaskDescription(ActivityManager.TaskDescription(pdf.name))
        }
        appTitle.text = pdf.getTitle()
        appTitlePageNumber.text = pdf.getPageCounterText()
        appTitlePageNumber.visibility = if (pref.getShowAppBarPageCount() && pdf.hasFile() && pdf.length > 0) {
            View.VISIBLE
        } else {
            View.GONE
        }
        reader.fullScreenOptionsManager.refreshInfo()
    }

    override fun updateActionBar() {
        if (!::actionBarMenu.isInitialized) {
            return
        }

        reader.toolbarActionController.update(actionBarMenu)
    }

    @SuppressLint("SourceLockedOrientationActivity")
    internal fun applyOrientationPolicy() {
        val docId = pdf.uri?.toString()
        if (docId != vm.orientationDocId) {
            vm.orientationDocId = docId
            vm.userOrientationLock = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        requestedOrientation = when {
            vm.userOrientationLock != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> vm.userOrientationLock
            pref.getAlwaysHorizontal() -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    internal fun maybeRestoreAnnotations(documentUri: Uri?, loadToken: Long) {
        val uri = documentUri ?: return
        lifecycleScope.launch {
            val hasJournal = withContext(Dispatchers.IO) { annotationController.hasJournal(uri) }
            if (!hasJournal || !vm.isCurrent(loadToken, uri)) {
                return@launch
            }
            if (annotationController.isSessionOwned(uri)) {
                replayAnnotations(uri, loadToken)
            } else {
                promptRestoreAnnotations(uri, loadToken)
            }
        }
    }

    private fun promptRestoreAnnotations(documentUri: Uri, loadToken: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_unsaved_highlights_title)
            .setMessage(R.string.restore_unsaved_highlights_message)
            .setCancelable(false)
            .setPositiveButton(R.string.restore) { _, _ ->
                lifecycleScope.launch { replayAnnotations(documentUri, loadToken) }
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                annotationController.clearJournal(documentUri)
                updateDirtyUi()
            }
            .show()
    }

    private suspend fun replayAnnotations(documentUri: Uri, loadToken: Long) {
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        annotationController.replayJournal()
        updateDirtyUi()
    }

    override fun updateDirtyUi() {
        val visible = annotationController.hasUnsavedAnnotations
        val saving = annotationController.isSaving
        binding.saveAnnotationsFab.visibility = if (visible) View.VISIBLE else View.GONE
        binding.discardAnnotationsFab.visibility = if (visible) View.VISIBLE else View.GONE
        binding.saveAnnotationsFab.isEnabled = visible && !saving
        binding.discardAnnotationsFab.isEnabled = visible && !saving
        binding.saveAnnotationsFab.alpha = if (saving) 0.5f else 1f
        binding.discardAnnotationsFab.alpha = if (saving) 0.5f else 1f
        if (saving && !savingProgressVisible) {
            savingProgressVisible = true
            binding.progressBar.isIndeterminate = true
            binding.progressBar.visibility = View.VISIBLE
        } else if (!saving && savingProgressVisible) {
            savingProgressVisible = false
            hideProgress()
        }
        updateDirtyUiPosition()
    }

    override fun updateDirtyUiPosition() {
        val params = binding.saveAnnotationsFab.layoutParams as ConstraintLayout.LayoutParams
        val defaultBottomMargin = (24 * resources.displayMetrics.density).toInt()
        val cardAtBottom = reader.inlineAnnotationActionController.isCardAtBottom()
        val bottomMargin = if (cardAtBottom && binding.textSelectionActionCard.height > 0) {
            binding.textSelectionActionCard.height + (32 * resources.displayMetrics.density).toInt()
        } else {
            defaultBottomMargin
        }
        if (params.bottomMargin != bottomMargin) {
            params.bottomMargin = bottomMargin
            binding.saveAnnotationsFab.layoutParams = params
        }
    }

    // Intentional behavior:
    // Once the user taps rotate, the app owns rotation for this document.
    // The button toggles landscape and portrait only, with no automatic option.
    // The lock clears when another document is opened, and when the app closes.
    // It is deliberately not saved across app restarts.
    @SuppressLint("SourceLockedOrientationActivity")
    internal fun rotateScreen() {
        if (!canControlOrientation()) {
            AppSnackbar.make(binding.root, R.string.rotation_not_available, Snackbar.LENGTH_LONG).show()
            return
        }
        val showingLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lock = if (showingLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        requestedOrientation = lock
        vm.userOrientationLock = lock
    }

    private fun canControlOrientation(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
            return false
        }
        val largeScreen = resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_SW_DP
        return !(largeScreen && Build.VERSION.SDK_INT >= ORIENTATION_REQUEST_IGNORED_SDK)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        fullscreenController.refreshOnWindowFocus(hasFocus)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (savingProgressVisible) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    public override fun onResume() {
        super.onResume()
        closeOtherReaderWindows()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (pref.getScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (::actionBarMenu.isInitialized) {
            updateActionBar()
        }
        updateHomeUpIndicator()
        reader.onResume()

        updatePickFileButtonVisibility()

        // restore the full screen mode if was toggled On
        fullscreenController.restoreFullScreenIfNeeded()

        if (pref.getHomeDisabled()) {
            showBackupNoticesIfNeeded()
        }

        if (BuildConfig.DEBUG) {
            stallWatchdog.start()
        }
    }

    private fun updatePickFileButtonVisibility() {
        binding.pickFileButton.visibility = if (pdf.uri == null || documentLoader.state is LoadState.Failed) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun closeOtherReaderWindows() {
        if (pref.getOpenPdfsInSeparateWindows()) return
        if (isFinishing) return
        if (pdf.uri == null) return
        val activityManager = getSystemService(ActivityManager::class.java)
        for (task in activityManager.appTasks) {
            try {
                val taskInfo = task.taskInfo
                val id = if (Build.VERSION.SDK_INT >= 29) {
                    taskInfo.taskId
                } else {
                    @Suppress("DEPRECATION")
                    taskInfo.persistentId
                }
                if (id == taskId) continue
                if (taskInfo.baseIntent.component?.className != MainActivity::class.java.name) continue
                task.finishAndRemoveTask()
            } catch (e: IllegalArgumentException) {
                continue
            }
        }
    }

    private fun showBackupNoticesIfNeeded() {
        pref.getImportResultPending()?.let { message ->
            pref.setImportResultPending(null)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.backup_import_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        val failure = BackupNotices.shouldShowFailureNotice(pref)
        if (!failure && !BackupNotices.shouldShowStaleNotice(pref)) {
            return
        }
        val messageRes = if (failure) R.string.auto_backup_failure_notice else R.string.auto_backup_stale_notice
        val snackbar = AppSnackbar.make(binding.root, messageRes, Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction(R.string.auto_backup_notice_action) {
            BackupNotices.acknowledge(pref)
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.BACKUP.name)
            )
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (event == DISMISS_EVENT_SWIPE) {
                    BackupNotices.acknowledge(pref)
                }
            }
        })
        snackbar.show()
    }

    override fun onPause() {
        if (BuildConfig.DEBUG) {
            stallWatchdog.stop()
        }
        super.onPause()
    }

    internal fun shareFile(uri: Uri?) {
        if (uri == null) {
            checkHasFile()  // only to show the message
            return
        }
        if (uri.scheme != null && uri.scheme!!.startsWith("http")) {
            val localCopy = OnlineDocumentStore.fileFor(this, pdf.uri?.toString())
                ?: publicDownloadsCopy(pdf.name)
            if (localCopy == null) {
                startShareIntent(plainTextShareIntent(getString(R.string.share_file), pdf.uri.toString()))
            } else {
                showShareChoiceDialog(localCopy)
            }
            return
        }

        lifecycleScope.launch {
            startShareIntent(pdfShareIntent(this@MainActivity, uri, pdf.name))
        }
    }

    internal fun shareImage(uri: Uri?, displayName: String) {
        if (uri == null) {
            checkHasFile()
            return
        }
        startShareIntent(imageShareIntent(getString(R.string.share_file), displayName, uri))
    }

    private fun showShareChoiceDialog(localCopy: File) {
        val options = arrayOf(
            getString(R.string.share_link_option),
            getString(R.string.share_pdf_option),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_file)
            .setItems(options) { _, which ->
                if (which == 0) {
                    startShareIntent(plainTextShareIntent(getString(R.string.share_file), pdf.uri.toString()))
                } else {
                    lifecycleScope.launch {
                        startShareIntent(pdfShareIntent(this@MainActivity, Uri.fromFile(localCopy), pdf.name))
                    }
                }
            }
            .show()
    }

    private fun startShareIntent(sharingIntent: Intent) {
        try {
            startActivity(sharingIntent)
        }
        catch (e: Throwable) {
            AppSnackbar.make(binding.root, R.string.share_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    internal fun handleFileOpeningError(exception: Throwable) {
        val fileHash = pdf.fileHash
        if (exception is PdfPasswordException && fileHash != null) {
            if (pdf.password != null) {
                AppSnackbar.make(binding.root, R.string.wrong_password, Snackbar.LENGTH_SHORT).show()
                pdf.password = null         // prevent the toast if the user rotates the screen
            }

            lifecycleScope.launch {
                pdf.password = pdfRepository.findPdfPassword(fileHash)
                withContext(Dispatchers.Main) {
                    if (pdf.password != null) {
                        displayFromUri(pdf.uri)
                    }
                    else {
                        askForPdfPassword()
                    }
                }
            }
        }
        else if (couldNotOpenFileDueToMissingPermission(exception)) {
            reader.readFileErrorPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        else if (shouldReturnToHomeForRelocate(exception)) {
            returnToHomeForRelocate()
        }
        else if (isStaleDocumentFailure(exception) && hasRecoverableUri()) {
            reader.startStaleDocumentRecovery(pdf.uri ?: return)
        }
        else {
            updatePickFileButtonVisibility()
            AppSnackbar.make(binding.root, R.string.file_opening_error, Snackbar.LENGTH_LONG).show()
            Log.e(TAG, getString(R.string.file_opening_error), exception)
        }
    }

    private fun shouldReturnToHomeForRelocate(exception: Throwable): Boolean {
        if (pref.getHomeDisabled()) {
            return false
        }
        if (!intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)) {
            return false
        }
        if (intent.getStringExtra(HomeActivity.EXTRA_RECORD_HASH) == null) {
            return false
        }
        return isStaleDocumentFailure(exception)
    }

    private fun isStaleDocumentFailure(exception: Throwable): Boolean {
        var cause: Throwable? = exception
        while (cause != null) {
            if (cause is SecurityException || cause is FileNotFoundException || cause is DocumentUnreachableException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun hasRecoverableUri(): Boolean {
        val scheme = pdf.uri?.scheme
        return scheme == "content" || scheme == "file"
    }

    private fun returnToHomeForRelocate() {
        Intent(this, HomeActivity::class.java).also { homeIntent ->
            homeIntent.putExtra(
                HomeActivity.EXTRA_RELOCATE_HASH,
                intent.getStringExtra(HomeActivity.EXTRA_RECORD_HASH)
            )
            startActivity(homeIntent)
        }
        finish()
    }

    private fun couldNotOpenFileDueToMissingPermission(e: Throwable): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) return false
        val exceptionMessage = e.message
        return e is FileNotFoundException && exceptionMessage != null
            && exceptionMessage.contains(getString(R.string.permission_denied))
    }

    internal fun restartAppIfGranted(isPermissionGranted: Boolean) {
        if (isPermissionGranted) {
            // This is a quick and dirty way to make the system restart the current activity *and the current app process*.
            // This is needed because on Android 6 storage permission grants do not take effect until
            // the app process is restarted.
            exitProcess(0)
        }
        else {
            AppSnackbar.make(binding.root, R.string.file_opening_error, Snackbar.LENGTH_LONG).show()
        }
    }

    internal fun reloadPdf() {
        if (checkHasFile()) {
            vm.revalidateDocumentOnLoad = true
            recreate()
        }
    }

    internal fun toggleCropMargins() {
        if (!checkHasFile()) {
            return
        }
        val enableCropMargins = !vm.cropMarginsEnabled
        reader.setCropMarginsEnabled(enableCropMargins)
        if (enableCropMargins) {
            cropMarginsController.startOrApply(
                pdf.fileHash,
                vm.currentLoadToken,
                pdf.uri,
                binding.pdfView.pageCount,
            )
        } else {
            cropMarginsController.cancel()
            recreate()
        }
    }

    internal fun downloadOrShowDownloadedFile(uri: Uri) {
        onlinePdfController.downloadOrShowDownloadedFile(uri)
    }

    override fun onStop() {
        if (::reader.isInitialized) {
            reader.autoScrollManager.stop()
            reader.autoScrollSpeedStore.flushPendingSave()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::reader.isInitialized) {
            reader.autoScrollSpeedStore.flushPendingSave()
            cropMarginsController.cancel()
            reader.inlineAnnotationActionController.hideActions()
            reader.onActivityDestroyed()
        }
        if (isFinishing) {
            OnlineDocumentStore.sweepIncognito(this)
        }
        super.onDestroy()
    }

    override fun showProgress() {
        binding.progressBar.isIndeterminate = true
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun hideProgress() {
        binding.progressBar.visibility = View.GONE
        binding.progressBar.isIndeterminate = true
        binding.progressBar.progress = 0
    }

    companion object {
        const val DEBUG_PROCESS_BOOTSTRAP_EXTRA = "mjpdf.debug.bootstrap_process"
    }

    private fun askForPdfPassword() {
        if (isFinishing || isDestroyed) {
            return
        }
        val dialogBinding = PasswordDialogBinding.inflate(layoutInflater)
        showAskForPasswordDialog(this, pdf, dialogBinding, ::displayFromUri)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        this.actionBarMenu = menu
        menu.showOptionalIcons(this)
        updateActionBar()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> navigateHome()
            R.id.toolbarPrimaryActionOption,
            R.id.toolbarSecondaryActionOption -> if (!reader.toolbarActionController.handle(item)) return super.onOptionsItemSelected(item)
            R.id.readerActionsOption -> reader.readerMenu.show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun navigateHome() {
        runAfterDirtyAnnotationPrompt(PostSaveAction.GO_HOME)
    }

    private fun goHomeNow() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (reader.volumeKeyPager.handleKeyDown(keyCode)) {
            return true
        }
        if (reader.mousePager.handleKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (reader.mousePager.handleGenericMotionEvent(ev)) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    internal fun showFileMetadata() {
        if (!checkHasFile()) {
            return
        }

        val uri = pdf.uri
        lifecycleScope.launch {
            val fileSizeBytes = withContext(Dispatchers.IO) { queryFileSizeBytes(uri) }
            val filePath = withContext(Dispatchers.IO) { resolveDisplayFilePath(uri) }
            val pageSize = withContext(Dispatchers.Default) {
                PdfPropertiesSummary.formatPageSizes(binding.pdfView, getString(R.string.pdf_page_size_mixed))
            }
            val fonts = withContext(Dispatchers.Default) {
                PdfPropertiesSummary.formatFonts(
                    binding.pdfView,
                    getString(R.string.font_embedded),
                    getString(R.string.font_not_embedded),
                )
            }
            showMetaDialog(this@MainActivity, binding.pdfView.documentMeta, pdf.name, fileSizeBytes, pageSize, fonts, filePath)
        }
    }

    private fun resolveDisplayFilePath(uri: Uri?): String? {
        if (uri == null) {
            return null
        }
        if (uri.scheme?.startsWith("http") == true) {
            return publicDownloadsCopy(pdf.name)?.absolutePath
        }
        return UriCanonicalizer.canonicalize(this, uri)?.absolutePath
    }

    private fun queryFileSizeBytes(uri: Uri?): Long? {
        if (uri == null) {
            return null
        }
        val heldFile = OnlineDocumentStore.fileFor(this, uri.toString())
        if (heldFile != null) {
            return heldFile.length()
        }
        return runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                fd.statSize.takeIf { it >= 0 }
            }
        }.getOrNull()
    }

    override fun checkHasFile(): Boolean {
        if (!pdf.hasFile()) {
            AppSnackbar.make(
                binding.root, getString(R.string.no_pdf_in_app),
                Snackbar.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        vm.captureViewStateForSave(binding.pdfView.captureViewState())
        signatureController.capturePlacementForState()
        readerNavigationController.saveState(outState)
        readerHistory.saveState(outState)
        outState.putBoolean(PDF.incognitoKey, vm.incognito)
        super.onSaveInstanceState(outState)
    }

    private fun restoreInstanceState(savedState: Bundle) {
        readerNavigationController.restoreState(savedState)
        readerHistory.restoreState(savedState)
        updateDirtyUi()
    }

    private fun overrideOnBackButtonPressed() {
        onBackPressedDispatcher.addCallback(this, readerBackCallback)
    }

    private fun leaveReader(callback: OnBackPressedCallback) {
        val launchedByPickerFlow = pref.getHomeDisabled()
                && intent.action == null
                && intent.data == null
        if (launchedByPickerFlow && pdf.uri != null) {
            doubleBackToExitPressedOnce = false
            reader.pickFileOnBackPressed()
        } else if (intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)) {
            navigateHome()
        } else {
            callback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

}

private const val LARGE_SCREEN_SW_DP = 600
private const val ORIENTATION_REQUEST_IGNORED_SDK = 36
