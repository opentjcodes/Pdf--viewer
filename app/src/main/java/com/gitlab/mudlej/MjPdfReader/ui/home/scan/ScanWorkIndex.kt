// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import android.util.Log
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataState
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.ScanScope
import java.util.ArrayDeque
import java.util.Collections
import kotlinx.coroutines.CancellationException

internal class ScanWorkIndex {
    private val entries = LinkedHashMap<String, ScannedPdfEntry>()
    private val pending = ArrayDeque<SourceFingerprint>()
    private val scheduled = HashSet<SourceFingerprint>()
    private val deferredMetadata = HashSet<SourceFingerprint>()
    private val seenPaths = HashMap<DiscoverySource, MutableSet<String>>()
    private var cachedPaths = emptySet<String>()
    private val finishedSources = HashSet<DiscoverySource>()
    private var plannedSources = DiscoverySource.entries.toSet()
    private var currentGeneration = 0L
    private var generationRoots: List<String>? = emptyList()
    private var activeFingerprint: SourceFingerprint? = null
    private var allSourcesFinished = false
    private var failures = emptySet<DiscoverySource>()

    val generation: Long get() = currentGeneration
    val scopeRoots: List<String>? get() = generationRoots
    val hasEntries: Boolean get() = entries.isNotEmpty()
    val inFlight: SourceFingerprint? get() = activeFingerprint
    val hasPending: Boolean get() = pending.isNotEmpty()
    val discoveryComplete: Boolean get() = allSourcesFinished
    val filesystemComplete: Boolean get() = DiscoverySource.FILESYSTEM in finishedSources
    val sourceFailures: Set<DiscoverySource> get() = failures
    val discoveryPhase: ScanPhase
        get() = if (allSourcesFinished) ScanPhase.READING_DETAILS else ScanPhase.FINDING

    fun restore(rows: List<ScannedPdfEntry>, roots: List<String>?) {
        generationRoots = roots?.let { Collections.unmodifiableList(it.toList()) }
        rows.forEach { entries[it.path] = it }
        cachedPaths = entries.keys.toSet()
    }

    fun advanceGeneration(id: Long) {
        currentGeneration = id
    }

    fun resetScope(roots: List<String>?, expectedSources: Set<DiscoverySource> = DiscoverySource.entries.toSet()) {
        generationRoots = roots?.let { Collections.unmodifiableList(it.toList()) }
        plannedSources = expectedSources.toSet()
        cachedPaths = entries.keys.toSet()
        allSourcesFinished = false
        failures = emptySet()
        finishedSources.clear()
        seenPaths.clear()
        deferredMetadata.clear()
        clearPending()
        releaseActive()
    }

    fun clearPending() {
        pending.clear()
        scheduled.clear()
    }

    fun rebuildPending(rows: List<ScannedPdfEntry>) {
        rows.forEach { row ->
            entries[row.path] = row
            if (eligible(row.path) && row.metadataState == ScannedPdfMetadataState.PENDING) enqueue(row.fingerprint)
        }
    }

    suspend fun discover(event: ScanEvent.DiscoveryBatch, repository: PdfRepository): Boolean {
        if (event.generation != generation || event.source !in plannedSources || event.source in finishedSources) return false
        val changed = ArrayList<ScannedPdfEntry>()
        val seen = seenPaths.getOrPut(event.source) { HashSet() }
        for (sighting in event.entries) {
            seen += sighting.path
            val old = entries[sighting.path]
            if (old != null && old.fingerprint == sighting.fingerprint) continue
            val pendingEntry = sighting.withPendingMetadata()
            changed += pendingEntry
        }
        if (changed.isEmpty()) return false
        repository.persistDiscoveredPdfs(changed)
        changed.forEach { changedEntry ->
            entries[changedEntry.path] = changedEntry
            if (eligible(changedEntry.path)) enqueue(changedEntry.fingerprint)
        }
        return true
    }

    fun sourceFinished(event: ScanEvent.DiscoverySourceFinished): Boolean {
        if (event.generation != generation || event.source !in plannedSources || !finishedSources.add(event.source)) return false
        if (!finishedSources.containsAll(plannedSources)) return false
        allSourcesFinished = true
        return true
    }

    fun sourceFailed(event: ScanEvent.DiscoverySourceFailed): Boolean {
        if (event.generation != generation || event.source !in plannedSources || event.source in finishedSources) return false
        failures = failures + event.source
        return true
    }

    fun reconciliationCandidates(walkRoots: List<String>, excludedRoots: List<String>): List<ScannedPdfEntry> =
        reconciliationCandidates(listOf(ScanWalk(walkRoots, excludedRoots)))

    fun reconciliationCandidates(walks: List<ScanWalk>): List<ScannedPdfEntry> {
        if (!discoveryComplete || sourceFailures.isNotEmpty()) return emptyList()
        return entries.values.filter { row ->
            row.path in cachedPaths && eligible(row.path) && row.path !in seenPaths[DiscoverySource.FILESYSTEM].orEmpty() && (
                (DiscoverySource.MEDIA_STORE in plannedSources && row.path !in seenPaths[DiscoverySource.MEDIA_STORE].orEmpty()) ||
                    (DiscoverySource.FILESYSTEM in plannedSources && walks.any { it.contains(row.path) })
                )
        }
    }

