// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.core.debug.HomeDebugState
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryTabController(
    context: Context,
    private val pref: Preferences,
    coverCache: CoverCache,
    scope: CoroutineScope,
    functions: HomeItemFunctions,
    private val selection: () -> Set<String>,
    libraryController: HomeLibraryController,
    hasFullAccess: () -> Boolean,
    onGrantAccessClicked: () -> Unit,
    showScanSetup: () -> Boolean,
    onScanSetupClicked: () -> Unit,
    onFilterChanged: () -> Unit,
    private val onPresentationInvalidated: () -> Unit,
) {
    val libraryAdapter = LibraryAdapter(
        coverCache = coverCache,
        scope = scope,
        functions = functions,
        pref = pref,
        selection = selection,
    )
    private val sectionsAdapter = HomeSectionsAdapter(
        coverCache = coverCache,
        scope = scope,
        functions = functions,
        onGrantAccessClicked = onGrantAccessClicked,
        onScanSetupClicked = onScanSetupClicked,
        selectedFilter = ::currentFilter,
        onChipSelected = { filter ->
            pref.setListFilter(filter)
            onFilterChanged()
        },
    )

    private val presentation = LibraryPresentation(
        pref, libraryController, hasFullAccess, showScanSetup,
    )
    private val renderMutex = Mutex()
    private var hostStopped = false
    private val viewport = LibraryViewport(
        context, pref, libraryAdapter, sectionsAdapter,
        onInteractionChanged = ::onViewportInteractionChanged,
        onPresentationInvalidated = { if (!hostStopped) onPresentationInvalidated() },
    )

    fun attach(recyclerView: RecyclerView) = viewport.attach(recyclerView)

    fun onHostStopped() {
        hostStopped = true
        presentation.onSelectionChanged(selection().isNotEmpty())
        viewport.onHostStopped()
    }

    fun onHostStarted() {
        hostStopped = false
        presentation.onSelectionChanged(selection().isNotEmpty())
        viewport.onHostStarted()
        onPresentationInvalidated()
    }

    fun currentFilter(): ListFilter = presentation.currentFilter()

    suspend fun render(allItems: List<HomeItem>, index: LibraryIndex) {
        renderMutex.withLock {
            withContext(NonCancellable) {
                presentation.onSelectionChanged(selection().isNotEmpty())
                val plan = presentation.plan(allItems, index, libraryAdapter.currentList)
                if (!viewport.submit(plan.items, plan.sections, plan.canonical)) return@withContext
                val changed = presentation.commit(plan)
                if (BuildConfig.DEBUG) {
                    HomeDebugState.noteRendered(HomeTab.LIBRARY, plan.items.size)
                    val token = HomeDebugState.notePresentationCommitted()
                    if (plan.filter == ListFilter.ALL) {
                        val keys = plan.items.map { it.stableKey }
                        if (!plan.canonical) {
                            ScanDebugSignals.noteAppend(keys)
                        } else if (changed) {
                            ScanDebugSignals.noteSettlement(plan.generation, token, keys)
                        }
                    }
                }
            }
        }
    }

    fun applyViewMode() {
        libraryAdapter.viewMode = pref.getHomeViewMode()
        libraryAdapter.notifyDataSetChanged()
    }

    fun applyProgressStyle() {
        val style = pref.getHomeProgressStyle()
        if (libraryAdapter.progressStyle != style) {
            libraryAdapter.progressStyle = style
            libraryAdapter.notifyDataSetChanged()
        }
    }

    fun applyTitleStyle() = libraryAdapter.applyTitleStyle()

    fun applyGridSize() = viewport.applyGridSize()

    fun currentGridItems(): List<HomeItem> = libraryAdapter.currentList

    private fun onViewportInteractionChanged(active: Boolean) {
        val released = presentation.onViewportInteractionChanged(active)
        val invalidated = active && viewport.onInteractionBlocked()
        if ((released || invalidated) && !hostStopped) onPresentationInvalidated()
    }

    fun notifySelectionChanged() {
        libraryAdapter.notifySelectionChanged()
        val active = selection().isNotEmpty()
        val released = presentation.onSelectionChanged(active)
        val invalidated = active && viewport.onInteractionBlocked()
        if ((released || invalidated) && !hostStopped) onPresentationInvalidated()
    }

    fun onCoversChanged() {
        sectionsAdapter.rebindCovers()
        libraryAdapter.notifyDataSetChanged()
    }
}
