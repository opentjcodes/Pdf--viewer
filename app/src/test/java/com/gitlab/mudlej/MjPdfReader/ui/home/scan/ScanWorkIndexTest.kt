// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import com.gitlab.mudlej.MjPdfReader.data.AppDatabase_Impl
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataFailure
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataState
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanWorkIndexTest {
    private val work = ScanWorkIndex()
    private val first = ScannedPdfEntry("/books/a.pdf", 100, 1_000, null)
    private val second = ScannedPdfEntry("/books/b.pdf", 200, 2_000, null)

    @Test fun restoredRowsKeepTheirOrderButOnlyScopedPendingRowsAreQueued() {
        val complete = first.copy(metadataState = ScannedPdfMetadataState.COMPLETE, pageCount = 5)
        val outside = second.copy(path = "/books-other/outside.pdf")
        work.restore(listOf(second, outside, complete), listOf("/books"))

        assertFalse(work.hasPending)
        val snapshot = snapshot()
        assertEquals(listOf(second, outside, complete), snapshot.entries)
        assertEquals(2, snapshot.foundCount)
        assertEquals(1, snapshot.detailsCompleted)
        assertEquals(2, snapshot.detailsTotal)

        work.rebuildPending(listOf(outside, second))
        assertEquals(second.fingerprint, work.claimNext())
        work.releaseActive()
        assertNull(work.claimNext())
        assertEquals(snapshot.entries, snapshot().entries)
    }

    @Test fun queuedAndInFlightFingerprintsAreDeduplicatedUntilRelease() {
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first, second, first, second))

        assertEquals(first.fingerprint, work.claimNext())
        assertNull(work.claimNext())
        work.rebuildPending(listOf(first, second))
        assertEquals(first.fingerprint, work.inFlight)

        work.releaseActive()
        assertEquals(second.fingerprint, work.claimNext())
        work.releaseActive()
        assertNull(work.claimNext())
        assertFalse(work.hasPending)
    }

    @Test fun changedQueuedFingerprintIsSkippedWithoutReorderingOtherWork() {
        val replacement = first.copy(size = first.size + 1)
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first, second))
        work.rebuildPending(listOf(replacement))

        assertEquals(second.fingerprint, work.claimNext())
        work.releaseActive()
        assertEquals(replacement.fingerprint, work.claimNext())
        work.releaseActive()
        assertNull(work.claimNext())
        assertEquals(listOf(replacement, second), snapshot().entries)
    }

    @Test fun changedInFlightPathCanQueueItsReplacementWithoutAcceptingTheOldMetadata() {
        val replacement = first.copy(lastModified = 3_000)
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first))
        assertEquals(first.fingerprint, work.claimNext())
        work.rebuildPending(listOf(replacement))

        work.applyCommittedMetadata(result(first))

        assertEquals(listOf(replacement), snapshot().entries)
        assertEquals(first.fingerprint, work.inFlight)
        assertTrue(work.hasPending)
        assertNull(work.claimNext())
        work.releaseActive()
        assertEquals(replacement.fingerprint, work.claimNext())
    }

    @Test fun committedMetadataDoesNotReleaseTheReservationOrMoveTheEntry() {
        val hashed = first.copy(hash = "existing", metadataWorkerDeaths = 1)
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(hashed, second))
        assertEquals(hashed.fingerprint, work.claimNext())

        work.applyCommittedMetadata(result(hashed).copy(hash = null))

        val rows = snapshot().entries
        assertEquals(listOf(first.path, second.path), rows.map { it.path })
        assertEquals("existing", rows.first().hash)
        assertEquals(12, rows.first().pageCount)
        assertEquals(ScannedPdfMetadataState.COMPLETE, rows.first().metadataState)
        assertEquals(1, rows.first().metadataWorkerDeaths)
        assertEquals(hashed.fingerprint, work.inFlight)
        assertNull(work.claimNext())
        work.releaseActive()
        assertEquals(second.fingerprint, work.claimNext())
    }

    @Test fun failedMetadataHasNoPageCountAndKeepsItsFailureReason() {
        work.restore(listOf(first), null)

        work.applyCommittedMetadata(result(first).copy(failure = ScannedPdfMetadataFailure.INVALID_PDF))

        val row = snapshot().entries.single()
        assertEquals("computed", row.hash)
        assertEquals(0, row.pageCount)
        assertEquals(ScannedPdfMetadataState.FAILED, row.metadataState)
        assertEquals(ScannedPdfMetadataFailure.INVALID_PDF, row.metadataFailure)
        assertEquals(1, snapshot().detailsCompleted)
    }

    @Test fun noLongerPendingRowsAreDiscardedAtClaimTime() {
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first, second))
        work.applyCommittedMetadata(result(first))

        assertEquals(second.fingerprint, work.claimNext())
        work.releaseActive()
        assertNull(work.claimNext())
        assertFalse(work.hasPending)
    }

    @Test fun retryAfterReleaseRunsBeforeLaterWork() {
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first, second))
        assertEquals(first.fingerprint, work.claimNext())

        work.releaseActive()
        work.retryFirst(first.fingerprint)

        assertEquals(first.fingerprint, work.claimNext())
        work.releaseActive()
        assertEquals(second.fingerprint, work.claimNext())
        assertEquals(0, snapshot().entries.first().metadataWorkerDeaths)
    }

    @Test fun generationAdvanceAndScopeResetHaveDifferentEffects() {
        work.advanceGeneration(10)
        work.restore(emptyList(), listOf("/books"))
        work.rebuildPending(listOf(first, second))
        work.claimNext()
        work.sourceFailed(ScanEvent.DiscoverySourceFailed(10, DiscoverySource.MEDIA_STORE, Exception("source")))
        assertFalse(work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.MEDIA_STORE)))
        assertFalse(work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.MEDIA_STORE)))
        assertTrue(work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.FILESYSTEM)))

        work.advanceGeneration(11)

        assertEquals(first.fingerprint, work.inFlight)
        assertTrue(work.hasPending)
        assertTrue(work.discoveryComplete)
        assertEquals(setOf(DiscoverySource.MEDIA_STORE), work.sourceFailures)
        assertFalse(work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.FILESYSTEM)))
        assertFalse(work.sourceFailed(ScanEvent.DiscoverySourceFailed(10, DiscoverySource.FILESYSTEM, Exception())))

        work.resetScope(listOf("/elsewhere"))

        assertEquals(11L, work.generation)
        assertNull(work.inFlight)
        assertFalse(work.hasPending)
        assertFalse(work.discoveryComplete)
        assertTrue(work.sourceFailures.isEmpty())
        assertEquals(ScanPhase.FINDING, work.discoveryPhase)
        assertEquals(listOf(first, second), snapshot().entries)
        assertEquals(0, snapshot().foundCount)
        work.rebuildPending(listOf(first, second))
        assertNull(work.claimNext())
        assertFalse(work.sourceFinished(ScanEvent.DiscoverySourceFinished(11, DiscoverySource.MEDIA_STORE)))
        assertTrue(work.sourceFinished(ScanEvent.DiscoverySourceFinished(11, DiscoverySource.FILESYSTEM)))
        assertEquals(ScanPhase.READING_DETAILS, work.discoveryPhase)
    }

    @Test fun snapshotsAreNotChangedByLaterEntryOrFailureUpdates() {
        work.restore(listOf(first), null)
        val before = snapshot()

        work.applyCommittedMetadata(result(first))
        work.sourceFailed(ScanEvent.DiscoverySourceFailed(0, DiscoverySource.FILESYSTEM, Exception()))

        assertEquals(listOf(first), before.entries)
        assertTrue(before.sourceFailures.isEmpty())
        assertEquals(0, before.detailsCompleted)
        assertEquals(1, snapshot().detailsCompleted)
        assertEquals(setOf(DiscoverySource.FILESYSTEM), snapshot().sourceFailures)
    }

    @Test fun mediaStoreOnlyGenerationCompletesWithoutWaitingForFilesystem() {
        work.advanceGeneration(10)
        val sources = mutableSetOf(DiscoverySource.MEDIA_STORE)
        work.resetScope(null, sources)
        sources += DiscoverySource.FILESYSTEM

        assertFalse(work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.FILESYSTEM)))
        assertTrue(work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.MEDIA_STORE)))
        assertTrue(work.discoveryComplete)
        assertFalse(work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.MEDIA_STORE)))
    }

    @Test fun scopeInputsAndPublishedCollectionsCannotMutateTheIndex() {
        val roots = mutableListOf("/books")
        work.resetScope(roots)
        roots.clear()
        work.rebuildPending(listOf(first, second))
        val before = snapshot()

        assertEquals(listOf("/books"), before.scopeRoots)
        assertEquals(2, before.foundCount)
        assertTrue(runCatching { (before.entries as MutableList).clear() }.isFailure)
        assertTrue(runCatching { (before.scopeRoots as MutableList).clear() }.isFailure)
        assertTrue(runCatching { (before.sourceFailures as MutableSet).add(DiscoverySource.FILESYSTEM) }.isFailure)
        assertEquals(before, snapshot())
    }

    @Test fun reconciliationRequiresAllPlannedSourcesAndNoFailuresAndOnlyIncludesEligibleRows() {
        work.resetScope(listOf("/books"))
        val outside = first.copy(path = "/elsewhere/a.pdf")
        work.restore(listOf(first, outside), listOf("/books"))

        assertTrue(work.reconciliationCandidates(emptyList(), emptyList()).isEmpty())
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.MEDIA_STORE))
        assertTrue(work.reconciliationCandidates(emptyList(), emptyList()).isEmpty())
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.FILESYSTEM))
        assertEquals(listOf(first), work.reconciliationCandidates(emptyList(), emptyList()))

        work.resetScope(listOf("/books"))
        work.sourceFailed(ScanEvent.DiscoverySourceFailed(0, DiscoverySource.MEDIA_STORE, Exception()))
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.MEDIA_STORE))
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.FILESYSTEM))
        assertTrue(work.reconciliationCandidates(listOf("/books"), emptyList()).isEmpty())
    }

    @Test fun terminalSnapshotRetainsCacheWhileScopeRetirementClearsQueueAndReservation() {
        work.restore(listOf(first, second), null)
        work.rebuildPending(listOf(first, second))
        work.claimNext()
        work.resetScope(emptyList(), setOf(DiscoverySource.MEDIA_STORE))

        val terminal = work.snapshot(ScanPhase.INCOMPLETE, loaded = true, revision = 7)
        assertFalse(work.hasPending)
        assertNull(work.inFlight)
        assertEquals(listOf(first, second), terminal.entries)
        assertFalse(terminal.active)
        assertFalse(terminal.showProgress)
        assertEquals(7L, terminal.revision)
        assertEquals(0, terminal.detailsTotal)
    }

    @Test fun durableRecheckQueuesAnUnfinishedFingerprintOnlyAfterItsReservationIsReleased() {
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first))
        work.claimNext()
        work.rebuildPending(listOf(first))
        assertFalse(work.hasPending)

        work.releaseActive()
        work.rebuildPending(listOf(first))
        assertEquals(first.fingerprint, work.claimNext())
    }

    @Test fun durableRebuildDoesNotQueueCompletedOrFailedRows() {
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(
            first.copy(metadataState = ScannedPdfMetadataState.COMPLETE),
            second.copy(metadataState = ScannedPdfMetadataState.FAILED),
        ))
        assertFalse(work.hasPending)
        assertEquals(2, snapshot().detailsCompleted)
    }

    @Test fun unchangedSightingsAreSeenWithoutPersistingOrQueueingMetadata() = runBlocking {
        val repository = PdfRepository(AppDatabase_Impl())
        val complete = first.copy(hash = "cached", pageCount = 3, metadataState = ScannedPdfMetadataState.COMPLETE)
        work.resetScope(null, setOf(DiscoverySource.MEDIA_STORE))
        work.restore(listOf(complete), null)

        assertFalse(work.discover(ScanEvent.DiscoveryBatch(0, DiscoverySource.MEDIA_STORE, listOf(first)), repository))
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.MEDIA_STORE))

        assertEquals(listOf(complete), snapshot().entries)
        assertFalse(work.hasPending)
        assertTrue(work.reconciliationCandidates(emptyList(), emptyList()).isEmpty())
    }

    @Test fun filesystemCoverageUsesItsOwnSightingsAndExcludesPreviouslyScannedSubtrees() = runBlocking {
        val repository = PdfRepository(AppDatabase_Impl())
        val retained = first.copy(path = "/books/retained/a.pdf")
        val outsideWalk = first.copy(path = "/other/a.pdf")
        work.restore(listOf(first, retained, outsideWalk), null)
        work.discover(ScanEvent.DiscoveryBatch(0, DiscoverySource.MEDIA_STORE, listOf(first, retained, outsideWalk)), repository)
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.MEDIA_STORE))
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.FILESYSTEM))

        assertEquals(listOf(first), work.reconciliationCandidates(listOf("/books"), listOf("/books/retained")))
    }

    @Test fun filesystemSightingsDoNotNeedAnotherProbeJustBecauseMediaStoreOmittedThem() = runBlocking {
        val repository = PdfRepository(AppDatabase_Impl())
        work.restore(listOf(first), null)
        work.discover(ScanEvent.DiscoveryBatch(0, DiscoverySource.FILESYSTEM, listOf(first)), repository)
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.MEDIA_STORE))
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.FILESYSTEM))

        assertTrue(work.reconciliationCandidates(listOf("/books"), emptyList()).isEmpty())
    }

    @Test fun unavailableAndUnchangedPathProbesPreserveTheActiveReservationAndCachedDetails() = runBlocking {
        val repository = PdfRepository(AppDatabase_Impl())
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first, second))
        work.claimNext()

        work.reconcileSource(first.fingerprint, ScanPathState.Unavailable, repository)
        work.reconcileSource(first.fingerprint, ScanPathState.Present(first), repository)

        assertEquals(first.fingerprint, work.inFlight)
        assertEquals(listOf(first, second), snapshot().entries)
        work.releaseActive()
        assertEquals(second.fingerprint, work.claimNext())
    }

    @Test fun failedBatchDoesNotPublishUncommittedRowsAndRetiredEventsCannotRepopulateTheQueue() = runBlocking {
        val repository = PdfRepository(AppDatabase_Impl())
        work.restore(listOf(first), null)
        val failed = runCatching {
            work.discover(ScanEvent.DiscoveryBatch(0, DiscoverySource.MEDIA_STORE, listOf(second)), repository)
        }
        assertTrue(failed.isFailure)
        assertEquals(listOf(first), snapshot().entries)
        assertFalse(work.hasPending)

        work.advanceGeneration(1)
        assertFalse(work.discover(ScanEvent.DiscoveryBatch(0, DiscoverySource.MEDIA_STORE, listOf(second)), repository))
        assertFalse(work.sourceFinished(ScanEvent.DiscoverySourceFinished(0, DiscoverySource.MEDIA_STORE)))
        assertTrue(work.reconciliationCandidates(emptyList(), emptyList()).isEmpty())
    }

    @Test fun unavailableMetadataKeepsItsReservationButDoesNotLoopDuringTheDurableRecheck() {
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first))
        work.claimNext()
        work.deferMetadata(first.fingerprint)

        assertEquals(first.fingerprint, work.inFlight)
        work.releaseActive()
        work.rebuildPending(listOf(first))
        assertFalse(work.hasPending)
        assertEquals(listOf(first), snapshot().entries)

        work.resetScope(null)
        work.rebuildPending(listOf(first))
        assertEquals(first.fingerprint, work.claimNext())
    }

    @Test fun deferringAnUnavailableFingerprintDoesNotBlockItsReplacementOrOtherMetadata() {
        val changed = first.copy(size = first.size + 1)
        work.restore(emptyList(), null)
        work.rebuildPending(listOf(first, second))
        work.deferMetadata(first.fingerprint)
        work.rebuildPending(listOf(changed))

        assertEquals(second.fingerprint, work.claimNext())
        work.releaseActive()
        assertEquals(changed.fingerprint, work.claimNext())
    }

    private fun snapshot() = work.snapshot(ScanPhase.FINDING, true)

    private fun result(row: ScannedPdfEntry) = MetadataResult(
        generation = work.generation,
        connectionToken = 0,
        fingerprint = row.fingerprint,
        hash = "computed",
        pageCount = 12,
        failure = null,
        sourceChanged = false,
        sample = MetadataMemorySample(0, 0, 0, false, 0, 0),
        workerPid = 0,
        workerInstance = 0,
        maxActiveDocuments = 1,
    )
}
