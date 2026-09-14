// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.PDFView.Configurator
import com.github.barteksc.pdfviewer.listener.OnTextSelectionChangeListener
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.github.barteksc.pdfviewer.scroll.ScrollHandle
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.DebugRootDescription
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.grantPdfReadAccess
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.signature.SignatureStore
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationEngine
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationSettings
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.resolveReadingLayout
import com.gitlab.mudlej.MjPdfReader.ui.about.AboutActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableActionResolver
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.FullScreenButtonController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.PageTextCopier
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.PrintController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ReaderMenu
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ScreenshotController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ShortcutBarController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ToolbarActionController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.AnnotationController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.AnnotationSaveController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.FormFieldController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.InlineAnnotationActionController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.SignatureController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.AutoScrollManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.AutoScrollSpeedStore
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.BrightnessController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.CropMarginsController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.FullScreenOptionsManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.FullscreenController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.ZoomSwipeLockController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection.ReadingDirectionController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection.ReadingDirectionResolver
import com.gitlab.mudlej.MjPdfReader.ui.reader.input.EdgeTapPager
import com.gitlab.mudlej.MjPdfReader.ui.reader.input.MousePager
import com.gitlab.mudlej.MjPdfReader.ui.reader.input.TapDispatcher
import com.gitlab.mudlej.MjPdfReader.ui.reader.input.VolumeKeyPager
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentListener
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentLoadedEvent
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentLoader
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.OnlinePdfController
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderHistoryManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderNavigationController
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.UserBookmarkController
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.gitlab.mudlej.MjPdfReader.ui.gotopage.showGoToPageDialog
import com.gitlab.mudlej.MjPdfReader.ui.text_mode.TextModeActivity
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.io.PersistedGrantKeeper
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentity
import com.gitlab.mudlej.MjPdfReader.core.io.navIntent
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.TemporaryCopyImporter
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.CopyConsentRequest
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.CopyConsentDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderComposition(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val pref: Preferences,
) {

    private val ui: ReaderUi = activity
    private val doc = vm.doc
    private val scope = activity.lifecycleScope

    private val pdfPickerLauncher: ActivityResultLauncher<Array<String>> = activity.registerForActivityResult(OpenDocument()) { selectedDocumentUri ->
        val exitOnCancel = vm.pickerOpenedByBackButton
        vm.pickerOpenedByBackButton = false
        if (selectedDocumentUri != null) {
            if (!vm.incognito) {
                PersistedGrantKeeper.takeReadGrant(activity, selectedDocumentUri)
            }
            openSelectedDocument(selectedDocumentUri)
        } else if (exitOnCancel) {
            activity.finish()
        }
    }

    private val relocatePickerLauncher: ActivityResultLauncher<Array<String>> = activity.registerForActivityResult(OpenDocument()) { pickedUri ->
        val pendingHash = vm.pendingRelocate?.hash
        vm.pendingRelocate = null
        if (pickedUri == null) {
            exitAfterFailedRecovery()
            return@registerForActivityResult
        }
        if (pendingHash == null) {
            if (!vm.incognito) {
                PersistedGrantKeeper.takeReadGrant(activity, pickedUri)
            }
            activity.displayFromUri(pickedUri, savePassword = true)
            return@registerForActivityResult
        }
        scope.launch {
            val record = pdfRepository.findRecord(pendingHash)
            if (record == null) {
                if (!vm.incognito) {
                    PersistedGrantKeeper.takeReadGrant(activity, pickedUri)
                }
                activity.displayFromUri(pickedUri, savePassword = true)
                return@launch
            }
            val identities = DocumentIdentity.of(activity, pickedUri)
            if (identities != null && identities.matches(record.hash)) {
                val pickedHash = pdfRepository.resolveIdentity(identities)
                if (!vm.incognito) {
                    PersistedGrantKeeper.takeReadGrant(activity, pickedUri)
                }
                val canonicalFile = withContext(Dispatchers.IO) { UriCanonicalizer.canonicalize(activity, pickedUri) }
                val durableUri = canonicalFile?.let(Uri::fromFile) ?: pickedUri
                if (historyPolicy.canRecord()) {
                    pdfRepository.updateRecordIdentity(pickedHash, durableUri, record.fileName, record.lastOpened)
                }
                activity.displayFromUri(durableUri)
            } else {
                showRelocateMismatchDialog(pickedUri)
            }
        }
    }

    private val saveToDownloadPermissionLauncher: ActivityResultLauncher<String> = activity.registerForActivityResult(RequestPermission()) { granted ->
        onlinePdfController.saveDownloadedFileAfterPermissionRequest(granted)
    }

    val readFileErrorPermissionLauncher: ActivityResultLauncher<String> = activity.registerForActivityResult(RequestPermission()) { granted ->
        activity.restartAppIfGranted(granted)
    }

    private val settingsLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) {
        val baseline = vm.alwaysHideMarginsAtSettingsOpen
        vm.alwaysHideMarginsAtSettingsOpen = null
        val alwaysHideMargins = pref.getAlwaysHideMargins()
        if (baseline != null && alwaysHideMargins != baseline) {
            if (!alwaysHideMargins) {
                cropMarginsController.abandonActiveDetection()
            }
            setCropMarginsEnabled(alwaysHideMargins)
        }
        activity.displayFromUri(doc.uri)
    }

    private val updateAnnotationDestinationLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            annotationSaveController.handleDestinationResult(result.data)
        } else {
            annotationSaveController.clearPendingRequests()
        }
    }

    private val createAnnotationDestinationLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            annotationSaveController.handleDestinationResult(result.data)
        } else {
            annotationSaveController.clearPendingRequests()
        }
    }

    private val tableOfContentsLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleTableOfContentsResult(result.resultCode, result.data)
    }

    private val userBookmarksLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        userBookmarkController.reload()
        readerNavigationController.handleUserBookmarksResult(result.resultCode, result.data)
    }

    private val userNotesLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleUserNotesResult(result.resultCode, result.data)
    }

    private val userHighlightsLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleUserHighlightsResult(result.resultCode, result.data)
    }

    private val navigationHistoryLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleNavigationHistoryResult(result.resultCode, result.data)
    }

    private val linksLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleLinksResult(result.resultCode, result.data)
    }

    private val searchLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleSearchResult(result.resultCode, result.data)
    }

    private val goToPageGridLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleGoToPageGridResult(result.resultCode, result.data)
    }

    private val textModeLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleTextModeResult(result.resultCode, result.data)
    }

    val pdfRepository: PdfRepository = PdfRepository(AppDatabase.getInstance(activity.applicationContext))
    val historyPolicy: HistoryPolicy = HistoryPolicy(pref) { vm.incognito }
    val autoScrollSpeedStore = AutoScrollSpeedStore(doc, pdfRepository, historyPolicy, scope, backgroundSaveScope)
    val autoScrollManager: AutoScrollManager =
        AutoScrollManager(binding, vm, pref, autoScrollSpeedStore::onSpeedChanged)
    val fullScreenOptionsManager: FullScreenOptionsManager =
        FullScreenOptionsManager(binding, vm, pref)
    val zoomSwipeLockController = ZoomSwipeLockController(binding, ::drawableOf)
    val brightnessController = BrightnessController(activity, binding, vm)
    val pdfThemeController = PdfThemeController(activity, binding, pref)
    val volumeKeyPager = VolumeKeyPager(binding, doc, pref)
    val mousePager = MousePager(binding, doc, pref)
    val edgeTapPager = EdgeTapPager(binding, doc, pref)
    val printController = PrintController(activity, binding, doc, scope) { activity.shareFile(doc.uri) }
    val pageTextCopier = PageTextCopier(activity, binding, doc, scope)
    val screenshotController: ScreenshotController = ScreenshotController(
        activity,
        binding,
        doc,
        { fullScreenOptionsManager.showAllTemporarilyOrHide() },
        { uri, displayName -> activity.shareImage(uri, displayName) },
    )

    val annotationController: AnnotationController = AnnotationController(activity, binding, vm, historyPolicy)
    val formFieldController = FormFieldController(activity, binding, ::onAnnotationEdit, ::canEditDocument)
    val signatureController: SignatureController = SignatureController(
        activity,
        binding,
        vm,
        SignatureStore(activity),
        annotationController,
        ::onAnnotationEdit,
        ::canEditDocument,
        ui::updateDirtyUi,
        { vm.incognito },
    )
    val dictionaryDefinitionController: DictionaryDefinitionController = DictionaryDefinitionController(
        activity,
        scope,
    ) { pref.getDictionaryDefineWords() }
    val inlineAnnotationActionController: InlineAnnotationActionController = InlineAnnotationActionController(
        activity,
        binding,
        { readerNavigationController.clearActiveSearchResultHighlight() },
        ::onAnnotationEdit,
        ::canEditDocument,
        ui::updateDirtyUiPosition,
        { pref.getDetectExistingHighlights() },
        { pref.getHighlightColors() },
        { doc.getTitle() },
        {
            TranslationSettings(
                pref.getTranslationMode(),
                TranslationEngine.fromId(pref.getTranslationEngine()),
                pref.getTranslationCustomUrl(),
                pref.getTranslationTargetLanguage(),
            )
        },
        dictionaryDefinitionController::defineWord,
        ::openSettings,
    ) { fullScreenOptionsManager.showAllTemporarilyOrHide() }
    val annotationSaveController: AnnotationSaveController = AnnotationSaveController(
        activity,
        binding,
        doc,
        annotationController,
        pdfRepository,
        historyPolicy,
        vm,
        scope,
        backgroundSaveScope,
        updateAnnotationDestinationLauncher,
        createAnnotationDestinationLauncher,
        { readerNavigationController.clearActiveSearchResultHighlight() },
        ui::updateDirtyUi,
        { signatureController.commitPendingSignature() },
        activity::performPostSaveAction,
    ) {
        ui.updateTitle()
    }
    val cropMarginsController: CropMarginsController = CropMarginsController(
        activity,
        binding,
        pdfRepository,
        historyPolicy,
        doc,
        scope,
        { vm.cropMarginsEnabled },
        ::setCropMarginsEnabled,
        vm::isCurrent,
        { configurator, pageNumber, cropMargins, viewState ->
            documentLoader.reloadWithCropMargins(configurator, pageNumber, cropMargins, viewState)
        },
    )
    val readingDirectionResolver = ReadingDirectionResolver(activity, doc, pref, pdfRepository)
    val onlinePdfController: OnlinePdfController = OnlinePdfController(
        activity,
        binding,
        doc,
        scope,
        { vm.incognito },
        { saveToDownloadPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
        { file -> documentLoader.initPdfViewAndLoad(binding.pdfView.fromFile(file)) },
        { uri -> ui.runAfterDirtyAnnotationPrompt(PostSaveAction.DISPLAY_URI, uri) },
    )

    val readerHistory: ReaderHistoryManager = ReaderHistoryManager({ binding.pdfView }, ::onHistoryChanged)
    val userBookmarkController: UserBookmarkController = UserBookmarkController(
        binding,
        vm,
        pdfRepository,
        historyPolicy,
        scope,
        ui,
        ::refreshActions,
        { vm.incognito },
        ::toggleIncognito,
    )
    val readerNavigationController: ReaderNavigationController = ReaderNavigationController(
        activity,
        binding,
        doc,
        pref,
        { vm.incognito },
        readerHistory,
        scope,
        userBookmarkController::onPageDisplayed,
        ui::updateTitle,
        { intent -> tableOfContentsLauncher.launch(intent) },
        { intent -> userBookmarksLauncher.launch(intent) },
        { intent -> userNotesLauncher.launch(intent) },
        { intent -> userHighlightsLauncher.launch(intent) },
        { intent -> navigationHistoryLauncher.launch(intent) },
        { intent -> linksLauncher.launch(intent) },
        { intent -> searchLauncher.launch(intent) },
        { intent -> goToPageGridLauncher.launch(intent) },
    )

    val actionResolver: ConfigurableActionResolver = ConfigurableActionResolver(
        doc::hasFile,
        { resolveReadingLayout(pref).swipeHorizontal },
        { vm.cropMarginsEnabled },
        pref::getDualPageMode,
        { pdfThemeController.effectivePdfDarkTheme() },
        { readerHistory.canGoBack() },
        { readerHistory.canGoForward() },
        { readerHistory.hasTrail() },
        { userBookmarkController.isCurrentPageBookmarked },
        { vm.incognito },
        createHandlers(),
    )
    val toolbarActionController = ToolbarActionController(
        actionResolver,
        pref::getPrimaryButtonAction,
        pref::getSecondaryButtonAction,
    )
    val fullScreenButtonController: FullScreenButtonController = FullScreenButtonController(
        activity,
        binding,
        pref,
        actionResolver,
        fullScreenOptionsManager,
        autoScrollManager,
    ) { brightnessController.hideControl() }
    val shortcutBarController: ShortcutBarController = ShortcutBarController(
        activity,
        binding,
        pref,
        actionResolver,
    ) { vm.isFullScreenToggled }
    val readerMenu: ReaderMenu = ReaderMenu(activity, actionResolver, doc::hasFile, ::toggleSecondBar)
    val fullscreenController: FullscreenController = FullscreenController(
        activity,
        binding,
        vm,
        pref,
        fullScreenOptionsManager,
        autoScrollManager,
        zoomSwipeLockController,
        brightnessController,
    ) { shortcutBarController.updateVisibility() }


    val tapDispatcher = TapDispatcher(listOf(
        { event -> inlineAnnotationActionController.handleImmediatePdfTap(event) },
        { event -> formFieldController.handlePdfTap(event) },
        { event -> binding.pdfView.performLinkTap(event.x, event.y) },
        { event -> edgeTapPager.handleTap(event) },
        { _ ->
            inlineAnnotationActionController.handleEmptyTap()
            true
        },
    ))
    val documentLoader: DocumentLoader = DocumentLoader(
        binding,
        vm,
        pref,
        pdfRepository,
        historyPolicy,
        readingDirectionResolver,
        scope,
        ui,
        activity::downloadOrShowDownloadedFile,
        ::decorateConfigurator,
    )
    val readingDirectionController: ReadingDirectionController = ReadingDirectionController(
        activity,
        doc,
        vm,
        pref,
        pdfRepository,
        historyPolicy,
        scope,
        readingDirectionResolver,
        documentLoader,
    )
    val temporaryCopyImporter: TemporaryCopyImporter = TemporaryCopyImporter(
        activity.applicationContext,
        pdfRepository,
        historyPolicy,
        pref,
        { doc.uri to doc.name },
        backgroundSaveScope,
        { fileHash, messageRes ->
            binding.root.post {
                if (!activity.isFinishing && !activity.isDestroyed && doc.fileHash == fileHash) {
                    AppSnackbar.make(binding.root, messageRes, Snackbar.LENGTH_SHORT).show()
                }
            }
        },
        { request -> showCopyConsent(request) },
    )

    private val readerPermissionManager = PermissionManager(activity)

    private fun showCopyConsent(request: CopyConsentRequest) {
        binding.root.post {
            if (activity.isFinishing || activity.isDestroyed || doc.fileHash != request.fileHash) {
                return@post
            }
            CopyConsentDialog.show(
                activity,
                request,
                onGrantAccess = { readerPermissionManager.requestFullAccess() },
                onCopy = {
                    backgroundSaveScope.launch {
                        temporaryCopyImporter.performCopy(request.fileHash, request.uri, request.name)
                    }
                },
            )
        }
    }

    private var historyNavState = false to false

    init {
        inlineAnnotationActionController.configure { annotationSaveController.saveHighlights() }
        vm.onSaveComplete = { outcome ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                annotationSaveController.deliverSaveOutcome(outcome)
            }
        }
        subscribeDocumentListeners()
    }

    fun onActivityDestroyed() {
        readerNavigationController.onActivityDestroyed()
        if (activity.isFinishing) {
            vm.onSaveComplete = null
        }
    }

    private fun decorateConfigurator(configurator: Configurator): Configurator {
        return configurator
            .onDocumentInteraction { motionEvent -> autoScrollManager.handleUserInteraction(motionEvent) }
            .onTap { motionEvent -> tapDispatcher.dispatch(motionEvent) }
            .onTapUp { motionEvent -> inlineAnnotationActionController.handleImmediatePdfTap(motionEvent) }
            .linkHandler(readerNavigationController.createLinkHandler())
            .scrollHandle(createScrollHandle())
            .nightMode(pdfThemeController.effectivePdfDarkTheme())
            .onTextSelectionChange(object : OnTextSelectionChangeListener {
                override fun onTextSelectionChanged(viewBounds: RectF?, pageIndex: Int) {
                    inlineAnnotationActionController.showSelectionActions(viewBounds)
                }

                override fun onTextSelectionCleared() {
                    inlineAnnotationActionController.hideActions()
                }
            })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createScrollHandle(): ScrollHandle {
        val handle = DefaultScrollHandle(activity, false, pref.getShowScrollHandlePageCount())
        val fullScreenTouchListener = fullScreenOptionsManager.getOnTouchListener()
        handle.setOnTouchListener { view, motionEvent ->
            autoScrollManager.handleUserInteraction(motionEvent)
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> fullScreenOptionsManager.onHandleDragStarted()
                MotionEvent.ACTION_MOVE -> fullScreenOptionsManager.refreshInfo()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fullScreenOptionsManager.onHandleDragEnded()
            }
            fullScreenTouchListener.onTouch(view, motionEvent)
        }
        handle.setOnClickListener { goToPage() }
        return handle
    }

    private fun subscribeDocumentListeners() {
        documentLoader.subscribe(temporaryCopyImporter)
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                autoScrollSpeedStore.flushPendingSave()
                pageTextCopier.resetForNewDocument()
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                pdfThemeController.configureTheme()
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                if (event.applyDocumentLoadDefaults) {
                    fullscreenController.checkAutoFullScreen()
                    activity.applyOrientationPolicy()
                    openTextModeByDefault()
                    configureButtonsLabels()
                }
                if (doc.uri != null) {
                    shortcutBarController.configure()
                }
                fullScreenButtonController.configure()
                fullscreenController.reapplyStateAfterLoad()
                autoScrollManager.setSpeed(doc.autoScrollSpeed ?: pref.getScrollSpeed())
                if (event.pageCount == 1) {
                    fullScreenOptionsManager.permanentlyHidePageHandle()
                }
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                readerNavigationController.resetSearchResultState()
                readerNavigationController.resetTableOfContentsState()
                readerNavigationController.resetLinkJumpState()
                readerHistory.clear()
            }

            override fun onPageChanged(pageIndex: Int) {
                readerNavigationController.onPageChanged(pageIndex)
            }

            override fun onFileHashComputed() {
                readerNavigationController.onFileHashComputed()
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                cropMarginsController.cancel()
            }

            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                cropMarginsController.startIfNeeded(
                    event.cachedCropMargins,
                    event.fileHash,
                    event.loadToken,
                    event.documentUri,
                    event.pageCount,
                )
            }

            override fun onRecordAvailable(fileHash: String) {
                scope.launch { cropMarginsController.onRecordAvailable(fileHash) }
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                inlineAnnotationActionController.hideActions()
                signatureController.cancelPlacement()
                annotationController.resetContentTags()
            }

            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                activity.maybeRestoreAnnotations(event.documentUri, event.loadToken)
                signatureController.resumeRestoredPlacementIfNeeded()
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onLoadFailed(reason: Throwable) {
                activity.handleFileOpeningError(reason)
            }
        })
    }

    fun wireViews() {
        binding.pdfView.setPreviewTagSource(annotationController.previewTagSource)
        binding.pdfView.setOnStampPlacementDiscardListener { signatureController.cancelPlacement() }
        binding.exitFullScreenButton.setOnClickListener { fullscreenController.exitFullscreen() }
        autoScrollManager.setup()
        brightnessController.attachSeekbarListener()
        binding.apply {
            rotateScreenButton.setOnClickListener { activity.rotateScreen() }
            brightnessButton.setOnClickListener { brightnessController.toggleControlVisibility() }
            screenshotButton.setOnClickListener { screenshotController.takeScreenshot() }
            toggleHorizontalSwipeButton.setOnClickListener { zoomSwipeLockController.toggleHorizontalSwipeLock() }
            toggleZoomLockButton.setOnClickListener { zoomSwipeLockController.toggleZoomLock() }
            toggleLabelButton.setOnClickListener { toggleLabels() }
            pickFileButton.setOnClickListener { pickFile() }
            discardAnnotationsFab.setOnClickListener { activity.confirmDiscardAnnotations() }
        }
        fullScreenButtonController.configure()
        showPendingIncognitoNotice()
        binding.root.post { readerMenu.prewarmIcons() }
    }

    private fun showPendingIncognitoNotice() {
        if (vm.pendingIncognitoNotice == null) {
            return
        }
        binding.root.post {
            if (activity.isFinishing || activity.isDestroyed) {
                return@post
            }
            vm.pendingIncognitoNotice?.let { turnedOn ->
                vm.pendingIncognitoNotice = null
                AppSnackbar.make(
                    binding.root,
                    if (turnedOn) R.string.incognito_on_message else R.string.incognito_off_message,
                    Snackbar.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun onResume() {
        fullScreenButtonController.configure()
        if (doc.hasFile()) {
            shortcutBarController.configure()
        } else {
            binding.secondBarScrollView.visibility = View.GONE
        }
        inlineAnnotationActionController.rebuildHighlightSwatches()
    }

    fun refreshActions() {
        ui.updateActionBar()
        fullScreenButtonController.configure()
        if (doc.hasFile()) {
            shortcutBarController.configure()
        } else {
            binding.secondBarScrollView.visibility = View.GONE
        }
    }

    fun setCropMarginsEnabled(enabled: Boolean) {
        vm.cropMarginsEnabled = enabled
        refreshActions()
    }

    fun pickFile() {
        ui.runAfterDirtyAnnotationPrompt(PostSaveAction.OPEN_PICKER)
    }

    fun openPickerWithoutPrompt() {
        vm.pickerOpenedByBackButton = false
        launchPdfPicker()
    }

    fun pickFileOnBackPressed() {
        vm.pickerOpenedByBackButton = true
        launchPdfPicker()
    }

    fun openTextModeByDefault() {
        if (pref.getDefaultTextMode()) {
            navToTextMode()
        }
    }

    fun configureButtonsLabels() {
        if (pref.getHideButtonsLabels() == fullScreenOptionsManager.isLabelsVisible()) {
            fullScreenOptionsManager.toggleLabelVisibility(::drawableOf, activity::getString)
        }
    }

    private fun createHandlers(): ConfigurableActionResolver.Handlers {
        return ConfigurableActionResolver.Handlers(
            toggleFullscreen = { fullscreenController.toggleFullscreen() },
            exitFullscreen = { fullscreenController.exitFullscreen() },
            rotate = activity::rotateScreen,
            toggleHorizontalLock = { zoomSwipeLockController.toggleHorizontalSwipeLock() },
            readingDirection = ::showReadingDirectionDialog,
            toggleZoomLock = { zoomSwipeLockController.toggleZoomLock() },
            toggleCropMargins = activity::toggleCropMargins,
            toggleDualPage = ::toggleDualPageMode,
            screenshot = { screenshotController.takeScreenshot() },
            switchTheme = ::switchPdfTheme,
            navigateBack = { readerHistory.goBack() },
            navigateForward = { readerHistory.goForward() },
            showNavigationHistory = ::showNavigationHistory,
            reload = activity::reloadPdf,
            openLocal = ::pickFile,
            openOnline = { onlinePdfController.showOpenOnlinePdfDialog() },
            search = { showSearchDialog(activity, doc) { query, ignoreAccents -> readerNavigationController.startInlineSearch(query, ignoreAccents) } },
            goToPage = ::goToPage,
            extractText = { pageTextCopier.copyPageText() },
            textMode = ::navToTextMode,
            share = { activity.shareFile(doc.uri) },
            settings = ::openSettings,
            fileMetadata = activity::showFileMetadata,
            about = { activity.startActivity(navIntent(activity, AboutActivity::class.java)) },
            tableOfContents = { readerNavigationController.showTableOfContents() },
            toggleBookmark = { userBookmarkController.toggleCurrentPageBookmark() },
            userBookmarks = ::showUserBookmarks,
            userNotes = ::showUserNotes,
            userHighlights = ::showUserHighlights,
            linksInFile = { readerNavigationController.showLinks() },
            print = ::printFile,
            addSignature = { signatureController.showSignatureDialog() },
            toggleIncognito = ::toggleIncognito,
        )
    }

    private fun toggleDualPageMode() {
        if (!doc.hasFile()) {
            return
        }
        pref.setDualPageMode(!pref.getDualPageMode())
        vm.captureViewStateForSave(binding.pdfView.captureViewState())
        refreshActions()
        activity.displayFromUri(doc.uri)
    }

    private fun toggleIncognito() {
        vm.incognito = !vm.incognito
        vm.pendingIncognitoNotice = vm.incognito
        activity.recreate()
    }

    private fun openSelectedDocument(selectedDocumentUri: Uri?) {
        if (selectedDocumentUri == null) {
            return
        }
        if (doc.uri == null || selectedDocumentUri == doc.uri) {
            try {
                documentLoader.initPdf(selectedDocumentUri)
                activity.displayFromUri(doc.uri, true)
            } catch (e: Throwable) {
                Log.e(TAG, "openSelectedDocument: ", e)
                AppSnackbar.make(binding.root, R.string.file_opening_error, Snackbar.LENGTH_LONG).show()
            }
        } else {
            val intent = Intent(activity, activity.javaClass)
            intent.data = selectedDocumentUri
            if (vm.incognito) {
                intent.putExtra(PDF.incognitoKey, true)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            activity.startActivity(intent)
        }
    }

    fun startStaleDocumentRecovery(failedUri: Uri) {
        scope.launch {
            val knownHash = activity.intent.getStringExtra(HomeActivity.EXTRA_RECORD_HASH)
            val record = knownHash?.let { pdfRepository.findRecord(it) }
                ?: pdfRepository.findRecordByUri(failedUri.toString())
            if (activity.isFinishing || activity.isDestroyed) {
                return@launch
            }
            if (record == null) {
                showTemporaryFileGoneDialog()
                return@launch
            }
            if (record.uri != failedUri && isUriReadable(record.uri)) {
                activity.displayFromUri(record.uri)
                return@launch
            }
            val scannedFile = pdfRepository.findScannedPdfsByHash(record.hash)
                .map { File(it.path) }
                .let { files -> withContext(Dispatchers.IO) { files.firstOrNull { it.canRead() } } }
            if (activity.isFinishing || activity.isDestroyed) {
                return@launch
            }
            if (scannedFile != null) {
                val healedUri = Uri.fromFile(scannedFile)
                if (historyPolicy.canRecord()) {
                    pdfRepository.updateRecordIdentity(record.hash, healedUri, record.fileName, record.lastOpened)
                }
                AppSnackbar.make(binding.root, R.string.home_relocate_found, Snackbar.LENGTH_SHORT).show()
                activity.displayFromUri(healedUri)
                return@launch
            }
            showRelocateDialog(record)
        }
    }

    private fun showRelocateDialog(record: PdfRecord) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_relocate_title)
            .setMessage(activity.getString(R.string.home_relocate_message, record.fileName))
            .setPositiveButton(R.string.home_relocate_action) { _, _ ->
                vm.pendingRelocate = PendingRelocate(record.hash)
                relocatePickerLauncher.launch(arrayOf(PDF.FILE_TYPE))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> exitAfterFailedRecovery() }
            .setOnCancelListener { exitAfterFailedRecovery() }
            .show()
    }

    private fun showTemporaryFileGoneDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.stale_shared_title)
            .setMessage(R.string.stale_shared_message)
            .setPositiveButton(R.string.stale_shared_locate) { _, _ ->
                vm.pendingRelocate = PendingRelocate(null)
                relocatePickerLauncher.launch(arrayOf(PDF.FILE_TYPE))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> exitAfterFailedRecovery() }
            .setOnCancelListener { exitAfterFailedRecovery() }
            .show()
    }

    private fun showRelocateMismatchDialog(pickedUri: Uri) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_relocate_mismatch_title)
            .setMessage(R.string.home_relocate_mismatch_message)
            .setPositiveButton(R.string.home_open_anyway) { _, _ ->
                if (!vm.incognito) {
                    PersistedGrantKeeper.takeReadGrant(activity, pickedUri)
                }
                activity.displayFromUri(pickedUri, savePassword = true)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> exitAfterFailedRecovery() }
            .setOnCancelListener { exitAfterFailedRecovery() }
            .show()
    }

    private fun exitAfterFailedRecovery() {
        if (activity.intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false) && !pref.getHomeDisabled()) {
            activity.startActivity(Intent(activity, HomeActivity::class.java))
            activity.finish()
        } else if (activity.intent.data != null) {
            activity.finish()
        } else {
            pickFile()
        }
    }

    private suspend fun isUriReadable(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).canRead() } == true
            } else {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { } != null
                }.getOrDefault(false)
            }
        }
    }

    private fun launchPdfPicker() {
        try {
            pdfPickerLauncher.launch(arrayOf(PDF.FILE_TYPE))
        } catch (e: ActivityNotFoundException) {
            AppSnackbar.make(binding.root, R.string.toast_pick_file_error, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun canEditDocument(): Boolean {
        if (!annotationController.isSaving) {
            return true
        }
        AppSnackbar.make(binding.root, R.string.annotation_edit_blocked_while_saving, Snackbar.LENGTH_SHORT).show()
        return false
    }

    private fun onAnnotationEdit(edit: AnnotationEdit) {
        annotationController.recordEdit(edit)
        ui.updateDirtyUi()
    }

    private fun onHistoryChanged() {
        publishDebugHistory()
        val navState = readerHistory.canGoBack() to readerHistory.canGoForward()
        if (navState != historyNavState) {
            historyNavState = navState
            refreshActions()
        }
    }

    fun publishDebugHistory() {
        if (!BuildConfig.DEBUG) {
            return
        }
        val back = readerHistory.backEntries().joinToString(",") { it.pageIndex.toString() }
        val forward = readerHistory.forwardEntries().joinToString(",") { it.pageIndex.toString() }
        DebugRootDescription.put(binding.root, "hist", "back=$back;forward=$forward")
    }

    private fun toggleSecondBar() {
        pref.setSecondBarEnabled(binding.secondBarScrollView.visibility != View.VISIBLE)
        shortcutBarController.updateVisibility()
    }

    private fun switchPdfTheme() {
        pdfThemeController.switchPdfTheme(
            hasFile = { ui.checkHasFile() },
            onThemeChanged = ::refreshActions,
        )
    }

    private fun showReadingDirectionDialog() {
        if (!ui.checkHasFile()) {
            return
        }
        readingDirectionController.showDialog()
    }

    private fun showUserBookmarks() {
        if (!ui.checkHasFile()) {
            return
        }
        if (doc.fileHash == null) {
            AppSnackbar.make(binding.root, R.string.bookmark_hash_unavailable, Snackbar.LENGTH_SHORT).show()
            return
        }
        readerNavigationController.showUserBookmarks()
    }

    private fun showNavigationHistory() {
        if (!ui.checkHasFile()) {
            return
        }
        readerNavigationController.showNavigationHistory()
    }

    private fun showUserNotes() {
        if (!ui.checkHasFile()) {
            return
        }
        activity.runAfterAnnotationSaveGate(PostSaveAction.SHOW_USER_NOTES)
    }

    private fun showUserHighlights() {
        if (!ui.checkHasFile()) {
            return
        }
        activity.runAfterAnnotationSaveGate(PostSaveAction.SHOW_USER_HIGHLIGHTS)
    }

    private fun printFile() {
        if (!ui.checkHasFile()) {
            return
        }
        printController.printFile()
    }

    private fun navToTextMode() {
        if (!ui.checkHasFile()) {
            return
        }
        val currentPageIndex = currentPdfViewPageIndex()
        Intent(activity, TextModeActivity::class.java).also {
            it.putExtra(PDF.filePathKey, doc.uri.toString())
            it.putExtra(PDF.passwordKey, doc.password)
            it.putExtra(PDF.pageNumberKey, currentPageIndex)
            it.putExtra(PDF.incognitoKey, vm.incognito)
            doc.fileHash?.let { fileHash -> it.putExtra(PDF.fileHashKey, fileHash) }
            it.grantPdfReadAccess(doc.uri.toString())
            textModeLauncher.launch(it)
        }
    }

    private fun openSettings() {
        vm.alwaysHideMarginsAtSettingsOpen = pref.getAlwaysHideMargins()
        val settingsIntent = Intent(activity, SettingsActivity::class.java)
        settingsIntent.putExtra(PDF.incognitoKey, vm.incognito)
        settingsLauncher.launch(settingsIntent)
    }

    private fun currentPdfViewPageIndex(): Int {
        val currentPage = binding.pdfView.currentPage.coerceAtLeast(0)
        val pageCount = binding.pdfView.pageCount
        return if (pageCount > 0) currentPage.coerceAtMost(pageCount - 1) else currentPage
    }

    private fun goToPage() {
        showGoToPageDialog(
            activity,
            doc.pageNumber,
            doc.length,
            doc.uri,
            doc.password,
            goToPageFunc = { pageIndex ->
                readerHistory.recordJump(ReaderHistoryManager.Origin.GO_TO, pageIndex)
                binding.pdfView.jumpTo(pageIndex)
            },
            showAllPages = { readerNavigationController.showGoToPageGrid() },
        )
    }

    private fun toggleLabels() {
        fullScreenOptionsManager.toggleLabelVisibility(::drawableOf, activity::getString)
        pref.setHideButtonsLabels(!pref.getHideButtonsLabels())
    }

    private fun drawableOf(id: Int): Drawable? {
        return AppCompatResources.getDrawable(activity, id)
    }

    companion object {
        private const val TAG = "ReaderComposition"
        private val backgroundSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
