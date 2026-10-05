// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.pdf.SearchResult
import com.gitlab.mudlej.MjPdfReader.databinding.ActivitySearchBinding
import com.gitlab.mudlej.MjPdfReader.core.ui.attachFilterSearchView
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.gitlab.mudlej.MjPdfReader.pdf.ExtractorScreen
import com.gitlab.mudlej.MjPdfReader.pdf.PdfExtractor
import com.gitlab.mudlej.MjPdfReader.core.ui.configureSearchIcon
import com.gitlab.mudlej.MjPdfReader.core.text.containsAccentInsensitive
import com.gitlab.mudlej.MjPdfReader.core.ui.tintIconsForChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : AppCompatActivity(), SearchResultFunctions {

    private lateinit var binding: ActivitySearchBinding
    private val searchResultAdapter = SearchResultAdapter(this)
    private var searchResults: MutableList<SearchResult> = mutableListOf()
    private lateinit var pdfExtractor: PdfExtractor
    private val extractorScreen = ExtractorScreen(this)
    private lateinit var actionBarMenu: Menu
    private var fileHash: String? = null
    private var searchQuery: String = ""
    private var restoredListPosition: Int = -1
    private var restoredListOffsetPx: Int = 0
    private var restoreListPositionPending = true
    private var restoredNestedQuery: String? = null
    private var restoredNestedQueryApplied = false
    private var nestedQueryJob: Job? = null
    private var pendingResultClick: SearchResult? = null
    private var ignoreAccents = false
    private var coordinatorListener: SearchCoordinator.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(R.string.searching)

        val pref = Preferences(PreferenceManager.getDefaultSharedPreferences(this))
        ignoreAccents = pref.getSearchIgnoreAccents()
        searchResultAdapter.ignoreAccents = ignoreAccents

        extractorScreen.open("Failed to read text! (file move or deleted?)") { extractor ->
            pdfExtractor = extractor
            initUi()
            initSearchResults()
        }
    }

    override fun onPause() {
        saveSearchSessionState()
        super.onPause()
    }

    private fun initUi() {
        initLoadingProgressBar()
        initRecyclerView()
    }

    private fun initLoadingProgressBar() {
        binding.searchProgressBar.max = pdfExtractor.getPageCount()
        binding.searchProgressBar.progress = 0
        binding.searchProgressBar.visibility = View.GONE
        hideProgressBar()
    }

    private fun showProgressBar() {
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun hideProgressBar() {
        binding.progressBar.visibility = View.GONE
    }

    override fun onDestroy() {
        coordinatorListener?.let { SearchCoordinator.unsubscribe(it) }
        extractorScreen.close()
        super.onDestroy()
    }

    private fun initRecyclerView() {
        searchResultAdapter.submitList(emptyList())
        searchResultAdapter.progressBar = binding.progressBar

        binding.searchRecyclerView.apply {
            adapter = searchResultAdapter
            layoutManager = LinearLayoutManager(this@SearchActivity)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        restoreListPositionPending = false
                    }
                }
            })
        }
    }

    private fun restorePositionInList() {
        if (!restoreListPositionPending) return
        val position = if (restoredListPosition != -1) {
            restoredListPosition
        } else {
            intent.getIntExtra(PDF.resultPositionInListKey, -1)
        }
        if (position == -1) {
            restoreListPositionPending = false
            return
        }

        if (position in 0 until searchResultAdapter.itemCount) {
            val layoutManager = binding.searchRecyclerView.layoutManager as? LinearLayoutManager ?: return
            restoreListPositionPending = false
            layoutManager.scrollToPositionWithOffset(position, restoredListOffsetPx)
        }
    }

    private fun initSearchResults() {
        searchQuery = intent.getStringExtra(PDF.searchQueryKey)?.trim().orEmpty()
        fileHash = intent.getStringExtra(PDF.fileHashKey)
        if (searchQuery.isBlank()) {
            return
        }
        val cachedSession = SearchSessionCache.get(fileHash, searchQuery, ignoreAccents)
        if (cachedSession != null) {
            restoredListPosition = cachedSession.listPosition
            restoredListOffsetPx = cachedSession.listOffsetPx
            restoredNestedQuery = cachedSession.nestedQuery
            hideProgressBar()
            binding.searchProgressBar.visibility = View.GONE
            searchResults = cachedSearchResults(cachedSession.hits)
            searchResultAdapter.nestedQuery = restoredNestedQuery
            searchResultAdapter.submitList(visibleResultRows()) { restorePositionInList() }
            postSearch()
            if (!restoredNestedQuery.isNullOrBlank()) {
                invalidateOptionsMenu()
            }
            return
        }

        hideProgressBar()
        binding.searchProgressBar.progress = 0
        binding.searchProgressBar.visibility = View.VISIBLE
        val listener = object : SearchCoordinator.Listener {
            override fun onProgress(pagesScanned: Int, pageCount: Int) {
                binding.searchProgressBar.max = pageCount
                binding.searchProgressBar.progress = pagesScanned
            }

            override fun onResults(results: List<SearchResult>, finished: Boolean) {
                searchResults = results.toMutableList()
                searchResultAdapter.submitList(visibleResultRows()) { restorePositionInList() }
                title = "${"%,d".format(results.size)} ${getString(R.string.search_results)}"
                if (finished) {
                    hideProgressBar()
                    binding.searchProgressBar.visibility = View.GONE
                    postSearch()
                }
            }
        }
        coordinatorListener = listener
        SearchCoordinator.startOrSubscribe(
            this,
            intent.getStringExtra(PDF.filePathKey),
            intent.getStringExtra(PDF.passwordKey),
            fileHash,
            searchQuery,
            ignoreAccents,
            listener,
        )
    }

    private fun cachedSearchResults(hits: List<SearchSessionCache.Hit>): MutableList<SearchResult> {
        return hits.sortedBy { it.resultIndex }.map { hit ->
            SearchResult(
                originalIndex = hit.originalIndex,
                inputStart = hit.inputStart,
                inputEnd = hit.inputEnd,
                text = hit.text,
                pageNumber = hit.pageNumber,
                expanded = hit.expanded,
            ).apply {
                searchResultIndexInList = hit.resultIndex
            }
        }.toMutableList()
    }

    private fun visibleSearchResults(): List<SearchResult> {
        val query = searchResultAdapter.nestedQuery
        return if (query.isNullOrBlank()) {
            searchResults.toList()
        } else if (ignoreAccents) {
            searchResults.filter { it.text.containsAccentInsensitive(query) }
        } else {
            searchResults.filter { it.text.contains(query, true) }
        }
    }

    private fun visibleResultRows(): List<SearchResultRow> {
        val query = searchResultAdapter.nestedQuery
        return visibleSearchResults().map { SearchResultRow(it, query) }
    }

    private fun postSearch() {
        if (::actionBarMenu.isInitialized) {
            configureSearchIcon(actionBarMenu, searchResults.isNotEmpty())
        }
        // set up the title in the App Bar
        title = "${"%,d".format(searchResults.size)} ${getString(R.string.search_results)}"

        // show too many results message
        if (searchResults.size > PDF.TOO_MANY_RESULTS) {
            AppSnackbar.make(binding.root,getString(R.string.too_many_results_may_be_slow), Snackbar.LENGTH_INDEFINITE).also {
                it.setAction(getText(R.string.ok)) { }
                it.show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.search_menu, menu)
        menu.tintIconsForChrome(this)
        actionBarMenu = menu
        configureSearchIcon(menu, searchResults.isNotEmpty())
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            else -> super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.attachFilterSearchView(
            binding.root,
            onQueryChanged = { query ->
                nestedQueryJob?.cancel()
                nestedQueryJob = lifecycleScope.launch {
                    delay(NESTED_QUERY_DEBOUNCE_MS)
                    searchResultAdapter.nestedQuery = query
                    showProgressBar()
                    val rows = withContext(Dispatchers.Default) { visibleResultRows() }
                    searchResultAdapter.submitList(rows)
                    AppSnackbar.make(
                        binding.root,
                        getString(R.string.number_of_filtered_results).format(rows.size),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                null
            },
            onClosed = {
                nestedQueryJob?.cancel()
                searchResultAdapter.nestedQuery = null
                searchResultAdapter.submitList(visibleResultRows())
                true
            },
        )
        val nestedQuery = restoredNestedQuery
        if (!restoredNestedQueryApplied && !nestedQuery.isNullOrBlank()) {
            restoredNestedQueryApplied = true
            val searchItem = menu.findItem(R.id.search_in_search_activity)
            searchItem.expandActionView()
            (searchItem.actionView as SearchView).setQuery(nestedQuery, false)
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onSearchResultClicked(searchResult: SearchResult) {
        pendingResultClick = searchResult
        saveSearchSessionState()
        val resultIntent = Intent()
        resultIntent.putExtra(PDF.searchResultKey, Gson().toJson(searchResult))
        resultIntent.putExtra(PDF.searchQueryResultKey, searchQuery)
        resultIntent.putExtra(PDF.searchIgnoreAccentsKey, ignoreAccents)
        setResult(PDF.SEARCH_RESULT_OK, resultIntent)
        finish()
    }

    override fun onShowMoreResultTextClicked(searchResult: SearchResult) {
        val searchResultIndex = searchResult.searchResultIndexInList
        if (searchResultIndex !in searchResults.indices) {
            return
        }
        val query = searchQuery.ifBlank { searchResult.text.substring(searchResult.inputStart, searchResult.inputEnd) }

        lifecycleScope.launch {
            val pageText = withContext(Dispatchers.Default) {
                pdfExtractor.getPageText(searchResult.pageNumber)
            }
            val newSearchResult = SearchCoordinator.buildSearchResult(
                query,
                searchResult.originalIndex,
                pageText,
                searchResult.pageNumber,
                200,
                expanded = true,
                matchLength = searchResult.inputEnd - searchResult.inputStart,
            )
            newSearchResult.searchResultIndexInList = searchResultIndex
            if (searchResultIndex !in searchResults.indices) {
                return@launch
            }
            searchResults[searchResultIndex] = newSearchResult
            SearchSessionCache.updateHit(
                fileHash,
                searchQuery,
                ignoreAccents,
                searchResultIndex,
                expanded = true,
                text = newSearchResult.text,
                inputStart = newSearchResult.inputStart,
                inputEnd = newSearchResult.inputEnd,
            )
            searchResultAdapter.submitList(visibleResultRows())
        }
    }

    private fun saveSearchSessionState() {
        if (!::binding.isInitialized || searchQuery.isBlank()) {
            return
        }

        val clicked = pendingResultClick
        if (clicked != null) {
            SearchSessionCache.updateUiState(
                fileHash,
                searchQuery,
                ignoreAccents,
                clicked.searchResultIndexInList,
                0,
                nestedQuery = null,
            )
            return
        }

        val layoutManager = binding.searchRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == -1) {
            return
        }
        val firstVisibleView = layoutManager.findViewByPosition(position)
        val offsetPx = firstVisibleView?.top?.minus(binding.searchRecyclerView.paddingTop) ?: 0
        SearchSessionCache.updateUiState(
            fileHash,
            searchQuery,
            ignoreAccents,
            position,
            offsetPx,
            searchResultAdapter.nestedQuery,
        )
    }

    companion object {
        private const val NESTED_QUERY_DEBOUNCE_MS = 200L
    }
}
