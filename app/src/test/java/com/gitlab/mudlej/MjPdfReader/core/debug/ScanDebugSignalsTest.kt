// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataHeadroomPolicy
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.DiscoverySource
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDebugSignalsTest {
    private val signals = ScanDebugSignals
    private val id = UUID.randomUUID()
    private val fingerprint = SourceFingerprint("/signals/$id.pdf", 10, 1_999)
    private val generation = id.mostSignificantBits

    @Test fun countersAreIndependentAndKeyedByEveryRawFingerprintField() {
        val variants = listOf(fingerprint, fingerprint.copy(path = "${fingerprint.path}/other"),
            fingerprint.copy(size = 11), fingerprint.copy(lastModified = 1_998))
        val before = variants.map(::counts)
        assertTrue(before.all { it == listOf(0, 0, 0) })
        variants.forEachIndexed { i, value ->
            repeat(i + 1) { signals.noteDispatch(value) }
            repeat(i + 2) { signals.noteWorkerDeath(value) }
            repeat(i + 3) { signals.noteRetry(value) }
        }
        signals.noteWorkerDeath(null)
        variants.forEachIndexed { i, value ->
            assertEquals(before[i].mapIndexed { column, count -> count + i + column + 1 }, counts(value.copy()))
        }
        assertEquals(variants.last(), signals.snapshot().inFlight)
    }

    @Test fun concurrentFirstUseAndIncrementsLoseNoCounts() {
        val before = counts(fingerprint)
        val start = CyclicBarrier(4)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = List(4) {
                executor.submit {
                    start.await(5, SECONDS)
                    repeat(50) {
                        signals.noteDispatch(fingerprint)
                        signals.noteWorkerDeath(fingerprint)
                        signals.noteRetry(fingerprint)
                    }
                }
            }
            futures.forEach { it.get(5, SECONDS) }
            assertEquals(before.map { it + 200 }, counts(fingerprint))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, SECONDS))
        }
    }

    @Test fun discoveryTracksPeakAndFinishWhileAggregateCountersRemainIndependent() {
        val runs = signals.discoveryRuns()
        val peak = signals.maxDiscoveryProducers() + 1
        val committed = signals.committedResults()
        val stale = signals.staleResultRejections()
        repeat(peak) { signals.noteDiscoveryStarted(generation) }
        try {
            assertEquals(peak, signals.maxDiscoveryProducers())
        } finally {
            repeat(peak) { signals.noteDiscoveryFinished(generation) }
        }
        signals.noteDiscoveryFinished(generation)
        // A new peak exposes both missed decrements and an underflow after the extra finish.
        repeat(peak + 1) { signals.noteDiscoveryStarted(generation) }
        try {
            assertEquals(peak + 1, signals.maxDiscoveryProducers())
        } finally {
            repeat(peak + 1) { signals.noteDiscoveryFinished(generation) }
        }
        assertEquals(peak + 1, signals.maxDiscoveryProducers())
        assertEquals(runs + 2 * peak + 1, signals.discoveryRuns())
        signals.noteCommitted()
        repeat(2) { signals.noteStaleResultRejected() }
        assertEquals(committed + 1, signals.committedResults())
        assertEquals(stale + 2, signals.staleResultRejections())
        val before = signals.snapshot()
        signals.noteSourceFailure(DiscoverySource.FILESYSTEM, IllegalStateException(id.toString()))
        assertEquals(before, signals.snapshot())
    }

    @Test fun publicationCopiesInputsAndPreviouslyReturnedHistoriesStayUnchanged() {
        val appends = signals.appendPublications()
        val settlements = signals.settlementPublications()
        val commits = signals.settlementCommits()
        val keys = mutableListOf(fingerprint.path, "$id/second")
        val expected = keys.toList()
        signals.noteAppend(keys)
        signals.noteSettlement(generation, id.leastSignificantBits, keys)
        val savedAppends = signals.appendPublications()
        val savedSettlements = signals.settlementPublications()
        keys.clear()
        signals.noteAppend(emptyList())
        signals.noteSettlement(generation, id.leastSignificantBits + 1, emptyList())
        assertEquals(appends + listOf(expected), savedAppends)
        assertEquals(settlements + listOf(expected), savedSettlements)
        assertEquals(appends + listOf(expected, emptyList()), signals.appendPublications())
        assertEquals(settlements + listOf(expected, emptyList()), signals.settlementPublications())
        assertEquals(commits + 2, signals.settlementCommits())
        val before = signals.snapshot()
        val index = LibraryIndex(generation = generation, loaded = true, foundCount = 7)
        signals.publish(index)
        assertEquals(before.copy(index = index), signals.snapshot())
    }

    @Test fun workerHistoryAndMaximumSurviveReconnectRecycleAndIdleWithoutMutatingSnapshots() {
        val before = signals.snapshot()
        val pid = id.hashCode()
        val first = before.copy(workerPid = pid, workerInstance = generation,
            workerPids = before.workerPids + pid, workerInstances = before.workerInstances + generation,
            workerInstancePids = before.workerInstancePids + (generation to pid),
            workerBaselines = before.workerBaselines + (generation to 123L),
            maxActiveMetadataDocuments = before.maxActiveMetadataDocuments + 2, inFlight = fingerprint)
        signals.noteDispatch(fingerprint)
        signals.workerConnected(pid, generation, 123, first.maxActiveMetadataDocuments)
        val saved = signals.snapshot()
        assertEquals(first, saved)
        val second = first.copy(workerPid = pid + 1, workerInstance = generation + 1,
            workerPids = first.workerPids + (pid + 1), workerInstances = first.workerInstances + (generation + 1),
            workerInstancePids = first.workerInstancePids + (generation + 1 to pid + 1),
            workerBaselines = first.workerBaselines + (generation + 1 to 456L))
        signals.workerConnected(pid + 1, generation + 1, 456, first.maxActiveMetadataDocuments - 1)
        assertEquals(second, signals.snapshot())
        signals.noteRecycle(id.toString())
        val recycled = second.copy(intentionalRecycles = second.intentionalRecycles + 1, lastRecycleReason = id.toString())
        assertEquals(recycled, signals.snapshot())
        signals.workerIdle()
        assertEquals(recycled.copy(inFlight = null), signals.snapshot())
        assertEquals(first, saved)
    }

    @Test fun memoryRecordsRealAndEffectiveSamplesButPreDispatchOnlyUpdatesRealFields() {
        val before = signals.snapshot()
        val real = MetadataMemorySample(1_000, 100, 2_000, false, 10, 20)
        val effective = real.copy(lowMemory = true, nativePssBytes = 200)
        val decision = MetadataHeadroomPolicy().evaluate(effective)
        signals.logMemory(generation, fingerprint, id.hashCode(), real, effective, decision)
        val recorded = before.copy(realMemorySamples = before.realMemorySamples + 1,
            effectiveMemorySamples = before.effectiveMemorySamples + 1,
            lastRealMemorySample = real, lastEffectiveMemorySample = effective)
        val saved = signals.snapshot()
        assertEquals(recorded, saved)
        val nextReal = real.copy(availableBytes = 900)
        signals.logPreDispatchMemory(generation, id.hashCode(), nextReal, decision)
        assertEquals(recorded.copy(realMemorySamples = recorded.realMemorySamples + 1,
            lastRealMemorySample = nextReal), signals.snapshot())
        assertEquals(recorded, saved)
    }

    private fun counts(value: SourceFingerprint) = listOf(
        signals.dispatchCount(value), signals.workerDeathCount(value), signals.workerRetryCount(value),
    )
}
