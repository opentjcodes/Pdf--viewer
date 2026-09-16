// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataState
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.core.debug.HomeRelinkTestHooks
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanPhase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class HomeLibraryController(
    private val pdfRepository: PdfRepository,
    private val pref: Preferences,
    private val historyPolicy: HistoryPolicy,
) {
    fun observeRecords(): Flow<List<PdfRecord>> = pdfRepository.observeRecords()

    suspend fun recordItems(
        records: List<PdfRecord>,
        annotatedTitleFormat: String,
        availabilityProbe: AvailabilityProbe,
    ): List<HomeItem> {
        val showPdfTitle = pref.getHomeShowPdfTitle()
        val items = withContext(Dispatchers.IO) {
            records
                .filter { it.fileName.isNotEmpty() }
                .map {
                    val availability = if (it.uri.scheme == "file") {
                        availabilityProbe.availabilityOf(it.uri)
                    } else Availability.ACCESS_UNKNOWN
                    HomeItem.from(
                        it, showPdfTitle, availability,
                        annotatedTitleFormat,
                    )
                }
        }
        return sort(items)
    }

    internal suspend fun relinkCompletedIndex(
        scanIndex: LibraryIndex,
        records: List<PdfRecord>,
        availability: Map<String, Availability>,
        probe: AvailabilityProbe,
        isCurrent: () -> Boolean,
    ) {
        if (!scanIndex.loaded || scanIndex.active ||
            scanIndex.phase !in setOf(ScanPhase.IDLE, ScanPhase.INCOMPLETE) ||
            !historyPolicy.canRecord() || !isCurrent()
        ) return
        HomeRelinkTestHooks.noteStarted()
        try {
            HomeRelinkTestHooks.awaitRelease()
            val candidates = scanIndex.entries.filter {
                it.hash != null && it.metadataState == ScannedPdfMetadataState.COMPLETE &&
                    ScanScope.contains(scanIndex.scopeRoots, it.path)
            }.groupBy { it.hash }
            for (record in records) {
                if (availability[record.uri.toString()] != Availability.MISSING) continue
                if (!historyPolicy.canRecord() || !isCurrent()) return
                val recordCandidates = candidates[record.hash] ?: continue
                val stillMissing = withContext(Dispatchers.IO) {
                    probe.availabilityOf(record.uri) == Availability.MISSING
                }
                if (!stillMissing) continue
                for (entry in recordCandidates) {
                    val file = File(entry.path)
                    val destination = Uri.fromFile(file)
                    if (record.uri == destination) continue
                    val repaired = pdfRepository.repairRecordIdentity(record, destination, file.nameWithoutExtension) {
                        historyPolicy.canRecord() && isCurrent() &&
                            (record.uri.scheme != "file" || record.uri.path?.let { File(it).canRead() } != true) &&
                            file.canRead() && SourceFingerprint.normalized(file.path, file.length(), file.lastModified()) == entry.fingerprint &&
                            historyPolicy.canRecord() && isCurrent()
                    }
                    if (repaired) break
                }
            }
        } finally {
            HomeRelinkTestHooks.noteFinished()
        }
    }

    fun sort(items: List<HomeItem>): List<HomeItem> {
        return when (pref.getHomeSort()) {
            HomeSortOrder.LAST_OPENED -> items.sortedWith(
                compareByDescending<HomeItem> { it.lastOpened }
                    .thenBy { it.sortKey }
                    .thenBy { it.uri.toString() }
            )
            HomeSortOrder.NAME -> items.sortedWith(
                compareBy<HomeItem> { it.sortKey }.thenBy { it.uri.toString() }
            )
        }
    }

    fun mergeDuringDiscovery(records: List<HomeItem>, entries: List<ScannedPdfEntry>): List<HomeItem> {
        val recordsByPath = records
            .filter { it.uri.scheme == "file" }
            .associateBy { it.uri.path }
        return entries.map { entry ->
            val match = recordsByPath[entry.path]
            match?.copy(
                sizeBytes = if (match.sizeBytes > 0) match.sizeBytes else entry.size,
                length = if (match.length > 0) match.length else entry.completedPageCount,
            ) ?: HomeItem.fromScan(entry)
        }.distinctBy { it.stableKey }
    }


    fun reconcileWithScan(records: List<HomeItem>, entries: List<ScannedPdfEntry>): List<HomeItem> {
        val recordsByPath = records
            .filter { it.uri.scheme == "file" }
            .associateBy { it.uri.path }
        val recordsByHash = records.associateBy { it.hash }
        return entries
            .map { entry ->
                val match = recordsByPath[entry.path] ?: entry.hash?.let { recordsByHash[it] }
                match?.copy(
                    sizeBytes = if (match.sizeBytes > 0) match.sizeBytes else entry.size,
                    length = if (match.length > 0) match.length else entry.completedPageCount,
                ) ?: HomeItem.fromScan(entry)
            }
            .distinctBy { it.uri }
    }

    private val ScannedPdfEntry.completedPageCount: Int
        get() = pageCount.takeIf { metadataState == ScannedPdfMetadataState.COMPLETE } ?: 0

    fun filterByChip(items: List<HomeItem>, filter: ListFilter): List<HomeItem> {
        return when (filter) {
            ListFilter.RECENT -> items
            ListFilter.ALL -> items
            ListFilter.FAVORITE -> items.filter { it.favorite }
            ListFilter.TO_READ -> items.filter { it.readingStatus == ReadingStatus.TO_READ }
            ListFilter.READING -> items.filter { it.readingStatus == ReadingStatus.READING }
            ListFilter.ON_HOLD -> items.filter { it.readingStatus == ReadingStatus.ON_HOLD }
            ListFilter.COMPLETED -> items.filter { it.readingStatus == ReadingStatus.COMPLETED }
            ListFilter.ABANDONED -> items.filter { it.readingStatus == ReadingStatus.ABANDONED }
        }
    }

    fun continueReading(items: List<HomeItem>): List<HomeItem> {
        return items
            .filter { it.hasBeenOpened }
            .sortedByDescending { it.lastOpened }
            .take(HERO_COUNT)
    }

    fun recents(items: List<HomeItem>, excluding: List<HomeItem>): List<HomeItem> {
        val excludedHashes = excluding.map { it.hash }.toSet()
        return items
            .filter { it.hasBeenOpened && it.hash !in excludedHashes }
            .sortedByDescending { it.lastOpened }
            .take(RECENTS_COUNT)
    }

    fun filterByQuery(items: List<HomeItem>, query: String): List<HomeItem> {
        if (query.isBlank()) {
            return items
        }
        return items.filter { it.title.contains(query, ignoreCase = true) }
    }

    fun searchAll(
        records: List<HomeItem>,
        entries: List<ScannedPdfEntry>,
        query: String,
    ): List<HomeItem> {
        if (query.isBlank()) {
            return records
        }
        val recordMatches = filterByQuery(records, query)
        val scanMatches = reconcileWithScan(records, entries)
            .filter { it.isScanOnly && it.title.contains(query, ignoreCase = true) }
        return sort(recordMatches + scanMatches)
    }

    companion object {
        private const val HERO_COUNT = 6
        private const val RECENTS_COUNT = 12
    }
}
