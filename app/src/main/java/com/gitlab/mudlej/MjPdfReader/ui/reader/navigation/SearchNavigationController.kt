// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.SearchTestHooks
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.SearchResult
import com.gitlab.mudlej.MjPdfReader.pdf.grantPdfReadAccess
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.search.SearchActivity
import com.gitlab.mudlej.MjPdfReader.ui.search.SearchCoordinator
import com.gitlab.mudlej.MjPdfReader.ui.search.SearchSessionCache
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.text.NormalizedTextMapper
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class SearchNavigationController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val pdf: DocumentState,
    private val pref: Preferences,
    private val isIncognito: () -> Boolean,
    private val historyManager: ReaderHistoryManager,
    private val scope: CoroutineScope,
    private val launchSearch: (Intent) -> Unit,
) {

    private var hits: List<SearchSessionCache.Hit> = emptyList()
    private var currentPosition = -1
    private var hasFullSession = false
    private var subscribedToActiveSearch = false
    private var query = ""
    private var ignoreAccents = false
    private var pendingAutoJump = false
    private var autoJumpAnchorPage = 0
    private var activeHighlightPageNumber: Int? = null
    private var snackbar: Snackbar? = null
    private var counterView: TextView? = null
    private var messageView: TextView? = null
    private var pendingLabelRestore: Runnable? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var showHitJob: Job? = null
    private var rawTextCachePage = -1
    private var rawTextCache: String? = null

    private val activeSearchListener = object : SearchCoordinator.Listener {
        override fun onProgress(pagesScanned: Int, pageCount: Int) = Unit

        override fun onResults(results: List<SearchResult>, finished: Boolean) {
            if (!subscribedToActiveSearch) {
                return
            }
            val currentResultIndex = hits.getOrNull(currentPosition)?.resultIndex
            hits = SearchCoordinator.appendHits(hits, results)
            if (finished) {
                subscribedToActiveSearch = false
                hasFullSession = hits.isNotEmpty()
            }
            if (pendingAutoJump) {
                maybeAutoJump(finished)
                updateControls()
                return
            }
            currentPosition = hits
                .indexOfFirst { it.resultIndex == currentResultIndex }
                .takeIf { it >= 0 }
                ?: currentPosition.coerceIn(0, hits.lastIndex.coerceAtLeast(0))
            updateControls()
        }
    }

    val isActive: Boolean
        get() = snackbar != null || activeHighlightPageNumber != null

    fun start(searchResult: SearchResult, resultQuery: String?, resultIgnoreAccents: Boolean) {
        query = resultQuery?.trim().takeUnless { it.isNullOrBlank() }
            ?: pdf.lastQuery?.trim().orEmpty()
        ignoreAccents = resultIgnoreAccents
        unsubscribeFromActiveSearch()
        val session = SearchSessionCache.get(pdf.fileHash, query, ignoreAccents)
        hasFullSession = session != null
        hits = session?.hits?.sortedBy { it.resultIndex }
            ?: listOf(
                SearchSessionCache.Hit(
                    pageNumber = searchResult.pageNumber,
                    originalIndex = searchResult.originalIndex,
                    resultIndex = searchResult.searchResultIndexInList,
                    text = searchResult.text,
                    inputStart = searchResult.inputStart,
                    inputEnd = searchResult.inputEnd,
                )
            )
        if (session == null) {
            subscribedToActiveSearch =
                SearchCoordinator.subscribeIfRunning(pdf.fileHash, query, ignoreAccents, activeSearchListener)
        }
        currentPosition = hits
            .indexOfFirst { it.resultIndex == searchResult.searchResultIndexInList }
            .takeIf { it >= 0 }
            ?: 0
        historyManager.recordJump(ReaderHistoryManager.Origin.SEARCH, searchResult.pageNumber - 1)
        showSnackbar()
        showCurrentHit()
    }

    fun startQuery(rawQuery: String, rawIgnoreAccents: Boolean) {
        reset()
        query = rawQuery.trim()
        ignoreAccents = rawIgnoreAccents
        autoJumpAnchorPage = binding.pdfView.visiblePageIndex
        val session = SearchSessionCache.get(pdf.fileHash, query, ignoreAccents)
        if (session != null) {
            hasFullSession = true
            hits = session.hits.sortedBy { it.resultIndex }
            showSnackbar()
            if (hits.isEmpty()) {
                enterNoResultsState()
                updateControls()
                return
            }
            currentPosition = hits
                .indexOfFirst { it.pageNumber - 1 >= autoJumpAnchorPage }
                .takeIf { it >= 0 }
                ?: 0
            historyManager.recordJump(ReaderHistoryManager.Origin.SEARCH, hits[currentPosition].pageNumber - 1)
            showCurrentHit()
            return
        }
        pendingAutoJump = true
        subscribedToActiveSearch = true
        showSnackbar()
        updateControls()
        SearchCoordinator.startOrSubscribe(
            activity,
            pdf.uri?.toString(),
            pdf.password,
            pdf.fileHash,
            query,
            ignoreAccents,
            activeSearchListener,
        )
    }

    private fun maybeAutoJump(finished: Boolean) {
        val position = hits.indexOfFirst { it.pageNumber - 1 >= autoJumpAnchorPage }
        if (position >= 0) {
            pendingAutoJump = false
            currentPosition = position
            historyManager.recordJump(ReaderHistoryManager.Origin.SEARCH, hits[position].pageNumber - 1)
            showCurrentHit()
            return
        }
        if (!finished) {
            return
        }
        pendingAutoJump = false
        if (hits.isEmpty()) {
            enterNoResultsState()
            return
        }
        currentPosition = 0
        historyManager.recordJump(ReaderHistoryManager.Origin.SEARCH, hits[0].pageNumber - 1)
        showCurrentHit()
    }

    private fun unsubscribeFromActiveSearch() {
        pendingAutoJump = false
        subscribedToActiveSearch = false
        SearchCoordinator.unsubscribe(activeSearchListener)
    }

    fun clearHighlight() {
        activeHighlightPageNumber?.let { pageNumber ->
            binding.pdfView.clearSearchResultsHighlight(pageNumber)
            activeHighlightPageNumber = null
        }
    }

    fun reset() {
        unsubscribeFromActiveSearch()
        showHitJob?.cancel()
        showHitJob = null
        rawTextCachePage = -1
        rawTextCache = null
        clearHighlight()
        dismissSnackbar()
        hits = emptyList()
        currentPosition = -1
        hasFullSession = false
    }

    fun resetAndReload() {
        if (!isActive) {
            return
        }
        reset()
    }

    fun onActivityDestroyed() {
        unsubscribeFromActiveSearch()
        dismissSnackbar()
    }

    private fun dismissSnackbar() {
        cancelPendingLabelRestore()
        snackbar?.dismiss()
        snackbar = null
        counterView = null
        messageView = null
        previousButton = null
        nextButton = null
    }

    private fun showSnackbar() {
        if (snackbar != null) {
            return
        }
        val bar = AppSnackbar.make(binding.root, activity.getString(R.string.results), Snackbar.LENGTH_INDEFINITE)
        bar.setAction(activity.getString(R.string.done)) {
            SearchCoordinator.cancel(pdf.fileHash, query, ignoreAccents)
            unsubscribeFromActiveSearch()
            clearHighlight()
        }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (snackbar === transientBottomBar) {
                    unsubscribeFromActiveSearch()
                    clearHighlight()
                    cancelPendingLabelRestore()
                    snackbar = null
                    counterView = null
                    messageView = null
                    previousButton = null
                    nextButton = null
                }
            }
        })
        val textView = bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.paintFlags = textView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        textView.setOnClickListener { openResultsList() }
        messageView = textView
        val density = activity.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val highlight = ColorStateList.valueOf(
            MaterialColors.getColor(binding.root, android.R.attr.colorControlHighlight)
        )

        fun borderlessRipple(cornerSize: Float): RippleDrawable {
            val shape = ShapeAppearanceModel.builder().setAllCornerSizes(cornerSize).build()
            return RippleDrawable(highlight, null, MaterialShapeDrawable(shape))
        }

        (textView.parent as? LinearLayout)?.let { content ->
            val buttonSize = (40 * density).toInt()

            fun navigationButton(
                iconRes: Int,
                descriptionRes: Int,
                marginStartDp: Int,
                marginEndDp: Int,
                onClick: () -> Unit,
            ): ImageButton {
                return ImageButton(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = (marginStartDp * density).toInt()
                        marginEnd = (marginEndDp * density).toInt()
                    }
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(onSurface)
                    background = borderlessRipple(buttonSize / 2f)
                    contentDescription = activity.getString(descriptionRes)
                    setOnClickListener { onClick() }
                }
            }

            val previous = navigationButton(R.drawable.ic_chevron_left, R.string.previous_search_result, 12, 10, ::showPrevious)
            val counter = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = (4 * density).toInt()
                    marginEnd = (4 * density).toInt()
                }
                minWidth = (48 * density).toInt()
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                gravity = Gravity.CENTER
                setTextColor(onSurface)
                textSize = 14f
            }
            val next = navigationButton(R.drawable.ic_chevron_right, R.string.next_search_result, 10, 12, ::showNext)

            val insertIndex = content.indexOfChild(textView) + 1
            content.addView(previous, insertIndex)
            content.addView(counter, insertIndex + 1)
            content.addView(next, insertIndex + 2)
            previousButton = previous
            counterView = counter
            nextButton = next
        }
        snackbar = bar
        bar.show()
    }

    private fun showPrevious() {
        if (hits.isEmpty()) {
            return
        }
        if (pendingAutoJump) {
            pendingAutoJump = false
            currentPosition = hits.lastIndex
        }
        else if (hits.size < 2) {
            return
        }
        else {
            currentPosition = if (currentPosition > 0) currentPosition - 1 else hits.lastIndex
        }
        if (BuildConfig.DEBUG) {
            SearchTestHooks.noteNavigationAction()
        }
        showCurrentHit()
    }

    private fun showNext() {
        if (hits.isEmpty()) {
            return
        }
        if (pendingAutoJump) {
            pendingAutoJump = false
            currentPosition = 0
        }
        else if (hits.size < 2) {
            return
        }
        else {
            currentPosition = if (currentPosition < hits.lastIndex) currentPosition + 1 else 0
        }
        if (BuildConfig.DEBUG) {
            SearchTestHooks.noteNavigationAction()
        }
        showCurrentHit()
    }

    private fun showCurrentHit() {
        cancelPendingLabelRestore()
        val hit = hits.getOrNull(currentPosition) ?: return
        showHitJob?.cancel()
        showHitJob = scope.launch {
            val cached = if (rawTextCachePage == hit.pageNumber) rawTextCache else null
            val rawText = cached ?: withContext(Dispatchers.IO) {
                binding.pdfView.getPageRawText(hit.pageNumber)
            }
            if (hits.getOrNull(currentPosition) !== hit) {
                return@launch
            }
            rawTextCachePage = hit.pageNumber
            rawTextCache = rawText
            applyCurrentHit(hit, rawText)
        }
    }

    private fun applyCurrentHit(hit: SearchSessionCache.Hit, rawText: String) {
        clearHighlight()
        val matchLength = if (hit.matchLength > 0) hit.matchLength else query.length
        val rawRange = NormalizedTextMapper.toRawRange(rawText, hit.originalIndex, matchLength)
        val computedBounds = if (rawRange == null) {
            emptyArray<Rect>()
        } else {
            binding.pdfView.createHighlightText(
                hit.pageNumber, rawRange.first, rawRange.last + 1 - rawRange.first, true)
        }
        val textBounds = if (BuildConfig.DEBUG && SearchTestHooks.consumeSearchHighlightFailure()) {
            emptyArray()
        } else {
            computedBounds
        }
        if (textBounds.isEmpty()) {
            showFailedToHighlightMessage()
            binding.pdfView.jumpUsingPageNumber(hit.pageNumber)
        }
        else {
            activeHighlightPageNumber = hit.pageNumber
            val targetZoom = if (pref.getSearchZoomToResult()) {
                max(binding.pdfView.zoom, binding.pdfView.midZoom).coerceAtMost(binding.pdfView.maxZoom)
            } else {
                binding.pdfView.zoom
            }
            val screenRect =
                binding.pdfView.focusOnPdfRect(hit.pageNumber - 1, unionPdfRect(textBounds), targetZoom)
            if (screenRect == null) {
                binding.pdfView.jumpUsingPageNumber(hit.pageNumber)
            }
            positionSnackbar(screenRect)
        }
        updateControls()
    }

    private fun showFailedToHighlightMessage() {
        val bar = snackbar
        val message = messageView
        if (bar == null || message == null) {
            AppSnackbar.make(binding.root, R.string.failed_to_highlight_search_result, Snackbar.LENGTH_SHORT).show()
            return
        }
        message.text = activity.getString(R.string.failed_to_highlight_search_result)
        val restore = Runnable {
            pendingLabelRestore = null
            messageView?.text = activity.getString(R.string.results)
        }
        pendingLabelRestore = restore
        bar.view.postDelayed(restore, FAILED_HIGHLIGHT_LABEL_MILLIS)
    }

    private fun cancelPendingLabelRestore() {
        val restore = pendingLabelRestore ?: return
        pendingLabelRestore = null
        snackbar?.view?.removeCallbacks(restore)
        messageView?.text = activity.getString(R.string.results)
    }

    private fun unionPdfRect(bounds: Array<Rect>): RectF {
        return RectF(
            bounds.minOf { it.left }.toFloat(),
            bounds.maxOf { it.top }.toFloat(),
            bounds.maxOf { it.right }.toFloat(),
            bounds.minOf { it.bottom }.toFloat(),
        )
    }

    private fun positionSnackbar(matchScreenRect: RectF?) {
        val bar = snackbar ?: return
        val params = bar.view.layoutParams as? FrameLayout.LayoutParams ?: return
        val viewHeight = binding.pdfView.height
        val nearBottom = matchScreenRect != null && viewHeight > 0
                && matchScreenRect.centerY() > viewHeight * BOTTOM_AREA_START
        val gravity = if (nearBottom) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        if (params.gravity != gravity) {
            params.gravity = gravity
            bar.view.layoutParams = params
        }
    }

    private fun updateControls() {
        val controlsVisible = (hasFullSession || subscribedToActiveSearch) && hits.isNotEmpty()
        val visibility = if (controlsVisible) View.VISIBLE else View.GONE
        previousButton?.visibility = visibility
        counterView?.visibility = visibility
        nextButton?.visibility = visibility
        if (!controlsVisible) {
            return
        }
        counterView?.text = activity.getString(R.string.search_result_counter, currentPosition + 1, hits.size)
        setButtonEnabled(previousButton, hits.size > 1)
        setButtonEnabled(nextButton, hits.size > 1)
    }

    private fun setButtonEnabled(button: ImageButton?, enabled: Boolean) {
        button?.isEnabled = enabled
        button?.alpha = if (enabled) 1f else 0.35f
    }

    private fun enterNoResultsState() {
        val label = messageView ?: return
        label.text = activity.getString(R.string.search_no_results)
        label.paintFlags = label.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        label.setOnClickListener(null)
        label.isClickable = false
    }

    private fun openResultsList() {
        pendingAutoJump = false
        Intent(activity, SearchActivity::class.java).also { searchIntent ->
            searchIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
            searchIntent.putExtra(PDF.passwordKey, pdf.password)
            searchIntent.putExtra(PDF.incognitoKey, isIncognito())
            pdf.fileHash?.let { searchIntent.putExtra(PDF.fileHashKey, it) }
            if (query.isNotBlank()) {
                searchIntent.putExtra(PDF.searchQueryKey, query)
            }
            hits.getOrNull(currentPosition)?.let { hit ->
                searchIntent.putExtra(PDF.resultPositionInListKey, hit.resultIndex)
            }
            searchIntent.grantPdfReadAccess(pdf.uri.toString())
            launchSearch(searchIntent)
        }
    }

    companion object {
        private const val BOTTOM_AREA_START = 0.6f
        private const val FAILED_HIGHLIGHT_LABEL_MILLIS = 1600L
    }
}