    suspend fun reconcileSource(fingerprint: SourceFingerprint, state: ScanPathState, repository: PdfRepository) {
        when (state) {
            is ScanPathState.Present -> {
                val old = entries[state.entry.path]
                if (old?.fingerprint == state.entry.fingerprint) return
                val changed = state.entry.withPendingMetadata()
                repository.persistDiscoveredPdfs(listOf(changed))
                entries[changed.path] = changed
                if (eligible(changed.path)) enqueue(changed.fingerprint)
            }
            ScanPathState.Missing -> {
                if (repository.pruneScannedPdf(fingerprint)) {
                    if (entries[fingerprint.path]?.fingerprint == fingerprint) entries.remove(fingerprint.path)
                    pending.removeAll { it == fingerprint }
                    scheduled.remove(fingerprint)
                } else {
                    reconcilePath(fingerprint.path, repository)
                }
            }
            ScanPathState.Unavailable -> Unit
        }
    }

    private fun enqueue(fingerprint: SourceFingerprint, first: Boolean = false) {
        if (fingerprint == inFlight || fingerprint in deferredMetadata || !scheduled.add(fingerprint)) return
        if (first) pending.addFirst(fingerprint) else pending.addLast(fingerprint)
    }

    fun deferMetadata(fingerprint: SourceFingerprint) {
        deferredMetadata += fingerprint
        pending.removeAll { it == fingerprint }
        scheduled.remove(fingerprint)
    }

    fun claimNext(): SourceFingerprint? {
        if (inFlight != null) return null
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            scheduled.remove(candidate)
            val row = entries[candidate.path]
            if (row?.fingerprint != candidate || row.metadataState != ScannedPdfMetadataState.PENDING) continue
            activeFingerprint = candidate
            return candidate
        }
        return null
    }

    fun releaseActive() {
        activeFingerprint = null
    }

    fun retryFirst(fingerprint: SourceFingerprint) {
        scheduled.remove(fingerprint)
        enqueue(fingerprint, first = true)
    }

    suspend fun commitMetadata(result: MetadataResult, repository: PdfRepository): Boolean = when {
        result.sourceChanged -> false
        result.failure != null -> repository.failScannedPdfMetadata(result.fingerprint, result.hash, result.failure)
        else -> repository.completeScannedPdfMetadata(result.fingerprint, result.hash, result.pageCount)
    }

    fun applyCommittedMetadata(result: MetadataResult) {
        val fingerprint = result.fingerprint
        val old = entries[fingerprint.path] ?: return
        if (old.fingerprint != fingerprint) return
        entries[fingerprint.path] = old.copy(
            hash = result.hash ?: old.hash,
            pageCount = if (result.failure == null) result.pageCount else 0,
            metadataState = if (result.failure == null) {
                ScannedPdfMetadataState.COMPLETE
            } else {
                ScannedPdfMetadataState.FAILED
            },
            metadataFailure = result.failure,
        )
    }

    suspend fun reconcilePath(path: String, repository: PdfRepository) {
        val durable = repository.findScannedPdfByPath(path)
        if (durable == null) entries.remove(path) else rebuildPending(listOf(durable))
    }

    suspend fun recoverInterrupted(fingerprint: SourceFingerprint, repository: PdfRepository) {
        ScanDebugSignals.noteWorkerDeath(fingerprint)
        val row = try {
            repository.recordScannedPdfWorkerDeath(fingerprint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(ScanDebugSignals.TAG, "worker-death checkpoint failed; retaining pending work", error)
            retryFirst(fingerprint)
            return
        } ?: return
        entries[row.path] = row
        if (row.metadataState != ScannedPdfMetadataState.PENDING) return
        enqueue(fingerprint, first = true)
        ScanDebugSignals.noteRetry(fingerprint)
    }

    suspend fun removeFile(path: String, repository: PdfRepository) {
        repository.pruneScannedPdfsThrowing(listOf(path))
        entries.remove(path)
        pending.removeAll { it.path == path }
        scheduled.removeAll { it.path == path }
    }

    suspend fun renameFile(oldPath: String, newPath: String, repository: PdfRepository): Boolean {
        if (!repository.renameScannedPdfThrowing(oldPath, newPath)) return false
        val old = entries[oldPath] ?: return false
        val renamed = old.withPendingMetadata(path = newPath)
        repository.persistDiscoveredPdfs(listOf(renamed))
        entries.remove(oldPath)
        entries[newPath] = renamed
        if (eligible(newPath)) enqueue(renamed.fingerprint)
        return true
    }

    private fun ScannedPdfEntry.withPendingMetadata(path: String = this.path): ScannedPdfEntry = copy(
        path = path,
        hash = null,
        pageCount = 0,
        metadataState = ScannedPdfMetadataState.PENDING,
        metadataFailure = null,
        metadataWorkerDeaths = 0,
    )

    suspend fun storeHash(fingerprint: SourceFingerprint, hash: String, repository: PdfRepository): Boolean {
        if (!repository.updateScannedPdfHash(fingerprint, hash)) return false
        val entry = entries[fingerprint.path] ?: return false
        if (entry.fingerprint != fingerprint) return false
        entries[fingerprint.path] = entry.copy(hash = hash)
        return true
    }

    fun snapshot(
        phase: ScanPhase,
        loaded: Boolean,
        showProgress: Boolean = false,
        revision: Long = 0L,
    ): LibraryIndex {
        val scopedEntries = entries.values.filter { eligible(it.path) }
        val completedDetails = scopedEntries.count { it.metadataState != ScannedPdfMetadataState.PENDING }
        return LibraryIndex(
            entries = Collections.unmodifiableList(entries.values.toList()),
            loaded = loaded,
            generation = generation,
            phase = phase,
            foundCount = scopedEntries.size,
            detailsCompleted = completedDetails,
            detailsTotal = scopedEntries.size,
            sourceFailures = Collections.unmodifiableSet(sourceFailures.toSet()),
            showProgress = showProgress,
            revision = revision,
            scopeRoots = generationRoots,
        )
    }

    private fun eligible(path: String): Boolean = ScanScope.contains(generationRoots, path)
}
