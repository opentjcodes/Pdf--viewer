// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanPhase

internal class LibraryPresentation(
    private val pref: Preferences,
    private val libraryController: HomeLibraryController,
    private val hasFullAccess: () -> Boolean,
    private val showScanSetup: () -> Boolean,
) {
    private var committed: Plan? = null
    private var interacting = false
    private var selecting = false

    internal data class Plan(
        val filter: ListFilter,
        val items: List<HomeItem>,
        val sections: List<HomeSection>,
        val canonical: Boolean,
        val generation: Long,
        val revision: Long,
    )

    fun currentFilter(): ListFilter {
        val stored = pref.getListFilter()
        return if (stored == ListFilter.RECENT || stored == ListFilter.FAVORITE) ListFilter.ALL else stored
    }

    fun plan(
        allItems: List<HomeItem>,
        index: LibraryIndex,
        presentedItems: List<HomeItem>,
    ): Plan {
        val filter = currentFilter()
        val canonical = !interacting && !selecting &&
            (index.phase == ScanPhase.IDLE || index.phase == ScanPhase.INCOMPLETE)
        val candidates = selectCandidates(allItems, index, filter, canonical)
        val previous = committed
        val items = if (canonical || (previous != null && previous.filter != filter)) {
            libraryController.sort(candidates)
        } else {
            stableAppend(presentedItems, candidates)
        }
        return Plan(
            filter, items,
            buildSections(filter, progressFor(index), items.isEmpty(), hasFullAccess, showScanSetup),
            canonical, index.generation, index.revision,
        )
    }

    fun commit(plan: Plan): Boolean {
        val changed = committed != plan
        committed = plan
        return changed
    }

    fun onViewportInteractionChanged(active: Boolean): Boolean {
        val wasBlocked = interacting || selecting
        interacting = active
        return wasBlocked && !interacting && !selecting
    }

    fun onSelectionChanged(active: Boolean): Boolean {
        val wasBlocked = interacting || selecting
        selecting = active
        return wasBlocked && !interacting && !selecting
    }

    private fun selectCandidates(
        allItems: List<HomeItem>,
        index: LibraryIndex,
        filter: ListFilter,
        canonical: Boolean,
    ): List<HomeItem> {
        if (filter != ListFilter.ALL) {
            return libraryController.filterByChip(allItems, filter).filter { !it.hidden }
        }
        val entries = index.entries.filter { ScanScope.contains(index.scopeRoots, it.path) }
        val candidates = if (canonical) {
            libraryController.reconcileWithScan(allItems, entries)
        } else {
            libraryController.mergeDuringDiscovery(allItems, entries)
        }
        return candidates.filter { !it.hidden }
    }

    private fun stableAppend(presented: List<HomeItem>, candidates: List<HomeItem>): List<HomeItem> {
        val fresh = candidates.associateBy { it.stableKey }
        val kept = presented.mapNotNull { fresh[it.stableKey] }
        val existing = kept.mapTo(HashSet()) { it.stableKey }
        return kept + libraryController.sort(candidates.filter { it.stableKey !in existing })
    }

    companion object {
        fun progressFor(index: LibraryIndex): ScanProgress? =
            if (index.showProgress) {
                progressFor(index.phase, index.foundCount, index.detailsCompleted, index.detailsTotal)
            } else null

        fun progressFor(phase: ScanPhase, found: Int, completed: Int, total: Int): ScanProgress? =
            when (phase) {
                ScanPhase.FINDING -> ScanProgress.Finding(found)
                ScanPhase.READING_DETAILS -> ScanProgress.ReadingDetails(completed, total)
                ScanPhase.INCOMPLETE -> ScanProgress.Incomplete(found)
                else -> null
            }

        fun buildSections(
            filter: ListFilter,
            progress: ScanProgress?,
            itemsEmpty: Boolean,
            hasFullAccess: () -> Boolean,
            showScanSetup: () -> Boolean,
        ): List<HomeSection> = buildList {
            if (!hasFullAccess()) add(HomeSection.PermissionCard())
            else if (showScanSetup()) add(HomeSection.ScanSetupCard)
            add(HomeSection.Chips)
            if (filter == ListFilter.ALL && progress != null) add(HomeSection.ScanProgressRow(progress))
            if (itemsEmpty && (filter != ListFilter.ALL || progress == null) && !showScanSetup()) {
                add(
                    when (filter) {
                        ListFilter.ALL -> HomeSection.EmptyState(
                            R.string.home_empty_all_title, R.string.home_empty_all_message,
                        )
                        else -> HomeSection.EmptyState(
                            R.string.home_empty_status_title, R.string.home_empty_status_message,
                        )
                    }
                )
            }
        }
    }
}
