// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.tableofcontents

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.DebugRootDescription
import com.gitlab.mudlej.MjPdfReader.core.ui.attachFilterSearchView
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.gitlab.mudlej.MjPdfReader.pdf.TableOfContentsEntry
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityTableOfContentsBinding
import com.gitlab.mudlej.MjPdfReader.pdf.ExtractorScreen
import com.gitlab.mudlej.MjPdfReader.pdf.PdfExtractor
import com.gitlab.mudlej.MjPdfReader.core.ui.configureSearchIcon
import com.gitlab.mudlej.MjPdfReader.core.ui.tintIconsForChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TableOfContentsActivity : AppCompatActivity(), TableOfContentsFunctions {
    private lateinit var binding: ActivityTableOfContentsBinding
    private lateinit var pdfExtractor: PdfExtractor
    private val extractorScreen = ExtractorScreen(this)
    private val tableOfContentsAdapter = TableOfContentsAdapter(this, this)
    private var entries: List<TableOfContentsEntry> = listOf()
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var actionBarMenu: Menu
    private var restoredTableOfContentsState = TableOfContentsState()
    private var activeQuery: String? = null
    private var resultPrepared = false
    private var applyingSearchState = false
    private var currentPageIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivityTableOfContentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DebugRootDescription.attachLayoutDirection(binding.root)
        setupScreenChrome()
        title = getString(R.string.loading)
        currentPageIndex = intent.getIntExtra(PDF.pageNumberKey, -1)
        restoreTableOfContentsState(savedInstanceState)

        showProgressBar()
        extractorScreen.open("Failed to read entries! (file move or deleted?)") { extractor ->
            pdfExtractor = extractor
            initUi()
            initEntries()
        }
    }

    private fun restoreTableOfContentsState(savedInstanceState: Bundle?) {
        restoredTableOfContentsState = savedInstanceState?.let { TableOfContentsState.from(it) } ?: TableOfContentsState.from(intent)
        tableOfContentsAdapter.setExpandedEntryPaths(restoredTableOfContentsState.expandedPaths)
        activeQuery = restoredTableOfContentsState.query
        tableOfContentsAdapter.query = activeQuery
    }

    private fun showProgressBar() {
        binding.progressBar.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        extractorScreen.close()
        super.onDestroy()
    }

    private fun initEntries() {
        lifecycleScope.launch {
            val loadedEntries = withContext(Dispatchers.Default) {
                pdfExtractor.getTableOfContents()
            }

            entries = loadedEntries
            tableOfContentsAdapter.currentEntryPath = findCurrentEntryPath()
            binding.progressBar.visibility = View.GONE
            submitVisibleEntries(restoreScroll = true)
            postGettingEntries()
        }
    }

    private fun postGettingEntries() {
        if (entries.isNotEmpty()) {
            binding.message.visibility = View.GONE
        }
        else {
            binding.message.text = getString(R.string.no_table_of_contents)
        }

        if (::actionBarMenu.isInitialized) {
            configureSearchIcon(actionBarMenu, entries.isNotEmpty())
            configureExpandCollapseItems(actionBarMenu)
            configureLocateMeItem(actionBarMenu)
            restoreSearchViewState(actionBarMenu)
        }
    }

    private fun configureExpandCollapseItems(menu: Menu) {
        val show = entries.any { it.hasSubEntries() }
        menu.findItem(R.id.expand_all_entries)?.isVisible = show
        menu.findItem(R.id.collapse_all_entries)?.isVisible = show
    }

    private fun configureLocateMeItem(menu: Menu) {
        menu.findItem(R.id.locate_current_entry)?.isVisible =
            tableOfContentsAdapter.currentEntryPath != null
    }

    private fun findCurrentEntryPath(): String? {
        if (currentPageIndex < 0) return null

        val flattened = mutableListOf<TableOfContentsEntry>()
        fun collect(entry: TableOfContentsEntry) {
            if (entry.pageIdx >= 0) flattened.add(entry)
            entry.subEntries.forEach(::collect)
        }
        entries.forEach(::collect)

        return flattened
            .sortedWith(compareBy({ it.pageIdx }, { it.level }))
            .lastOrNull { it.pageIdx <= currentPageIndex }
            ?.path
    }

    private fun locateCurrentEntry() {
        val path = tableOfContentsAdapter.currentEntryPath ?: return
        if (tableOfContentsAdapter.isFiltering()) return

        tableOfContentsAdapter.expandAncestors(path)
        tableOfContentsAdapter.refresh {
            val position = tableOfContentsAdapter.currentList.indexOfFirst { it.entry.path == path }
            if (position >= 0) {
                layoutManager.scrollToPositionWithOffset(position, binding.bookmarksRecyclerView.height / 3)
            }
        }
    }

    private fun initUi() {
        title = getString(R.string.table_of_contents)
        layoutManager = LinearLayoutManager(this@TableOfContentsActivity)
        binding.bookmarksRecyclerView.apply {
            adapter = tableOfContentsAdapter
            layoutManager = this@TableOfContentsActivity.layoutManager
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.table_of_contents_menu, menu)
        menu.tintIconsForChrome(this)
        actionBarMenu = menu
        configureSearchIcon(menu, entries.isNotEmpty())
        configureExpandCollapseItems(menu)
        configureLocateMeItem(menu)
        initSearchView(menu)
        restoreSearchViewState(menu)
        return true
    }

    private fun initSearchView(menu: Menu) {
        menu.attachFilterSearchView(
            binding.root,
            onQueryChanged = { query ->
                if (applyingSearchState) {
                    null
                } else {
                    activeQuery = query.trim().takeUnless { it.isBlank() }
                    tableOfContentsAdapter.query = activeQuery
                    val visibleEntries = submitVisibleEntries()
                    if (activeQuery.isNullOrBlank()) {
                        null
                    } else {
                        tableOfContentsAdapter.visibleEntryCount(visibleEntries)
                    }
                }
            },
            onClosed = {
                activeQuery = null
                tableOfContentsAdapter.query = null
                submitVisibleEntries()
                false
            },
        )
    }

    private fun restoreSearchViewState(menu: Menu) {
        val query = activeQuery ?: return
        if (query.isBlank()) return

        val searchItem = menu.findItem(R.id.search_in_search_activity)
        if (!searchItem.isVisible) return

        val searchView = searchItem.actionView as SearchView
        applyingSearchState = true
        searchItem.expandActionView()
        searchView.setQuery(query, false)
        searchView.clearFocus()
        applyingSearchState = false
    }

    private fun visibleEntries(): List<TableOfContentsEntry> {
        return if (activeQuery.isNullOrBlank()) {
            entries
        }
        else {
            entries.filter(tableOfContentsAdapter::matchesSelfOrDescendant)
        }
    }

    private fun submitVisibleEntries(restoreScroll: Boolean = false): List<TableOfContentsEntry> {
        tableOfContentsAdapter.submitEntries(entries) {
            if (restoreScroll) restorePositionInList()
        }
        return visibleEntries()
    }

    private fun restorePositionInList() {
        if (!::layoutManager.isInitialized) return
        if (restoredTableOfContentsState.scrollPosition !in 0 until tableOfContentsAdapter.itemCount) return

        layoutManager.scrollToPositionWithOffset(restoredTableOfContentsState.scrollPosition, restoredTableOfContentsState.scrollOffset)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.locate_current_entry -> locateCurrentEntry()
            R.id.expand_all_entries -> tableOfContentsAdapter.expandAll()
            R.id.collapse_all_entries -> tableOfContentsAdapter.collapseAll()
            else -> super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onEntryClicked(entry: TableOfContentsEntry) {
        if (entry.pageIdx < 0) {
            return
        }
        setResultWithTableOfContentsState(PDF.TABLE_OF_CONTENTS_RESULT_OK, entry.pageIdx.toInt())
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentTableOfContentsState().putInto(outState)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (!resultPrepared && ::binding.isInitialized) {
            setResultWithTableOfContentsState(Activity.RESULT_CANCELED)
        }
        super.finish()
    }

    private fun setResultWithTableOfContentsState(resultCode: Int, selectedPageIndex: Int? = null) {
        val resultIntent = Intent()
        currentTableOfContentsState().putInto(resultIntent)
        selectedPageIndex?.let { resultIntent.putExtra(PDF.chosenTableOfContentsEntryKey, it) }
        resultPrepared = true
        setResult(resultCode, resultIntent)
    }

    private fun currentTableOfContentsState(): TableOfContentsState {
        val (scrollPosition, scrollOffset) = currentScrollState()
        return TableOfContentsState(
            expandedPaths = tableOfContentsAdapter.getExpandedEntryPaths(),
            scrollPosition = scrollPosition,
            scrollOffset = scrollOffset,
            query = activeQuery,
        )
    }

    private fun currentScrollState(): Pair<Int, Int> {
        if (!::layoutManager.isInitialized) {
            return Pair(restoredTableOfContentsState.scrollPosition, restoredTableOfContentsState.scrollOffset)
        }

        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == -1) {
            return Pair(restoredTableOfContentsState.scrollPosition, restoredTableOfContentsState.scrollOffset)
        }

        val view = layoutManager.findViewByPosition(position)
        val offset = view?.top?.minus(binding.bookmarksRecyclerView.paddingTop) ?: restoredTableOfContentsState.scrollOffset
        return Pair(position, offset)
    }

    companion object {
        const val TAG = "TableOfContentsActivity"
    }

}
