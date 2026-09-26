// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.load

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.PDFView.Configurator
import com.github.barteksc.pdfviewer.model.CropMargins
import com.github.barteksc.pdfviewer.util.Constants
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.resolveReadingLayout
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderUi
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection.ReadingDirectionResolver
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.io.getFileName
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.shockwave.pdfium.PdfPasswordException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentLoader(
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val pref: Preferences,
    private val pdfRepository: PdfRepository,
    private val historyPolicy: HistoryPolicy,
    private val readingDirectionResolver: ReadingDirectionResolver,
    private val scope: CoroutineScope,
    private val ui: ReaderUi,
    private val openOnlineDocument: (Uri) -> Unit,
    private val decorateConfigurator: (Configurator) -> Configurator,
) {

    var state: LoadState = LoadState.Idle
        private set

    private val listeners = mutableListOf<DocumentListener>()
    private var hashErrorShownForToken = -1L
    private val doc get() = vm.doc
    private val context get() = binding.root.context

    private val source = DocumentSource(context)
    private val records = DocumentRecords(
        binding = binding,
        vm = vm,
        pdfRepository = pdfRepository,
        historyPolicy = historyPolicy,
        readingDirectionResolver = readingDirectionResolver,
        source = source,
        onRecordAvailable = { fileHash -> emit { it.onRecordAvailable(fileHash) } },
    )

    fun subscribe(listener: DocumentListener) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: DocumentListener) {
        listeners.remove(listener)
    }

    private inline fun emit(action: (DocumentListener) -> Unit) {
        listeners.forEach(action)
    }

    fun prepareNewDocument(uri: Uri) {
        if (doc.uri == uri) {
            return
        }
        state = LoadState.Idle
        emit { it.onDocumentReset() }
        vm.beginNewDocument(uri, pref.getAlwaysHideMargins())
        OnlineDocumentStore.sweepIncognito(context)
        ui.updateDirtyUi()
    }

    fun initPdf(uri: Uri) {
        prepareNewDocument(uri)
        val loadToken = vm.currentLoadToken
        scope.launch {
            val hash = resolveDocumentIdentity(doc.uri)
            if (vm.isCurrent(loadToken, uri)) {
                doc.fileHash = hash
            }
        }
    }

    private suspend fun resolveDocumentIdentity(uri: Uri?): String? {
        return pdfRepository.resolveIdentity(context, uri)
    }

    fun displayFromUri(uri: Uri?, savePassword: Boolean = false) {
        if (uri == null) {
            ui.updateActionBar()
            return
        }

        prepareNewDocument(uri)

        doc.name = getFileName(context, uri)
        ui.updateActionBar()
        ui.updateTitle()
        doc.resetLength()

        val scheme = uri.scheme
        val isOnline = scheme.equals("http", ignoreCase = true) ||
            scheme.equals("https", ignoreCase = true) ||
            scheme?.contains("org.nextcloud.documents") == true
        if (isOnline) {
            openOnlineDocument(uri)
        }
        else {
            loadCurrentDocument(savePassword)
        }
    }

    fun loadCurrentDocument(savePassword: Boolean = false) {
        val uri = doc.uri ?: return
        val file = OnlineDocumentStore.fileFor(context, uri.toString())
        val configurator = if (file != null) {
            binding.pdfView.fromFile(file)
        } else {
            binding.pdfView.fromUri(uri)
        }
        initPdfViewAndLoad(configurator, savePassword = savePassword)
    }

    fun initPdfViewAndLoad(viewConfigurator: Configurator, savePassword: Boolean = false) {
        var loadToken = vm.currentLoadToken
        val documentUri = doc.uri
        val previousHash = doc.fileHash
        state = LoadState.Loading
        scheduleLoadProgress(loadToken, documentUri)
        scope.launch {
            val hash = if (vm.revalidateDocumentOnLoad) {
                resolveDocumentIdentity(documentUri) ?: previousHash
            } else {
                previousHash ?: resolveDocumentIdentity(documentUri)
            }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            vm.revalidateDocumentOnLoad = false
            if (previousHash != null && hash != null && hash != previousHash && documentUri != null) {
                emit { it.onDocumentReset() }
                vm.beginNewDocument(documentUri, pref.getAlwaysHideMargins())
                doc.password = null
                loadToken = vm.currentLoadToken
                ui.updateDirtyUi()
                scheduleLoadProgress(loadToken, documentUri)
            }
            val viewState = vm.pendingViewState
            if (hash == null && doc.pageNumber == 0) {
                if (!canLoadWithoutHash(loadToken, documentUri, savePassword)) {
                    return@launch
                }
            }

            if (hash != null) {
                doc.fileHash = hash
            }

            resolveAnnotationDestination(documentUri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val openAtFirstPageOverride = doc.pageNumber == 0 && pref.getAlwaysOpenAtFirstPage()
            val pageNumber = if (doc.pageNumber == 0 && hash != null && !openAtFirstPageOverride) {
                pdfRepository.findPageNumber(hash)
            } else {
                doc.pageNumber
            }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val autoScrollSpeed = hash?.let { pdfRepository.findAutoScrollSpeed(it) }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val readingDirectionState = readingDirectionResolver.resolve(hash, documentUri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val cachedCropMargins = findCachedCropMargins(hash)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            vm.setPage(pageNumber)
            if (openAtFirstPageOverride) {
                vm.suppressPositionPersistAt(pageNumber)
            } else {
                vm.clearPositionPersistSuppression()
            }
            doc.autoScrollSpeed = doc.autoScrollSpeed ?: autoScrollSpeed
            doc.readingDirectionOverride = readingDirectionState.overrideDirection
            doc.detectedReadingDirection = readingDirectionState.detectedDirection
            doc.effectiveReadingDirection = readingDirectionState.effectiveDirection
            doc.readingDirectionResolved = true
            withContext(Dispatchers.Main) {
                if (vm.isCurrent(loadToken, documentUri)) {
                    configureAndLoad(
                        viewConfigurator,
                        pageNumber,
                        savePassword,
                        cachedCropMargins,
                        hash,
                        loadToken,
                        documentUri,
                        readingDirectionState.effectiveDirection.isRightToLeft,
                        viewState,
                    )
                }
            }
        }
    }

    fun reloadWithCropMargins(
        configurator: Configurator,
        pageNumber: Int,
        cropMargins: CropMargins,
        viewState: PDFView.ViewState?,
    ) {
        val zoomDisabled = binding.pdfView.isZoomDisabled
        val horizontalSwipeDisabled = binding.pdfView.isHorizontalSwipeDisabled
        configureAndLoad(
            configurator,
            pageNumber,
            savePassword = false,
            cachedCropMargins = cropMargins,
            fileHash = doc.fileHash,
            loadToken = vm.currentLoadToken,
            documentUri = doc.uri,
            readingDirectionRtl = doc.effectiveReadingDirection.isRightToLeft,
            viewState = viewState,
            applyDocumentLoadDefaults = false,
            zoomDisabled = zoomDisabled,
            horizontalSwipeDisabled = horizontalSwipeDisabled,
        )
    }

    fun applyTileRenderingPreferences() {
        val partSize = pref.getPartSize()
        Constants.PART_SIZE = partSize
        val tilePixels = partSize * partSize
        Constants.Cache.CACHE_SIZE =
            (TILE_CACHE_PIXEL_BUDGET / tilePixels).toInt().coerceIn(MIN_TILE_CACHE_SIZE, MAX_TILE_CACHE_SIZE)
    }

    fun showFailedToComputeHashError() {
        val loadToken = vm.currentLoadToken
        if (hashErrorShownForToken == loadToken) {
            return
        }
        hashErrorShownForToken = loadToken
        val message = context.getString(R.string.hash_failed_notice)
        AppSnackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Log.e(TAG, "showFailedToComputeHashError: $message", RuntimeException())
    }

    private suspend fun canLoadWithoutHash(loadToken: Long, documentUri: Uri?, savePassword: Boolean): Boolean {
        val failedUri = doc.uri
        val readable = failedUri != null && source.isReadable(failedUri)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return false
        }
        if (readable) {
            showFailedToComputeHashError()
            return true
        }

        val recoveredFile = failedUri?.let { source.canonicalFile(it) }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return false
        }
        val recoveredUri = recoveredFile?.let(Uri::fromFile)
        if (recoveredUri != null && recoveredUri != doc.uri) {
            withContext(Dispatchers.Main) {
                displayFromUri(recoveredUri, savePassword)
            }
        } else {
            val failure = DocumentUnreachableException(failedUri)
            state = LoadState.Failed(failure)
            withContext(Dispatchers.Main) {
                hideProgressBar(loadToken, documentUri)
                emit { it.onLoadFailed(failure) }
            }
        }
        return false
    }

    private suspend fun resolveAnnotationDestination(documentUri: Uri?) {
        if (documentUri == null) {
            vm.annotationSaveDestinationUri = null
            vm.annotationSaveDestinationDurable = false
            return
        }
        val destination = pdfRepository.findAnnotationSaveDestinationByDestinationUri(documentUri.toString())
        val destinationUri = destination?.destinationUri?.toUri()
        vm.annotationSaveDestinationUri = destinationUri
        vm.annotationSaveDestinationDurable = destinationUri != null
    }

    private suspend fun findCachedCropMargins(fileHash: String?): CropMargins? {
        if (!vm.cropMarginsEnabled || fileHash == null) {
            return null
        }
        val storedCropMargins = pdfRepository.findCropMargins(fileHash, CropMargins.STORAGE_VERSION)
            ?: return null
        return CropMargins.fromStorageString(storedCropMargins)
    }

    private fun configureAndLoad(
        viewConfigurator: Configurator,
        pageNumber: Int,
        savePassword: Boolean,
        cachedCropMargins: CropMargins?,
        fileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
        readingDirectionRtl: Boolean,
        viewState: PDFView.ViewState? = null,
        applyDocumentLoadDefaults: Boolean = true,
        zoomDisabled: Boolean = false,
        horizontalSwipeDisabled: Boolean = false,
    ) {
        val pdfView = binding.pdfView
        applyTileRenderingPreferences()
        pdfView.useBestQuality(pref.getHighQuality())
        pdfView.minZoom = Preferences.minZoomDefault
        pdfView.midZoom = Preferences.midZoomDefault
        pdfView.maxZoom = pref.getMaxZoom()
        val spacing = if (pref.getSpaceBetweenPages()) Preferences.spacingDefault else 0
        val layout = resolveReadingLayout(pref)

        val configurator = viewConfigurator
            .defaultPage(pageNumber)
            .defaultViewState(viewState)
            .onPageChange { page: Int, pageCount: Int -> setCurrentPage(page, pageCount, fileHash, loadToken, documentUri) }
            .enableAnnotationRendering(Preferences.annotationRenderingDefault)
            .enableAntialiasing(pref.getAntiAliasing())
            .renderDuringScale(true)
            .debugChecks(BuildConfig.DEBUG)
            .mainThreadChecks(true)
            .spacing(spacing)
            .onError { exception: Throwable ->
                state = if (exception is PdfPasswordException) LoadState.PasswordRequired else LoadState.Failed(exception)
                hideProgressBar(loadToken, documentUri)
                emit { it.onLoadFailed(exception) }
            }
            .onPageError { page: Int, error: Throwable -> reportLoadPageError(page, error) }
            .pageFitPolicy(layout.fitPolicy)
            .threeStepDoubleTapZoom(pref.getDoubleTapThreeStepZoom())
            .password(doc.password)
            .swipeHorizontal(layout.swipeHorizontal)
            .horizontalReadingDirectionRtl(layout.swipeHorizontal && readingDirectionRtl)
            .disableHorizontalSwipe(horizontalSwipeDisabled)
            .zoomDisabled(zoomDisabled)
            .autoSpacing(layout.autoSpacing)
            .pagesPerRow(if (layout.dualPage) 2 else 1)
            .firstPageAlone(pref.getDualPageFirstPageAlone())
            .pageSnap(layout.pageSnap)
            .pageFling(layout.pageFling)
            .freeScrollMode(layout.freeScroll)
            .enableTextSelection(pref.getInlineTextSelection())
            .textSelectionColor(MaterialColors.getColor(binding.root, R.attr.colorPrimary))
            .cropMargins(vm.cropMarginsEnabled)
            .cachedCropMargins(cachedCropMargins)
            .onLoad { pageCount ->
                if (vm.pendingViewState === viewState) {
                    vm.pendingViewState = null
                }
                state = LoadState.Loaded(pageCount)
                doc.initPdfLength(pageCount)
                hideProgressBar(loadToken, documentUri)
                createPdfRecord(savePassword, fileHash, loadToken, documentUri)
                val event = DocumentLoadedEvent(
                    pageCount,
                    cachedCropMargins,
                    fileHash,
                    loadToken,
                    documentUri,
                    applyDocumentLoadDefaults,
                )
                emit { it.onDocumentLoaded(event) }
            }

        decorateConfigurator(configurator).load()
    }

    private fun createPdfRecord(
        savePassword: Boolean,
        expectedFileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        scope.launch {
            records.persistRecord(savePassword, expectedFileHash, loadToken, documentUri)
        }
    }

    fun persistCurrentDocument() {
        val documentUri = doc.uri ?: return
        val loadToken = vm.currentLoadToken
        scope.launch {
            records.persistRecord(false, doc.fileHash, loadToken, documentUri)
            val fileHash = doc.fileHash
            if (fileHash != null && vm.isCurrent(loadToken, documentUri) && historyPolicy.canRecord()) {
                pdfRepository.setPageNumber(fileHash, doc.pageNumber)
            }
        }
    }

    private fun setCurrentPage(
        pageNumber: Int,
        pageCount: Int,
        expectedFileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        vm.setPage(pageNumber)
        doc.pageRangeStart = binding.pdfView.getRowFirstPage(pageNumber)
        doc.pageRangeEnd = binding.pdfView.getRowLastPage(pageNumber)
        doc.initPdfLength(pageCount)
        ui.updateTitle()
        emit { it.onPageChanged(pageNumber) }
        val rangeStart = minOf(doc.pageRangeStart, pageNumber)
        val announcement = if (doc.pageRangeEnd > rangeStart) {
            context.getString(R.string.pages_x_to_y_of_z, rangeStart + 1, doc.pageRangeEnd + 1, pageCount)
        } else {
            context.getString(R.string.page_x_of_y, pageNumber + 1, pageCount)
        }
        binding.pdfView.announceForAccessibility(announcement)

        scope.launch {
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            val hash = doc.fileHash ?: expectedFileHash ?: resolveDocumentIdentity(doc.uri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            if (hash != null) {
                doc.fileHash = hash
                emit { it.onFileHashComputed() }
                attachPreviewDiskIfCurrent(hash, loadToken, documentUri)
                if (historyPolicy.canRecord() && vm.canPersistPosition(pageNumber)) {
                    pdfRepository.setPageNumber(hash, pageNumber)
                }
            }
            else {
                showFailedToComputeHashError()
            }
        }
    }

    private suspend fun attachPreviewDiskIfCurrent(fileHash: String, loadToken: Long, documentUri: Uri?) {
        val sizeBytes = source.sizeBytes(doc.uri)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        PreviewDiskCoordinator.attach(
            pdfView = binding.pdfView,
            cacheDir = context.cacheDir,
            fileHash = fileHash,
            pageCount = binding.pdfView.getPageCount(),
            sizeBytes = sizeBytes,
            incognito = vm.incognito,
            hasPassword = doc.password != null,
        )
    }

    private fun reportLoadPageError(page: Int, error: Throwable) {
        val message = context.resources.getString(R.string.cannot_load_page) + page + " " + error
        AppSnackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    private fun scheduleLoadProgress(loadToken: Long, documentUri: Uri?) {
        scope.launch {
            delay(LOCAL_LOAD_PROGRESS_DELAY_MS)
            if (vm.isCurrent(loadToken, documentUri) && state == LoadState.Loading) {
                ui.showProgress()
            }
        }
    }

    private fun hideProgressBar(loadToken: Long, documentUri: Uri?) {
        if (vm.isCurrent(loadToken, documentUri)) {
            ui.hideProgress()
        }
    }

    private companion object {
        const val TAG = "DocumentLoader"
        const val LOCAL_LOAD_PROGRESS_DELAY_MS = 150L
        const val TILE_CACHE_PIXEL_BUDGET = 2 * 120 * 256 * 256
        const val MIN_TILE_CACHE_SIZE = 24
        const val MAX_TILE_CACHE_SIZE = 480
    }
}
