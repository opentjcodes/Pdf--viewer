// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.pm.ActivityInfo
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.data.annotation.SourceKey
import com.gitlab.mudlej.MjPdfReader.pdf.ReadingDirection
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.SaveOutcome

class ReaderViewModel(state: SavedStateHandle) : ViewModel() {

    val doc = DocumentState()

    var currentLoadToken = 0L
        private set
    var pendingViewState: PDFView.ViewState? = null
    var revalidateDocumentOnLoad = false
    var cropMarginsEnabled = false

    private var positionPersistSuppressed = false
    private var positionPersistSuppressedAtPage = 0

    // Session scoped by design. Do not add these to the saved state.
    // The rotation lock must not survive an app restart.
    var userOrientationLock = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    var orientationDocId: String? = null
    var isFullScreenToggled = false
    var incognito = false
    var pendingIncognitoNotice: Boolean? = null
    var isBrightnessClicked = false
    var isAutoScrollClicked = false
    var isAutoScrolling = false

    var loadedAnnotationDocumentUri: Uri? = null
    var annotationSaveDestinationUri: Uri? = null
    var annotationSaveDestinationDurable = false
    var hasUnsavedAnnotations = false
    var hasRecoverableUnsavedAnnotations = false
    var hasUnrecoverableUnsavedAnnotations = false
    var isSavingAnnotations = false
    val sessionOwnedAnnotationKeys = mutableSetOf<String>()
    var onSaveComplete: ((SaveOutcome) -> Unit)? = null

    val bookmarkedPages = mutableSetOf<Int>()
    var bookmarksLoadedForHash: String? = null
    var bookmarkActionState = false

    var signatureDirtyBeforePlacement = false
    var pendingSignaturePage = -1
    var pendingSignatureRect: RectF? = null

    var pickerOpenedByBackButton = false
    var pendingRelocate: PendingRelocate? = null
    var pendingPostSaveAction: PostSaveAction? = null
    var pendingPostSaveActionUri: Uri? = null
    var pendingAnnotationSourceUri: Uri? = null
    var pendingAnnotationDestinationIsCopy = false
    var alwaysHideMarginsAtSettingsOpen: Boolean? = null

    init {
        state.setSavedStateProvider(STATE_KEY) { snapshotState() }
        state.get<Bundle>(STATE_KEY)?.let { restoreState(it) }
    }

    fun isCurrent(loadToken: Long, uri: Uri?): Boolean {
        return currentLoadToken == loadToken && acceptsDocumentUri(uri)
    }

    fun acceptsDocumentUri(uri: Uri?): Boolean {
        return uri == null || uri == doc.uri || uri == loadedAnnotationDocumentUri
    }

    fun setPage(pageIndex: Int) {
        doc.pageNumber = pageIndex
    }

    fun suppressPositionPersistAt(pageIndex: Int) {
        positionPersistSuppressed = true
        positionPersistSuppressedAtPage = pageIndex
    }

    fun clearPositionPersistSuppression() {
        positionPersistSuppressed = false
    }

    fun canPersistPosition(pageIndex: Int): Boolean {
        if (!positionPersistSuppressed) {
            return true
        }
        if (pageIndex == positionPersistSuppressedAtPage) {
            return false
        }
        positionPersistSuppressed = false
        return true
    }

    fun beginNewDocument(uri: Uri, cropMarginsDefault: Boolean) {
        currentLoadToken++
        revalidateDocumentOnLoad = false
        doc.uri = uri
        doc.fileHash = null
        doc.pageNumber = 0
        clearPositionPersistSuppression()
        doc.pageRangeStart = 0
        doc.pageRangeEnd = 0
        doc.autoScrollSpeed = null
        doc.readingDirectionResolved = false
        doc.readingDirectionOverride = null
        doc.detectedReadingDirection = null
        doc.effectiveReadingDirection = ReadingDirection.LEFT_TO_RIGHT
        pendingViewState = null
        cropMarginsEnabled = cropMarginsDefault
        bookmarkedPages.clear()
        bookmarksLoadedForHash = null
        bookmarkActionState = false
        resetAnnotationsForDocument(uri)
        signatureDirtyBeforePlacement = false
        pendingSignaturePage = -1
        pendingSignatureRect = null
    }

    fun resetAnnotationsForDocument(uri: Uri?) {
        loadedAnnotationDocumentUri = uri
        annotationSaveDestinationUri = null
        annotationSaveDestinationDurable = false
        hasUnsavedAnnotations = false
        hasRecoverableUnsavedAnnotations = false
        hasUnrecoverableUnsavedAnnotations = false
        isSavingAnnotations = false
    }

    fun captureViewStateForSave(captured: PDFView.ViewState?) {
        pendingViewState = captured ?: pendingViewState
    }

