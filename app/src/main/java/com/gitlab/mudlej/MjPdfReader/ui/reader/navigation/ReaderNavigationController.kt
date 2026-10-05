// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.link.LinkHandler
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.openDocumentLink
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import kotlinx.coroutines.CoroutineScope
import com.gitlab.mudlej.MjPdfReader.pdf.SearchResult
import com.gitlab.mudlej.MjPdfReader.pdf.grantPdfReadAccess
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.userbookmarks.UserBookmarksActivity
import com.gitlab.mudlej.MjPdfReader.ui.userhighlights.UserHighlightsActivity
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.UserNotesActivity
import com.gitlab.mudlej.MjPdfReader.ui.history.NavigationHistoryActivity
import com.gitlab.mudlej.MjPdfReader.ui.gotopage.GoToPageActivity
import com.gitlab.mudlej.MjPdfReader.ui.links.LinksActivity
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsActivity
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.max

class ReaderNavigationController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val pdf: DocumentState,
    private val pref: Preferences,
    private val isIncognito: () -> Boolean,
    private val historyManager: ReaderHistoryManager,
    private val scope: CoroutineScope,
    private val onPageDisplayed: (Int) -> Unit,
    private val updateAppTitle: () -> Unit,
    private val launchTableOfContents: (Intent) -> Unit,
    private val launchUserBookmarks: (Intent) -> Unit,
    private val launchUserNotes: (Intent) -> Unit,
    private val launchUserHighlights: (Intent) -> Unit,
    private val launchNavigationHistory: (Intent) -> Unit,
    private val launchLinks: (Intent) -> Unit,
    private val launchSearch: (Intent) -> Unit,
    private val launchGoToPageGrid: (Intent) -> Unit,
) {

    private val searchNavigationController =
        SearchNavigationController(activity, binding, pdf, pref, isIncognito, historyManager, scope, launchSearch)
    private val tableOfContentsSnackbar = JumpBackSnackbar(binding.root)
    private val linkJumpSnackbar = JumpBackSnackbar(binding.root)
    private var tableOfContentsState = TableOfContentsState()

    fun createLinkHandler(): LinkHandler = BackTrackingLinkHandler()

    fun showLinks() {
        Intent(activity, LinksActivity::class.java).also { linksIntent ->
            linksIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
            linksIntent.putExtra(PDF.passwordKey, pdf.password)
            linksIntent.putExtra(PDF.incognitoKey, isIncognito())
            linksIntent.grantPdfReadAccess(pdf.uri.toString())
            launchLinks(linksIntent)
        }
    }

    fun showTableOfContents() {
        Intent(activity, TableOfContentsActivity::class.java).also { tocIntent ->
            tocIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
            tocIntent.putExtra(PDF.passwordKey, pdf.password)
            tocIntent.putExtra(PDF.pageNumberKey, pdf.pageNumber)
            tocIntent.putExtra(PDF.incognitoKey, isIncognito())
            tableOfContentsState.putInto(tocIntent)
            tocIntent.grantPdfReadAccess(pdf.uri.toString())
            launchTableOfContents(tocIntent)
        }
    }

    fun showGoToPageGrid() {
        Intent(activity, GoToPageActivity::class.java).also { gridIntent ->
            gridIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
            gridIntent.putExtra(PDF.passwordKey, pdf.password)
            gridIntent.putExtra(PDF.pageNumberKey, pdf.pageNumber)
            gridIntent.putExtra(PDF.incognitoKey, isIncognito())
            gridIntent.grantPdfReadAccess(pdf.uri.toString())
            launchGoToPageGrid(gridIntent)
        }
    }

    fun showUserBookmarks() {
        Intent(activity, UserBookmarksActivity::class.java).also { bookmarksIntent ->
            pdf.fileHash?.let { bookmarksIntent.putExtra(PDF.fileHashKey, it) }
            bookmarksIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
            bookmarksIntent.putExtra(PDF.passwordKey, pdf.password)
            bookmarksIntent.putExtra(PDF.incognitoKey, isIncognito())
            bookmarksIntent.grantPdfReadAccess(pdf.uri.toString())
            launchUserBookmarks(bookmarksIntent)
        }
    }

    fun showUserNotes() {
        Intent(activity, UserNotesActivity::class.java).also { notesIntent ->
            notesIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
            notesIntent.putExtra(PDF.passwordKey, pdf.password)
            notesIntent.putExtra(PDF.nameKey, pdf.name)
            notesIntent.putExtra(PDF.incognitoKey, isIncognito())
            notesIntent.grantPdfReadAccess(pdf.uri.toString())
            launchUserNotes(notesIntent)
        }
    }

    fun showUserHighlights() {
        Intent(activity, UserHighlightsActivity::class.java).also { highlightsIntent ->
            highlightsIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
            highlightsIntent.putExtra(PDF.passwordKey, pdf.password)
            highlightsIntent.putExtra(PDF.nameKey, pdf.name)
            highlightsIntent.putExtra(PDF.incognitoKey, isIncognito())
            highlightsIntent.grantPdfReadAccess(pdf.uri.toString())
            launchUserHighlights(highlightsIntent)
        }
    }

    fun showNavigationHistory() {
        if (!historyManager.hasTrail()) {
            return
        }
        val historyIntent = NavigationHistoryActivity.createIntent(
            activity,
            historyManager.backEntries(),
            historyManager.forwardEntries(),
            binding.pdfView.currentPage,
        )
        historyIntent.putExtra(PDF.filePathKey, pdf.uri.toString())
        historyIntent.putExtra(PDF.passwordKey, pdf.password)
        historyIntent.putExtra(PDF.incognitoKey, isIncognito())
        historyIntent.grantPdfReadAccess(pdf.uri.toString())
        launchNavigationHistory(historyIntent)
    }

    fun onPageChanged(pageIndex: Int) {
        historyManager.onPageChanged(pageIndex)
        onPageDisplayed(pageIndex)
    }

    fun onFileHashComputed() {
        onPageDisplayed(pdf.pageNumber)
    }

    fun clearActiveSearchResultHighlight() {
        searchNavigationController.clearHighlight()
    }

    fun resetSearchResultState() {
        searchNavigationController.reset()
    }

    fun onActivityDestroyed() {
        searchNavigationController.onActivityDestroyed()
    }

    fun startInlineSearch(query: String, ignoreAccents: Boolean) {
        searchNavigationController.startQuery(query, ignoreAccents)
    }

    fun resetTableOfContentsState() {
        tableOfContentsSnackbar.dismiss()
        tableOfContentsState = TableOfContentsState()
    }

    fun resetLinkJumpState() {
        linkJumpSnackbar.dismiss()
    }

    fun saveState(outState: Bundle) {
        tableOfContentsState.putInto(outState)
    }

    fun restoreState(savedState: Bundle) {
        tableOfContentsState = TableOfContentsState.from(savedState)
    }

    fun handleTableOfContentsResult(resultCode: Int, intent: Intent?) {
        saveTableOfContentsState(intent)
        if (resultCode == PDF.TABLE_OF_CONTENTS_RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.chosenTableOfContentsEntryKey, pdf.pageNumber) ?: return
            historyManager.recordJump(ReaderHistoryManager.Origin.TOC, pageIndex)
            binding.pdfView.jumpTo(pageIndex)
            showTableOfContentsJumpBackSnackbar()
        }
    }

    fun handleUserBookmarksResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.TABLE_OF_CONTENTS_RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.chosenTableOfContentsEntryKey, pdf.pageNumber) ?: return
            historyManager.recordJump(ReaderHistoryManager.Origin.BOOKMARK, pageIndex)
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleUserNotesResult(resultCode: Int, intent: Intent?) {
        handleAnnotationListResult(resultCode, intent)
    }

    fun handleUserHighlightsResult(resultCode: Int, intent: Intent?) {
        handleAnnotationListResult(resultCode, intent)
    }

    private fun handleAnnotationListResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.TABLE_OF_CONTENTS_RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.chosenTableOfContentsEntryKey, pdf.pageNumber) ?: return
            historyManager.recordJump(ReaderHistoryManager.Origin.BOOKMARK, pageIndex)
            @Suppress("DEPRECATION")
            focusOnHighlight(
                pageIndex,
                intent.getStringExtra(PDF.chosenHighlightGroupKey),
                intent.getIntExtra(PDF.chosenHighlightAnnotationIndexKey, -1),
                intent.getParcelableExtra(PDF.chosenHighlightBoundsKey),
            )
        }
    }

    private fun focusOnHighlight(
        pageIndex: Int,
        groupKey: String?,
        annotationIndex: Int,
        knownBounds: RectF? = null,
    ) {
        val bounds = knownBounds
            ?: binding.pdfView.findHighlightPdfBounds(pageIndex, groupKey, annotationIndex)
        if (bounds == null) {
            binding.pdfView.jumpTo(pageIndex)
            return
        }
        val targetZoom = max(binding.pdfView.zoom, binding.pdfView.midZoom)
            .coerceAtMost(binding.pdfView.maxZoom)
        if (binding.pdfView.focusOnPdfRect(pageIndex, bounds, targetZoom) == null) {
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleNavigationHistoryResult(resultCode: Int, intent: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val data = intent ?: return
            val forwardStackIndex = data.getIntExtra(NavigationHistoryActivity.EXTRA_SELECTED_FORWARD_STACK_INDEX, -1)
            if (forwardStackIndex >= 0) {
                historyManager.goForwardToForwardStackIndex(forwardStackIndex)
                return
            }
            val backStackIndex = data.getIntExtra(NavigationHistoryActivity.EXTRA_SELECTED_BACK_STACK_INDEX, -1)
            if (backStackIndex >= 0) {
                historyManager.goBackToBackStackIndex(backStackIndex)
            }
        }
    }

    fun handleTextModeResult(resultCode: Int, intent: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.pageNumberKey, pdf.pageNumber) ?: return
            val pageCount = binding.pdfView.pageCount
            val boundedPageIndex = if (pageCount > 0) pageIndex.coerceIn(0, pageCount - 1) else pageIndex.coerceAtLeast(0)
            historyManager.recordJump(ReaderHistoryManager.Origin.TEXT_MODE, boundedPageIndex)
            binding.pdfView.jumpTo(boundedPageIndex)
        }
    }

    fun handleGoToPageGridResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.GO_TO_PAGE_RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.chosenPageIndexKey, pdf.pageNumber) ?: return
            historyManager.recordJump(ReaderHistoryManager.Origin.GO_TO, pageIndex)
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleLinksResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.LINK_RESULT_OK) {
            val pageNumber = intent?.getIntExtra(PDF.linkResultKey, pdf.pageNumber) ?: return
            val pageIndex = pageNumber - 1
            historyManager.recordJump(ReaderHistoryManager.Origin.LINK, pageIndex)
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleSearchResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.SEARCH_RESULT_OK) {
            val searchResultJson = intent?.getStringExtra(PDF.searchResultKey) ?: return
            val searchResultType = object : TypeToken<SearchResult>() {}.type
            val searchResult = Gson().fromJson<SearchResult>(searchResultJson, searchResultType)

            searchNavigationController.start(
                searchResult,
                intent.getStringExtra(PDF.searchQueryResultKey),
                intent.getBooleanExtra(PDF.searchIgnoreAccentsKey, false),
            )
        }
        else {
            searchNavigationController.resetAndReload()
        }
    }

    private fun saveTableOfContentsState(intent: Intent?) {
        if (intent == null) return

        tableOfContentsState = TableOfContentsState.from(intent)
    }

    private fun showTableOfContentsJumpBackSnackbar() {
        resetSearchResultState()
        linkJumpSnackbar.dismiss()
        tableOfContentsSnackbar.show(activity.getString(R.string.back_to_table_of_contents)) {
            showTableOfContents()
        }
    }

    private fun showLinkJumpBackSnackbar(originPageIndex: Int, originViewState: PDFView.ViewState?) {
        resetSearchResultState()
        tableOfContentsSnackbar.dismiss()
        linkJumpSnackbar.show(activity.getString(R.string.back_to_page, originPageIndex + 1)) {
            if (!binding.pdfView.applyViewState(originViewState)) {
                binding.pdfView.jumpTo(originPageIndex)
            }
        }
    }

    private inner class BackTrackingLinkHandler : LinkHandler {
        override fun handleLinkEvent(event: LinkTapEvent) {
            val destPageIndex = event.link.destPageIdx
            if (event.link.uri.isNullOrEmpty() && destPageIndex != null) {
                val originPageIndex = binding.pdfView.currentPage
                val originViewState = binding.pdfView.captureViewState()
                historyManager.recordJump(ReaderHistoryManager.Origin.LINK, destPageIndex)
                binding.pdfView.jumpTo(destPageIndex)
                showLinkJumpBackSnackbar(originPageIndex, originViewState)
            } else {
                event.link.uri?.takeIf { it.isNotEmpty() }?.let {
                    openDocumentLink(binding.root, it)
                }
            }
        }
    }
}
