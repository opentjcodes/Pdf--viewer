// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.search

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.SearchResult
import com.gitlab.mudlej.MjPdfReader.pdf.PdfExtractor
import com.gitlab.mudlej.MjPdfReader.core.text.accentInsensitiveRanges
import com.gitlab.mudlej.MjPdfReader.core.debug.SearchTestHooks
import com.gitlab.mudlej.MjPdfReader.pdf.createPdfExtractor
import com.gitlab.mudlej.MjPdfReader.core.text.indexesOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

object SearchCoordinator {

    private const val SEARCH_BATCH_PAGES = 20

    data class Key(val fileHash: String?, val query: String, val ignoreAccents: Boolean)

    interface Listener {
        fun onProgress(pagesScanned: Int, pageCount: Int)
        fun onResults(results: List<SearchResult>, finished: Boolean)
    }

    private class ActiveSearch(val key: Key) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listeners = CopyOnWriteArrayList<Listener>()
        @Volatile var snapshot: List<SearchResult> = emptyList()
        @Volatile var finished = false
        @Volatile var pagesScanned = 0
        @Volatile var pageCount = 0
    }

    data class DebugProgress(
        val fileHash: String?,
        val query: String,
        val ignoreAccents: Boolean,
        val pagesScanned: Int,
        val pageCount: Int,
        val finished: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var active: ActiveSearch? = null

    @Volatile
    private var lastFinished: DebugProgress? = null

    @Synchronized
    fun debugProgress(): DebugProgress? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        val current = active ?: return lastFinished
        return debugSnapshotOf(current)
    }

    @Synchronized
    fun clearDebugProgress() {
        if (!BuildConfig.DEBUG) {
            return
        }
        lastFinished = null
    }

    private fun debugSnapshotOf(search: ActiveSearch) = DebugProgress(
        fileHash = search.key.fileHash,
        query = search.key.query,
        ignoreAccents = search.key.ignoreAccents,
        pagesScanned = search.pagesScanned,
        pageCount = search.pageCount,
        finished = search.finished,
    )

    @Synchronized
    fun startOrSubscribe(
        activity: Activity,
        pdfPath: String?,
        password: String?,
        fileHash: String?,
        query: String,
        ignoreAccents: Boolean,
        listener: Listener,
    ) {
        val key = Key(fileHash, query.trim(), ignoreAccents)
        val current = active
        if (current != null && current.key == key && !current.finished) {
            attachTo(current, listener)
            return
        }
        current?.scope?.cancel()
        current?.listeners?.clear()
        val search = ActiveSearch(key)
        search.listeners.add(listener)
        active = search
        if (BuildConfig.DEBUG) {
            SearchTestHooks.noteSearchStarted()
            lastFinished = null
        }
        val appContext = activity.applicationContext
        search.scope.launch { runSearch(search, appContext, pdfPath, password) }
    }

    @Synchronized
    fun subscribeIfRunning(fileHash: String?, query: String, ignoreAccents: Boolean, listener: Listener): Boolean {
        val current = active ?: return false
        if (current.key != Key(fileHash, query.trim(), ignoreAccents) || current.finished) {
            return false
        }
        attachTo(current, listener)
        return true
    }

    @Synchronized
    fun unsubscribe(listener: Listener) {
        active?.listeners?.remove(listener)
    }

    @Synchronized
    fun cancel(fileHash: String?, query: String, ignoreAccents: Boolean) {
        val current = active ?: return
        if (current.key != Key(fileHash, query.trim(), ignoreAccents)) {
            return
        }
        current.scope.cancel()
        current.listeners.clear()
        active = null
        if (BuildConfig.DEBUG) {
            lastFinished = null
        }
    }

    private fun attachTo(search: ActiveSearch, listener: Listener) {
        search.listeners.add(listener)
        val results = search.snapshot
        val finished = search.finished
        val pagesScanned = search.pagesScanned
        val pageCount = search.pageCount
        mainHandler.post {
            if (pageCount > 0) {
                listener.onProgress(pagesScanned, pageCount)
            }
            listener.onResults(results, finished)
        }
    }

    private suspend fun runSearch(search: ActiveSearch, context: Context, pdfPath: String?, password: String?) {
        var extractor: PdfExtractor? = null
        try {
            extractor = createPdfExtractor(context, Uri.parse(pdfPath), password)
            val pageCount = extractor.getPageCount()
            search.pageCount = pageCount
            val results = mutableListOf<SearchResult>()
            var lastPublishedCount = 0
            for (pageNumber in 1..pageCount) {
                yield()
                val pageText = extractor.getPageText(pageNumber)
                if (pageText.isNotBlank()) {
                    matchRanges(pageText, search.key.query, search.key.ignoreAccents).forEach { range ->
                        results.add(
                            buildSearchResult(
                                search.key.query,
                                range.first,
                                pageText,
                                pageNumber,
                                matchLength = range.last + 1 - range.first,
                            ).apply { searchResultIndexInList = results.size }
                        )
                    }
                }
                search.pagesScanned = pageNumber
                SearchTestHooks.notePageScanned(search.key.fileHash, search.key.query, pageNumber)
                publishProgress(search, pageNumber, pageCount)
                val isBatchBoundary = pageNumber % SEARCH_BATCH_PAGES == 0 || pageNumber == pageCount
                if (isBatchBoundary && results.size > lastPublishedCount) {
                    lastPublishedCount = results.size
                    publishResults(search, results.toList(), finished = false)
                    if (BuildConfig.DEBUG) {
                        SearchTestHooks.blockIfResultBatchRequested()
                    }
                }
            }
            SearchTestHooks.noteSearchCompleted()
            SearchSessionCache.put(search.key.fileHash, search.key.query, search.key.ignoreAccents, cacheHits(results))
            finish(search, results.toList())
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            finish(search, search.snapshot)
        } finally {
            extractor?.let { opened -> runCatching { opened.close() } }
        }
    }

    private fun publishProgress(search: ActiveSearch, pagesScanned: Int, pageCount: Int) {
        mainHandler.post {
            search.listeners.forEach { it.onProgress(pagesScanned, pageCount) }
        }
    }

    private fun publishResults(search: ActiveSearch, results: List<SearchResult>, finished: Boolean) {
        search.snapshot = results
        mainHandler.post {
            search.listeners.forEach { it.onResults(results, finished) }
        }
    }

    private fun finish(search: ActiveSearch, results: List<SearchResult>) {
        search.finished = true
        publishResults(search, results, finished = true)
        mainHandler.post { search.listeners.clear() }
        synchronized(this) {
            if (BuildConfig.DEBUG) {
                lastFinished = debugSnapshotOf(search)
            }
            if (active === search) {
                active = null
            }
        }
    }

    fun matchRanges(text: String, query: String, ignoreAccents: Boolean): List<IntRange> {
        return if (ignoreAccents) {
            text.accentInsensitiveRanges(query)
        } else {
            text.indexesOf(query, ignoreCase = true).map { it until it + query.length }
        }
    }

    fun buildSearchResult(
        query: String,
        indexInPage: Int,
        pageText: String,
        pageNumber: Int,
        textOffset: Int? = null,
        expanded: Boolean = false,
        matchLength: Int = query.length,
    ): SearchResult {
        val offset = textOffset ?: PDF.SEARCH_RESULT_OFFSET
        val count = matchLength

        val starting = max(0, indexInPage - offset)
        val ending = min(pageText.length, indexInPage + count + offset)
        val resultText = pageText.substring(startIndex = starting, endIndex = ending)

        // remove half words (e.g. "er can I found hi" -> "can I found")
        val queryIndex = indexInPage - starting
        val firstSpace = resultText.indexOf(" ") + 1
        val start = if (firstSpace != -1) min(firstSpace, queryIndex) else 0

        val lastSpace = resultText.lastIndexOf(" ")
        val end = if (lastSpace != -1) max(lastSpace, queryIndex + count) else resultText.length

        val trimmedText = resultText.substring(start, end)
        val newStart = queryIndex - start

        return SearchResult(
            originalIndex = indexInPage,
            inputStart = newStart,
            inputEnd = newStart + count,
            text = trimmedText,
            pageNumber = pageNumber,
            expanded = expanded
        )
    }

    fun appendHits(
        previous: List<SearchSessionCache.Hit>,
        results: List<SearchResult>,
    ): List<SearchSessionCache.Hit> {
        if (previous.isEmpty() || results.size < previous.size) {
            return cacheHits(results)
        }
        if (previous.last().resultIndex != results[previous.size - 1].searchResultIndexInList) {
            return cacheHits(results)
        }
        if (results.size == previous.size) {
            return previous
        }
        val appended = ArrayList<SearchSessionCache.Hit>(results.size)
        appended.addAll(previous)
        appended.addAll(cacheHits(results.subList(previous.size, results.size)))
        return appended
    }

    fun cacheHits(results: List<SearchResult>): List<SearchSessionCache.Hit> {
        return results.map { result ->
            SearchSessionCache.Hit(
                pageNumber = result.pageNumber,
                originalIndex = result.originalIndex,
                resultIndex = result.searchResultIndexInList,
                expanded = result.expanded,
                text = result.text,
                inputStart = result.inputStart,
                inputEnd = result.inputEnd,
            )
        }
    }
}