    private fun snapshotState(): Bundle {
        val out = Bundle()
        out.putParcelable(PDF.uriKey, doc.uri)
        out.putString(PDF.fileHashKey, doc.fileHash)
        out.putBoolean(KEY_REVALIDATE_DOCUMENT, revalidateDocumentOnLoad)
        out.putInt(PDF.pageNumberKey, doc.pageNumber)
        out.putString(PDF.passwordKey, doc.password)
        out.putBoolean(KEY_READING_DIRECTION_RESOLVED, doc.readingDirectionResolved)
        out.putString(PDF.readingDirectionOverrideKey, doc.readingDirectionOverride?.id)
        out.putString(PDF.detectedReadingDirectionKey, doc.detectedReadingDirection?.id)
        out.putString(PDF.effectiveReadingDirectionKey, doc.effectiveReadingDirection.id)
        out.putBoolean(PDF.isFullScreenToggledKey, isFullScreenToggled)
        out.putBoolean(PDF.incognitoKey, incognito)
        doc.autoScrollSpeed?.let { out.putInt(PDF.autoScrollSpeedKey, it) }
        out.putBoolean(PDF.cropMarginsEnabledKey, cropMarginsEnabled)
        pendingViewState?.let { viewState ->
            out.putBoolean(PDF.viewStateSavedKey, true)
            out.putFloat(PDF.viewStateZoomKey, viewState.zoom)
            out.putInt(PDF.viewStatePageIndexKey, viewState.pageIndex)
            out.putBoolean(PDF.viewStateSwipeVerticalKey, viewState.swipeVertical)
            out.putBoolean(PDF.viewStateHorizontalReadingDirectionRtlKey, viewState.horizontalReadingDirectionRtl)
            out.putFloat(PDF.viewStateRelativeCrossAxisCenterKey, viewState.relativeCrossAxisCenter)
            out.putFloat(PDF.viewStatePageCenterOffsetRatioKey, viewState.pageCenterOffsetRatio)
            out.putInt(PDF.viewStatePagesPerRowKey, viewState.pagesPerRow)
            out.putBoolean(PDF.viewStateFirstPageAloneKey, viewState.firstPageAlone)
        }
        if (hasRecoverableUnsavedAnnotations) {
            out.putBoolean(PDF.hasUnsavedAnnotationsKey, hasUnsavedAnnotations)
            out.putStringArrayList(PDF.sessionOwnedAnnotationKeysKey, ArrayList(sessionOwnedAnnotationKeys))
        }
        out.putParcelable(KEY_LOADED_ANNOTATION_URI, loadedAnnotationDocumentUri)
        val signatureRect = pendingSignatureRect
        if (signatureRect != null && pendingSignaturePage >= 0) {
            out.putBoolean(KEY_SIGNATURE_PENDING, true)
            out.putInt(KEY_SIGNATURE_PAGE, pendingSignaturePage)
            out.putFloatArray(
                KEY_SIGNATURE_RECT,
                floatArrayOf(signatureRect.left, signatureRect.top, signatureRect.right, signatureRect.bottom),
            )
            out.putBoolean(KEY_SIGNATURE_DIRTY_BEFORE, signatureDirtyBeforePlacement)
        }
        out.putBoolean(KEY_PICKER_BACK, pickerOpenedByBackButton)
        pendingRelocate?.let { pending ->
            out.putBoolean(KEY_RELOCATE_PENDING, true)
            out.putString(KEY_RELOCATE_HASH, pending.hash)
        }
        out.putString(KEY_POST_SAVE_ACTION, pendingPostSaveAction?.name)
        out.putParcelable(KEY_POST_SAVE_URI, pendingPostSaveActionUri)
        out.putParcelable(KEY_ANNOTATION_SOURCE_URI, pendingAnnotationSourceUri)
        out.putBoolean(KEY_ANNOTATION_DESTINATION_COPY, pendingAnnotationDestinationIsCopy)
        alwaysHideMarginsAtSettingsOpen?.let { out.putBoolean(KEY_MARGINS_BASELINE, it) }
        return out
    }

