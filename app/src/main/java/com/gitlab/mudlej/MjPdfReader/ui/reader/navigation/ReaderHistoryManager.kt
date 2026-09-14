// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R

class ReaderHistoryManager(
    private val pdfView: () -> PDFView,
    private val onChanged: () -> Unit,
) {

    enum class Origin(@StringRes val labelRes: Int) {
        LINK(R.string.history_origin_link),
        TOC(R.string.table_of_contents),
        GO_TO(R.string.go_to_page),
        SEARCH(R.string.search),
        BOOKMARK(R.string.history_origin_bookmark),
        TEXT_MODE(R.string.text_mode),
        DWELL(R.string.history_origin_dwell),
        HISTORY(R.string.history_origin_visited),
    }

    data class Entry(
        val pageIndex: Int,
        val viewState: PDFView.ViewState?,
        val timestamp: Long,
        val origin: Origin,
    )

    private val backStack = ArrayDeque<Entry>()
    private val forwardStack = ArrayDeque<Entry>()
    private var navigatingInternally = false
    private var suppressDwellOnce = false
    private var currentPageIndex = -1
    private var arrivedAtElapsed = 0L

    private val debugDwellThresholdMs: Long by lazy {
        val preferences = PreferenceManager.getDefaultSharedPreferences(pdfView().context)
        runCatching { preferences.getLong(debugDwellThresholdKey, DWELL_THRESHOLD_MS) }
            .getOrDefault(DWELL_THRESHOLD_MS)
    }

    private fun dwellThresholdMs(): Long {
        return if (BuildConfig.DEBUG) debugDwellThresholdMs else DWELL_THRESHOLD_MS
    }

    fun elapsedDwellMs(): Long {
        if (currentPageIndex < 0) {
            return 0L
        }
        return SystemClock.elapsedRealtime() - arrivedAtElapsed
    }

    fun canGoBack() = backStack.isNotEmpty()

    fun canGoForward() = forwardStack.isNotEmpty()

    fun hasTrail() = canGoBack() || canGoForward()

    fun backEntries(): List<Entry> = backStack.toList()

    fun forwardEntries(): List<Entry> = forwardStack.toList()

    fun recordJump(origin: Origin, targetPageIndex: Int? = null) {
        val view = pdfView()
        if (targetPageIndex != null && targetPageIndex == view.currentPage) {
            return
        }
        targetPageIndex?.let { removePage(backStack, it) }
        pushBack(currentEntry(origin))
        forwardStack.clear()
        suppressDwellOnce = true
        onChanged()
    }

    fun goBack() {
        val entry = backStack.removeLastOrNull() ?: return
        pushForward(currentEntry(Origin.HISTORY))
        navigate(entry)
    }

    fun goForward() {
        val entry = forwardStack.removeLastOrNull() ?: return
        pushBack(currentEntry(Origin.HISTORY))
        navigate(entry)
    }

    fun goBackTo(entry: Entry) {
        if (backStack.none { it === entry }) {
            return
        }
        pushForward(currentEntry(Origin.HISTORY))
        while (backStack.isNotEmpty()) {
            val top = backStack.removeLast()
            if (top === entry) {
                navigate(top)
                return
            }
            pushForward(top)
        }
    }

    fun goBackToBackStackIndex(index: Int): Boolean {
        val entry = backStack.toList().getOrNull(index) ?: return false
        goBackTo(entry)
        return true
    }

    fun goForwardTo(entry: Entry) {
        if (forwardStack.none { it === entry }) {
            return
        }
        pushBack(currentEntry(Origin.HISTORY))
        while (forwardStack.isNotEmpty()) {
            val top = forwardStack.removeLast()
            if (top === entry) {
                navigate(top)
                return
            }
            pushBack(top)
        }
    }

    fun goForwardToForwardStackIndex(index: Int): Boolean {
        val entry = forwardStack.toList().getOrNull(index) ?: return false
        goForwardTo(entry)
        return true
    }

    fun onPageChanged(pageIndex: Int) {
        val now = SystemClock.elapsedRealtime()
        val leftPage = currentPageIndex
        val dwellMs = now - arrivedAtElapsed
        val skipDwell = navigatingInternally || suppressDwellOnce
        navigatingInternally = false
        suppressDwellOnce = false
        currentPageIndex = pageIndex
        arrivedAtElapsed = now
        if (leftPage < 0 || leftPage == pageIndex || skipDwell) {
            return
        }
        if (dwellMs < dwellThresholdMs()) {
            return
        }
        if (backStack.lastOrNull()?.pageIndex == leftPage) {
            return
        }
        pushBack(Entry(leftPage, null, System.currentTimeMillis(), Origin.DWELL))
        forwardStack.clear()
        onChanged()
    }

    fun clear() {
        backStack.clear()
        forwardStack.clear()
        navigatingInternally = false
        suppressDwellOnce = false
        currentPageIndex = -1
        arrivedAtElapsed = 0L
        onChanged()
    }

    fun saveState(outState: Bundle) {
        saveStack(outState, backStack, backPagesKey, backOriginsKey, backTimesKey)
        saveStack(outState, forwardStack, forwardPagesKey, forwardOriginsKey, forwardTimesKey)
    }

    fun restoreState(savedState: Bundle) {
        restoreStack(savedState, backStack, backPagesKey, backOriginsKey, backTimesKey)
        restoreStack(savedState, forwardStack, forwardPagesKey, forwardOriginsKey, forwardTimesKey)
        onChanged()
    }

    private fun currentEntry(origin: Origin): Entry {
        val view = pdfView()
        return Entry(view.currentPage, view.captureViewState(), System.currentTimeMillis(), origin)
    }

    private fun navigate(entry: Entry) {
        removePage(backStack, entry.pageIndex)
        removePage(forwardStack, entry.pageIndex)
        val view = pdfView()
        navigatingInternally = entry.pageIndex != view.currentPage
        if (!view.applyViewState(entry.viewState)) {
            view.jumpTo(entry.pageIndex)
        }
        onChanged()
    }

    private fun pushBack(entry: Entry) {
        pushUnique(backStack, entry)
    }

    private fun pushForward(entry: Entry) {
        pushUnique(forwardStack, entry)
    }

    private fun pushUnique(stack: ArrayDeque<Entry>, entry: Entry) {
        removePage(stack, entry.pageIndex)
        stack.addLast(entry)
        while (stack.size > MAX_ENTRIES) {
            stack.removeFirst()
        }
    }

    private fun removePage(stack: ArrayDeque<Entry>, pageIndex: Int) {
        val retained = stack.filterNot { it.pageIndex == pageIndex }
        if (retained.size == stack.size) {
            return
        }
        stack.clear()
        stack.addAll(retained)
    }

    private fun saveStack(
        outState: Bundle,
        stack: ArrayDeque<Entry>,
        pagesKey: String,
        originsKey: String,
        timesKey: String,
    ) {
        outState.putIntArray(pagesKey, stack.map { it.pageIndex }.toIntArray())
        outState.putStringArray(originsKey, stack.map { it.origin.name }.toTypedArray())
        outState.putLongArray(timesKey, stack.map { it.timestamp }.toLongArray())
    }

    private fun restoreStack(
        savedState: Bundle,
        stack: ArrayDeque<Entry>,
        pagesKey: String,
        originsKey: String,
        timesKey: String,
    ) {
        val pages = savedState.getIntArray(pagesKey) ?: return
        val origins = savedState.getStringArray(originsKey) ?: return
        val times = savedState.getLongArray(timesKey) ?: return
        if (pages.size != origins.size || pages.size != times.size) {
            return
        }
        stack.clear()
        pages.indices.forEach { index ->
            val origin = Origin.entries.firstOrNull { it.name == origins[index] } ?: Origin.HISTORY
            pushUnique(stack, Entry(pages[index], null, times[index], origin))
        }
    }

    companion object {
        private const val DWELL_THRESHOLD_MS = 30_000L
        private const val MAX_ENTRIES = 50
        const val debugDwellThresholdKey = "debugDwellThresholdMs"
        private const val backPagesKey = "readerHistoryBackPages"
        private const val backOriginsKey = "readerHistoryBackOrigins"
        private const val backTimesKey = "readerHistoryBackTimes"
        private const val forwardPagesKey = "readerHistoryForwardPages"
        private const val forwardOriginsKey = "readerHistoryForwardOrigins"
        private const val forwardTimesKey = "readerHistoryForwardTimes"
    }
}
