// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.core.debug.HomeDebugState
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityHomeBinding
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HomeRendering(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val libraryController: HomeLibraryController,
    private val libraryScanner: LibraryScanner,
    private val permissionManager: PermissionManager,
    private val recentTab: RecentTabController,
    private val libraryTab: LibraryTabController,
    private val foldersTab: FoldersTabController,
    private val searchResultsAdapter: LibraryAdapter,
    initialAccess: Boolean,
) {
    private var allItems: List<HomeItem> = emptyList()
    private var lastPresentedScanIndex = LibraryIndex()
    private var desiredIndex = libraryScanner.index.value
    private val renderRequests = Channel<Unit>(Channel.CONFLATED)
    private val recordRequests = Channel<Unit>(Channel.CONFLATED)
    private val recordCache = HomeRecordCache()
    private var records: List<PdfRecord>? = null
    private var recordMetadata: RecordSnapshot? = null
    private var recordRevision = 0L
    private var desiredRevision = 0L
    private var committedRevision = -1L
    private var recordSnapshot: RecordSnapshot? = null
    private var lastRelinkStamp: RelinkStamp? = null
    private var storageAccess = initialAccess
    private var storageAccessRevision = 0L
    private var coversNeedRebind = false
    private var accessRefreshScheduled = false

    private data class AccessSnapshot(val revision: Long, val hasAccess: Boolean)

    private data class RecordSnapshot(
        val revision: Long,
        val epoch: Long,
        val access: AccessSnapshot,
        val items: List<HomeItem>,
    )

    private data class RelinkStamp(val generation: Long, val revision: Long, val epoch: Long)

    private data class RenderCommit(
        val access: AccessSnapshot,
        val scanIndex: LibraryIndex,
    )

    fun observeLibraryIndex() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    libraryScanner.index.collect { index ->
                        desiredIndex = index
                        val recordsInvalidated = recordCache.onLibraryIndex(index)
                        refresh()
                        if (recordsInvalidated || index.loaded && !index.active) recordRequests.trySend(Unit)
                    }
                }
                launch {
                    libraryController.observeRecords().retryWhen { error, _ ->
                        if (error is CancellationException) throw error
                        Log.e(ScanDebugSignals.TAG, "record observation failed", error)
                        delay(1_000)
                        true
                    }.collect { updated ->
                        val copiedRecords = updated.map { it.copy() }
                        records = copiedRecords
                        recordRevision++
                        recordCache.updateRecords(copiedRecords.filter { it.fileName.isNotEmpty() }.map {
                            HomeRecordCache.Identity(it.hash, it.uri.toString(), it.sourceUri)
                        })
                        refresh()
                        recordRequests.trySend(Unit)
                    }
                }
                launch {
                    for (request in recordRequests) {
                        try {
                            updateRecordSnapshot()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.e(ScanDebugSignals.TAG, "record snapshot failed", error)
                            delay(1_000)
                            recordRequests.trySend(Unit)
                        }
                    }
                }
                launch {
                    for (request in renderRequests) {
                        if (committedRevision == desiredRevision) continue
                        val revision = desiredRevision
                        try {
                            if (renderTabs()) committedRevision = revision
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.e(ScanDebugSignals.TAG, "home render failed", error)
                            delay(250)
                            renderRequests.trySend(Unit)
                        }
                    }
                }
                refresh()
                recordRequests.trySend(Unit)
            }
        }
    }

    fun onResume() {
        val accessChanged = permissionManager.recheck()
        val accessRefreshWasScheduled = accessRefreshScheduled
        libraryTab.applyProgressStyle()
        libraryTab.applyTitleStyle()
        recentTab.applyTitleStyle()
        foldersTab.applyTitleStyle()
        searchResultsAdapter.applyTitleStyle()
        invalidateAvailability()
        if (!accessChanged && !accessRefreshWasScheduled) {
            libraryScanner.refresh()
        }
        accessRefreshScheduled = false
    }

    fun onStop() {
        recordCache.invalidate()
    }

    fun invalidateAvailability() {
        recordCache.invalidate()
        recordRevision++
        recordRequests.trySend(Unit)
        refresh()
    }

    fun onStorageAccessChanged(hasAccess: Boolean) {
        if (storageAccess == hasAccess) {
            return
        }
        recentTab.invalidateAccessRender()
        storageAccess = hasAccess
        storageAccessRevision++
        coversNeedRebind = true
        accessRefreshScheduled = true
        libraryScanner.onStorageAccessChanged(hasAccess)
        invalidateAvailability()
    }

    fun refresh() {
        desiredRevision++
        renderRequests.trySend(Unit)
    }

    private suspend fun updateRecordSnapshot() {
        val metadata = loadRecordMetadata() ?: return
        val requestedRecords = records ?: return
        val revision = metadata.revision
        val access = metadata.access
        val probe = AvailabilityProbe(activity.applicationContext, access.hasAccess)
        val availability = recordCache.resolve { uri ->
            withContext(Dispatchers.IO) { probe.availabilityOf(Uri.parse(uri)) }
        } ?: return
        if (recordSnapshot?.revision != revision || recordSnapshot?.epoch != availability.epoch) {
            val items = metadata.items.map { it.copy(availability = availability.availability.getValue(it.uri.toString())) }
            if (revision != recordRevision || availability.epoch != recordCache.epoch ||
                !isStorageAccessCurrent(access)
            ) return
            recordSnapshot = RecordSnapshot(revision, availability.epoch, access, items.toList())
            refresh()
        }
        val scanIndex = desiredIndex
        if (!scanIndex.loaded || scanIndex.active || !access.hasAccess ||
            scanIndex.phase !in setOf(ScanPhase.IDLE, ScanPhase.INCOMPLETE)
        ) return
        val stamp = RelinkStamp(scanIndex.generation, scanIndex.revision, availability.epoch)
        if (stamp == lastRelinkStamp) return
        libraryController.relinkCompletedIndex(
            scanIndex, requestedRecords, availability.availability, probe,
            isCurrent = {
                val current = libraryScanner.index.value
                current.generation == scanIndex.generation && current.revision == scanIndex.revision &&
                    !current.active && recordCache.epoch == availability.epoch && permissionManager.hasFullAccess()
            },
        )
        if (recordCache.epoch == availability.epoch && isStorageAccessCurrent(access)) lastRelinkStamp = stamp
    }

    private suspend fun loadRecordMetadata(): RecordSnapshot? {
        val requestedRecords = records ?: return null
        val access = captureStorageAccess()
        val revision = recordRevision
        val epoch = recordCache.epoch
        recordMetadata?.takeIf {
            it.revision == revision && it.epoch == epoch && it.access == access
        }?.let { return it }
        val items = libraryController.recordItems(
            requestedRecords, activity.getString(R.string.home_title_annotated),
            AvailabilityProbe(activity.applicationContext, access.hasAccess),
        )
        if (revision != recordRevision || epoch != recordCache.epoch || !isStorageAccessCurrent(access)) return null
        return RecordSnapshot(revision, epoch, access, items).also {
            recordMetadata = it
            refresh()
        }
    }

    private suspend fun renderTabs(): Boolean {
        val metadata = loadRecordMetadata() ?: return false
        val access = metadata.access
        val scanIndex = desiredIndex
        val snapshot = recordSnapshot?.takeIf {
            it.revision == recordRevision && it.epoch == recordCache.epoch && it.access == access
        }
        val recordItems = snapshot?.items ?: metadata.items
        val displayIndex = libraryController.excludeMissingRecordFiles(recordItems, scanIndex)
        val visibleItems = libraryController.sort(recordItems)
            .filter { it.availability != Availability.MISSING }
        libraryTab.render(visibleItems, displayIndex)
        if (!isStorageAccessCurrent(access)) return false
        foldersTab.render(visibleItems, displayIndex)
        if (binding.searchView.isShowing) submitSearchResults(visibleItems, displayIndex)
        lastPresentedScanIndex = displayIndex
        allItems = visibleItems
        if (snapshot != null) {
            val commit = recentTab.renderTransaction<RenderCommit>(
                validate = {
                    isStorageAccessCurrent(it.access) && snapshot.epoch == recordCache.epoch
                },
            ) {
                submit(visibleItems)
                RenderCommit(access, scanIndex)
            } ?: return false
            rebindCoversIfNeeded()
            publishCommit(commit)
        } else {
            publishCommit(RenderCommit(access, scanIndex))
        }
        return true
    }

    private fun captureStorageAccess(): AccessSnapshot {
        val liveAccess = permissionManager.hasFullAccess()
        if (liveAccess != storageAccess) {
            onStorageAccessChanged(liveAccess)
        }
        return AccessSnapshot(storageAccessRevision, storageAccess)
    }

    private fun isStorageAccessCurrent(snapshot: AccessSnapshot): Boolean {
        val liveAccess = permissionManager.hasFullAccess()
        if (liveAccess != storageAccess) {
            onStorageAccessChanged(liveAccess)
        }
        return snapshot.revision == storageAccessRevision && snapshot.hasAccess == storageAccess
    }

    private fun rebindCoversIfNeeded() {
        if (!coversNeedRebind) {
            return
        }
        recentTab.onCoversChanged()
        libraryTab.onCoversChanged()
        foldersTab.onCoversChanged()
        searchResultsAdapter.notifyDataSetChanged()
        coversNeedRebind = false
    }

    fun submitSearchResults() {
        refresh()
    }

    private fun submitSearchResults(items: List<HomeItem>, scanIndex: LibraryIndex) {
        val query = binding.searchView.editText.text?.toString().orEmpty()
        searchResultsAdapter.submitList(
            libraryController.searchAll(items, libraryScanner.libraryEntries(scanIndex), query)
        )
    }

    fun showStats() {
        showLibraryStatsDialog(
            activity,
            allItems,
            libraryScanner.libraryEntries(lastPresentedScanIndex),
        )
    }

    fun republishDebugState() {
        if (!BuildConfig.DEBUG) {
            return
        }
        HomeDebugState.republish(
            binding.root,
            currentTab(),
            binding.searchView.isShowing,
            lastPresentedScanIndex,
        )
    }

    private fun publishCommit(commit: RenderCommit) {
        if (!BuildConfig.DEBUG) {
            return
        }
        HomeDebugState.publish(
            root = binding.root,
            tab = currentTab(),
            searching = binding.searchView.isShowing,
            index = commit.scanIndex,
            accessRevision = commit.access.revision,
            storageAccess = commit.access.hasAccess,
        )
    }

    private fun currentTab(): HomeTab = HomeTab.entries[binding.homePager.currentItem]
}