    private fun restoreState(saved: Bundle) {
        doc.uri = saved.getParcelable(PDF.uriKey)
        doc.fileHash = saved.getString(PDF.fileHashKey)
        revalidateDocumentOnLoad = saved.getBoolean(KEY_REVALIDATE_DOCUMENT, false)
        doc.pageNumber = saved.getInt(PDF.pageNumberKey)
        doc.password = saved.getString(PDF.passwordKey)
        doc.readingDirectionResolved = saved.getBoolean(KEY_READING_DIRECTION_RESOLVED, false)
        doc.readingDirectionOverride = ReadingDirection.fromOverrideId(
            saved.getString(PDF.readingDirectionOverrideKey),
        )
        doc.detectedReadingDirection = ReadingDirection.fromId(saved.getString(PDF.detectedReadingDirectionKey))
        doc.effectiveReadingDirection = ReadingDirection.fromId(
            saved.getString(PDF.effectiveReadingDirectionKey),
        ) ?: ReadingDirection.LEFT_TO_RIGHT
        isFullScreenToggled = saved.getBoolean(PDF.isFullScreenToggledKey)
        incognito = saved.getBoolean(PDF.incognitoKey, false)
        doc.autoScrollSpeed = saved.takeIf { it.containsKey(PDF.autoScrollSpeedKey) }
            ?.getInt(PDF.autoScrollSpeedKey)
        cropMarginsEnabled = saved.getBoolean(PDF.cropMarginsEnabledKey, false)
        if (saved.getBoolean(PDF.viewStateSavedKey, false)) {
            pendingViewState = PDFView.ViewState(
                saved.getFloat(PDF.viewStateZoomKey, 1f),
                saved.getInt(PDF.viewStatePageIndexKey, 0),
                saved.getBoolean(PDF.viewStateSwipeVerticalKey, true),
                saved.getBoolean(PDF.viewStateHorizontalReadingDirectionRtlKey, false),
                saved.getFloat(PDF.viewStateRelativeCrossAxisCenterKey, 0.5f),
                saved.getFloat(PDF.viewStatePageCenterOffsetRatioKey, 0.5f),
                saved.getInt(PDF.viewStatePagesPerRowKey, 1),
                saved.getBoolean(PDF.viewStateFirstPageAloneKey, false),
            )
        }
        loadedAnnotationDocumentUri = saved.getParcelable(KEY_LOADED_ANNOTATION_URI)
        saved.getStringArrayList(PDF.sessionOwnedAnnotationKeysKey)?.let(sessionOwnedAnnotationKeys::addAll)
        if (saved.getBoolean(PDF.hasUnsavedAnnotationsKey, false)) {
            hasUnsavedAnnotations = true
            hasRecoverableUnsavedAnnotations = true
            doc.uri?.let { sessionOwnedAnnotationKeys.add(SourceKey.of(it)) }
        }
        if (saved.getBoolean(KEY_SIGNATURE_PENDING, false)) {
            pendingSignaturePage = saved.getInt(KEY_SIGNATURE_PAGE, -1)
            val values = saved.getFloatArray(KEY_SIGNATURE_RECT)
            if (pendingSignaturePage >= 0 && values != null && values.size == 4) {
                pendingSignatureRect = RectF(values[0], values[1], values[2], values[3])
            }
            signatureDirtyBeforePlacement = saved.getBoolean(KEY_SIGNATURE_DIRTY_BEFORE, false)
        }
        pickerOpenedByBackButton = saved.getBoolean(KEY_PICKER_BACK, false)
        if (saved.getBoolean(KEY_RELOCATE_PENDING, false)) {
            pendingRelocate = PendingRelocate(saved.getString(KEY_RELOCATE_HASH))
        }
        pendingPostSaveAction = saved.getString(KEY_POST_SAVE_ACTION)?.let { name ->
            PostSaveAction.entries.firstOrNull { it.name == name }
        }
        pendingPostSaveActionUri = saved.getParcelable(KEY_POST_SAVE_URI)
        pendingAnnotationSourceUri = saved.getParcelable(KEY_ANNOTATION_SOURCE_URI)
        pendingAnnotationDestinationIsCopy = saved.getBoolean(KEY_ANNOTATION_DESTINATION_COPY, false)
        if (saved.containsKey(KEY_MARGINS_BASELINE)) {
            alwaysHideMarginsAtSettingsOpen = saved.getBoolean(KEY_MARGINS_BASELINE)
        }
    }

    private companion object {
        const val KEY_READING_DIRECTION_RESOLVED = "readingDirectionResolved"
        const val KEY_REVALIDATE_DOCUMENT = "revalidateDocumentOnLoad"
        const val STATE_KEY = "readerViewModelState"
        const val KEY_LOADED_ANNOTATION_URI = "loadedAnnotationDocumentUri"
        const val KEY_SIGNATURE_PENDING = "signaturePlacementPending"
        const val KEY_SIGNATURE_PAGE = "signaturePlacementPage"
        const val KEY_SIGNATURE_RECT = "signaturePlacementRect"
        const val KEY_SIGNATURE_DIRTY_BEFORE = "signaturePlacementDirtyBefore"
        const val KEY_PICKER_BACK = "pickerOpenedByBackButton"
        const val KEY_RELOCATE_PENDING = "relocatePending"
        const val KEY_RELOCATE_HASH = "relocatePendingHash"
        const val KEY_POST_SAVE_ACTION = "pendingPostSaveAction"
        const val KEY_POST_SAVE_URI = "pendingPostSaveActionUri"
        const val KEY_ANNOTATION_SOURCE_URI = "pendingAnnotationSourceUri"
        const val KEY_ANNOTATION_DESTINATION_COPY = "pendingAnnotationDestinationIsCopy"
        const val KEY_MARGINS_BASELINE = "alwaysHideMarginsAtSettingsOpen"
    }
}

class PendingRelocate(val hash: String?)
