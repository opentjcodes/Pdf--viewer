// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.text_mode

import android.net.Uri
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.core.debug.WeakRegistry
import com.gitlab.mudlej.MjPdfReader.core.debug.PdfExtractorDebug
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.pdf.PdfExtractor
import com.gitlab.mudlej.MjPdfReader.pdf.createPdfExtractor
import com.shockwave.pdfium.PageTextTooLargeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

class TextModeContentLoader(
    private val activity: AppCompatActivity,
    private val recyclerView: RecyclerView,
    private val joinParagraphs: () -> Boolean,
    private val detectHeadings: () -> Boolean,
    private val detectCodeBlocks: () -> Boolean,
) {

    var pageCount = 0
        private set
    val closing: Boolean
        get() = isClosing

    private lateinit var pdfExtractor: PdfExtractor
    private lateinit var adapter: TextModePageAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var currentPageIndex: () -> Int

    private var pendingExtractor: PdfExtractor? = null

    @Volatile
    private var isClosing = false
    private val extractionMutex = Mutex()
    private val extractionJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private val loadingPages = mutableSetOf<Int>()
    private val loadingJobs = HashMap<Int, Job>()
    private val textCache = LinkedHashMap<Int, CharSequence>(CACHE_PAGE_LIMIT, 0.75f, true)
    private var cacheGeneration = 0
    private val loadVisiblePagesRunnable = Runnable {
        if (::layoutManager.isInitialized) {
            loadVisiblePages()
        }
    }

    init {
        if (BuildConfig.DEBUG) {
            debugInstances?.add(this)
        }
    }

    data class TextCacheStats(val entryCount: Int, val pageKeys: List<Int>)

    @MainThread
    fun debugTextCacheStats(): TextCacheStats? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        return TextCacheStats(
            entryCount = textCache.size,
            pageKeys = textCache.keys.toList(),
        )
    }

    suspend fun open(uri: Uri, password: String?): Boolean {
        val extractor = withContext(Dispatchers.IO) {
            extractionMutex.withLock {
                try {
                    createPdfExtractor(activity, uri, password).also { created ->
                        if (isClosing) {
                            created.close()
                        } else {
                            pendingExtractor = created
                        }
                    }
                } catch (throwable: Throwable) {
                    null
                }
            }
        } ?: return false

        return withContext(Dispatchers.IO) {
            extractionMutex.withLock {
                if (isClosing) {
                    extractor.close()
                    if (pendingExtractor === extractor) {
                        pendingExtractor = null
                    }
                    return@withLock false
                }

                pdfExtractor = extractor
                pendingExtractor = null
                pageCount = pdfExtractor.getPageCount()
                if (BuildConfig.DEBUG) {
                    PdfExtractorDebug.awaitTextModeSetupGate()
                }
                true
            }
        }
    }

    fun attach(
        adapter: TextModePageAdapter,
        layoutManager: LinearLayoutManager,
        currentPageIndex: () -> Int,
    ) {
        this.adapter = adapter
        this.layoutManager = layoutManager
        this.currentPageIndex = currentPageIndex
    }

    fun loadAround(pageIndex: Int) {
        for (index in pageIndex - PREFETCH_DISTANCE..pageIndex + PREFETCH_DISTANCE) {
            loadPage(index)
        }
    }

    fun loadTargetWindow(pageIndex: Int) {
        val window = (pageIndex - PREFETCH_DISTANCE)..(pageIndex + JUMP_LOAD_AHEAD)
        cancelLoadsOutside(window)
        loadPage(pageIndex)
        for (index in pageIndex + 1..window.last) {
            loadPage(index)
        }
        for (index in pageIndex - 1 downTo window.first) {
            loadPage(index)
        }
    }

    private fun cancelLoadsOutside(keep: IntRange) {
        if (loadingJobs.isEmpty()) return
        for ((page, job) in loadingJobs.toList()) {
            if (page !in keep) {
                job.cancel()
            }
        }
    }

    fun loadVisiblePages() {
        val firstVisiblePage = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePage = layoutManager.findLastVisibleItemPosition()
        if (firstVisiblePage == RecyclerView.NO_POSITION || lastVisiblePage == RecyclerView.NO_POSITION) {
            loadAround(currentPageIndex())
            return
        }

        for (index in (firstVisiblePage - PREFETCH_DISTANCE)..(lastVisiblePage + PREFETCH_DISTANCE)) {
            loadPage(index)
        }
    }

    fun scheduleLoadVisiblePages() {
        recyclerView.removeCallbacks(loadVisiblePagesRunnable)
        recyclerView.post(loadVisiblePagesRunnable)
    }

    fun retryPage(pageIndex: Int) {
        loadPage(pageIndex, force = true)
    }

    fun close() {
        isClosing = true
        if (BuildConfig.DEBUG) {
            debugInstances?.remove(this)
        }
        recyclerView.removeCallbacks(loadVisiblePagesRunnable)
        synchronized(extractionJobs) {
            extractionJobs.toList()
        }.forEach { it.cancel() }
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            extractionMutex.withLock {
                pendingExtractor?.close()
                pendingExtractor = null
                if (::pdfExtractor.isInitialized) {
                    pdfExtractor.close()
                }
            }
        }
    }

    private fun loadPage(pageIndex: Int, force: Boolean = false) {
        if (pageIndex !in 0 until pageCount) return

        textCache[pageIndex]?.let { cachedText ->
            val currentState = adapter.pageState(pageIndex)
            if (currentState !is TextModePageState.Ready || currentState.text !== cachedText) {
                updatePageState(TextModePageState.Ready(pageIndex, cachedText))
                scheduleLoadVisiblePages()
            }
            return
        }
        if (loadingPages.contains(pageIndex)) return
        if (!force) {
            when (adapter.pageState(pageIndex)) {
                is TextModePageState.Ready,
                is TextModePageState.Empty,
                is TextModePageState.Error,
                is TextModePageState.TooLarge -> return
                else -> Unit
            }
        }

        loadingPages.add(pageIndex)
        updatePageState(TextModePageState.Loading(pageIndex))
        val useJoinParagraphs = joinParagraphs()
        val useDetectHeadings = detectHeadings()
        val useDetectCodeBlocks = detectCodeBlocks()
        val generation = cacheGeneration
        val job = activity.lifecycleScope.launch(Dispatchers.IO) {
            val state = extractionMutex.withLock {
                val relevant = withContext(Dispatchers.Main) { isPageStillWanted(pageIndex) }
                if (!relevant) {
                    null
                } else {
                    try {
                        val text = extractPageText(pageIndex, useJoinParagraphs, useDetectHeadings, useDetectCodeBlocks)
                        if (text.isBlank()) {
                            TextModePageState.Empty(pageIndex)
                        } else {
                            TextModePageState.Ready(pageIndex, text)
                        }
                    } catch (tooLarge: PageTextTooLargeException) {
                        TextModePageState.TooLarge(pageIndex)
                    } catch (throwable: Throwable) {
                        TextModePageState.Error(pageIndex, throwable.message.orEmpty())
                    }
                }
            }

            withContext(Dispatchers.Main) {
                loadingPages.remove(pageIndex)
                if (state == null) {
                    if (adapter.pageState(pageIndex) is TextModePageState.Loading) {
                        updatePageState(TextModePageState.NotLoaded(pageIndex))
                    }
                    return@withContext
                }
                if (generation != cacheGeneration) {
                    if (adapter.pageState(pageIndex) is TextModePageState.Loading) {
                        updatePageState(TextModePageState.NotLoaded(pageIndex))
                    }
                    if (isPageStillWanted(pageIndex)) {
                        loadPage(pageIndex)
                    }
                    return@withContext
                }
                if (state is TextModePageState.Ready) {
                    cacheText(state.pageIndex, state.text)
                }
                updatePageState(state)
                scheduleLoadVisiblePages()
            }
        }
        extractionJobs.add(job)
        loadingJobs[pageIndex] = job
        job.invokeOnCompletion { cause ->
            extractionJobs.remove(job)
            recyclerView.post {
                if (loadingJobs[pageIndex] === job) {
                    loadingJobs.remove(pageIndex)
                }
                if (cause != null && loadingPages.remove(pageIndex)) {
                    if (adapter.pageState(pageIndex) is TextModePageState.Loading) {
                        updatePageState(TextModePageState.NotLoaded(pageIndex))
                    }
                }
            }
        }
    }

    private fun extractPageText(
        pageIndex: Int,
        joinParagraphs: Boolean,
        detectHeadings: Boolean,
        detectCodeBlocks: Boolean,
    ): CharSequence {
        if (!joinParagraphs && !detectHeadings && !detectCodeBlocks) {
            return TextModeTextFormatter.format(pdfExtractor.getPageTextOrThrow(pageIndex + 1))
        }
        val metrics = pdfExtractor.getPageCharMetrics(pageIndex + 1)
        val structuredText = metrics?.let {
            runCatching { StructuredTextFormatter.format(it, joinParagraphs, detectHeadings, detectCodeBlocks) }.getOrNull()
        }
        if (structuredText != null) return structuredText
        val rawText = pdfExtractor.getPageTextOrThrow(pageIndex + 1)
        return if (joinParagraphs) {
            TextModeTextFormatter.formatJoined(rawText)
        } else {
            TextModeTextFormatter.format(rawText)
        }
    }

    fun invalidateAndReload() {
        if (recyclerView.isComputingLayout) {
            recyclerView.post { invalidateAndReload() }
            return
        }
        cacheGeneration++
        textCache.clear()
        if (!::adapter.isInitialized || !::layoutManager.isInitialized) return

        val firstVisiblePage = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePage = layoutManager.findLastVisibleItemPosition()
        adapter.submitPageCount(pageCount)
        if (firstVisiblePage == RecyclerView.NO_POSITION || lastVisiblePage == RecyclerView.NO_POSITION) {
            loadTargetWindow(currentPageIndex())
        } else {
            for (index in (firstVisiblePage - PREFETCH_DISTANCE)..(lastVisiblePage + PREFETCH_DISTANCE)) {
                loadPage(index)
            }
        }
        recyclerView.doOnNextLayout { loadVisiblePages() }
    }

    private fun isPageStillWanted(pageIndex: Int): Boolean {
        if (isClosing || !::layoutManager.isInitialized) return false

        val nearCurrent = pageIndex in (currentPageIndex() - PREFETCH_DISTANCE)..(currentPageIndex() + JUMP_LOAD_AHEAD)
        if (nearCurrent) return true

        val firstVisiblePage = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePage = layoutManager.findLastVisibleItemPosition()
        if (firstVisiblePage == RecyclerView.NO_POSITION || lastVisiblePage == RecyclerView.NO_POSITION) return true

        return pageIndex in (firstVisiblePage - PREFETCH_DISTANCE)..(lastVisiblePage + PREFETCH_DISTANCE)
    }

    private fun updatePageState(state: TextModePageState) {
        if (recyclerView.isComputingLayout) {
            recyclerView.post { updatePageState(state) }
            return
        }
        if (state is TextModePageState.Loading && !loadingPages.contains(state.pageIndex)) {
            return
        }
        adapter.updatePageState(state)
    }

    private fun cacheText(pageIndex: Int, text: CharSequence) {
        textCache[pageIndex] = text
        if (textCache.size <= CACHE_PAGE_LIMIT) return

        val firstVisiblePage = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePage = layoutManager.findLastVisibleItemPosition()
        val protectedRange = if (firstVisiblePage == RecyclerView.NO_POSITION || lastVisiblePage == RecyclerView.NO_POSITION) {
            (currentPageIndex() - PREFETCH_DISTANCE)..(currentPageIndex() + JUMP_LOAD_AHEAD)
        } else {
            (firstVisiblePage - PREFETCH_DISTANCE)..(lastVisiblePage + PREFETCH_DISTANCE)
        }

        val iterator = textCache.keys.iterator()
        while (textCache.size > CACHE_PAGE_LIMIT && iterator.hasNext()) {
            val eldestPageIndex = iterator.next()
            if (eldestPageIndex == pageIndex || eldestPageIndex in protectedRange) continue
            iterator.remove()
            updatePageState(TextModePageState.NotLoaded(eldestPageIndex))
        }
    }

    companion object {
        private val debugInstances = if (BuildConfig.DEBUG) WeakRegistry<TextModeContentLoader>() else null

        fun debugLiveInstances(): List<TextModeContentLoader> = debugInstances?.snapshot().orEmpty()

        private const val PREFETCH_DISTANCE = 2
        private const val JUMP_LOAD_AHEAD = 8
        private const val CACHE_PAGE_LIMIT = 24
    }
}
