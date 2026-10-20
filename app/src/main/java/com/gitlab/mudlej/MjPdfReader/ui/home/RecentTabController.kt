// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.HomeDebugState
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

class RecentTabController(
    coverCache: CoverCache,
    scope: CoroutineScope,
    functions: HomeItemFunctions,
    pref: Preferences,
    private val libraryController: HomeLibraryController,
    onGrantAccessClicked: () -> Unit,
    selection: () -> Set<String> = { emptySet() },
) {

    private val sectionsAdapter = HomeSectionsAdapter(
        coverCache = coverCache,
        scope = scope,
        functions = functions,
        onGrantAccessClicked = onGrantAccessClicked,
    )
    private val rowsAdapter = LibraryAdapter(
        coverCache = coverCache,
        scope = scope,
        functions = functions,
        pref = pref,
        selection = selection,
    ).apply {
        viewMode = HomeViewMode.LIST
        metaStyle = ListMetaStyle.RECENT
    }

    private val layout = RecentLayout()

    private data class Rendered<T>(val value: T, val transaction: RenderTransaction)

    fun attach(recyclerView: RecyclerView) {
        layout.attach(recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = ConcatAdapter(sectionsAdapter, rowsAdapter)
    }

    inner class RenderTransaction internal constructor() {
        var renderedRows: Int = 0
            private set

        suspend fun submit(allItems: List<HomeItem>) {
            val visibleItems = allItems.filter { !it.hidden }
            val heroItems = libraryController.continueReading(
                visibleItems.filter { it.availability == Availability.AVAILABLE }
            )
            val rows = visibleItems
                .filter { it.hasBeenOpened }
                .sortedByDescending { it.lastOpened }
            val sections = buildSections(rows, heroItems)

            awaitSubmissions(rows, sections)
            renderedRows = rows.size
        }
    }

    private fun buildSections(
        rows: List<HomeItem>,
        heroItems: List<HomeItem>,
    ): List<HomeSection> = buildList {
        if (rows.any { it.availability == Availability.ACCESS_UNKNOWN }) {
            add(HomeSection.PermissionCard(R.string.home_recent_permission_message))
        }
        if (heroItems.isNotEmpty()) {
            add(HomeSection.Hero(heroItems))
        }
        if (rows.isEmpty()) {
            add(
                HomeSection.EmptyState(
                    R.string.home_empty_recent_title, R.string.home_empty_recent_message
                )
            )
        }
    }

    suspend fun <T> renderTransaction(
        validate: (T) -> Boolean,
        block: suspend RenderTransaction.() -> T,
    ): T? = layout.render(
        validate = { validate(it.value) },
        block = {
            val transaction = RenderTransaction()
            Rendered(transaction.block(), transaction)
        },
        onCommitted = { rendered ->
            if (BuildConfig.DEBUG) {
                HomeDebugState.noteRendered(HomeTab.RECENT, rendered.transaction.renderedRows)
            }
        },
    )?.value

    fun invalidateAccessRender() = layout.invalidateAccess()

    private suspend fun awaitSubmissions(rows: List<HomeItem>, sections: List<HomeSection>) {
        suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(2)
            val committed = Runnable {
                if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
            rowsAdapter.submitList(rows, committed)
            sectionsAdapter.submitList(sections, committed)
        }
    }

    fun currentItems(): List<HomeItem> = rowsAdapter.currentList

    fun applyTitleStyle() = rowsAdapter.applyTitleStyle()

    fun notifySelectionChanged() = rowsAdapter.notifySelectionChanged()

    fun onCoversChanged() {
        sectionsAdapter.rebindCovers()
        rowsAdapter.notifyDataSetChanged()
    }
}
