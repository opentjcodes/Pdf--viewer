// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPublicationTest {
    private val work = ScanWorkIndex()
    private val publication = ScanPublication(work)

    @Test fun scanStateAdvancesWithoutAHomeCollector() = runBlocking {
        withTimeout(250) {
            publication.publish(ScanPhase.FINDING, loaded = true)
            publication.publish(ScanPhase.IDLE)
        }
        assertEquals(ScanPhase.IDLE, publication.index.value.phase)
        assertTrue(publication.index.value.loaded)
    }

    @Test fun slowCollectorCannotDelayBackendCompletion() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
            publication.index.collect { release.await() }
        }
        try {
            withTimeout(250) {
                publication.publish(ScanPhase.FINDING, loaded = true, showProgress = true)
                repeat(1_000) { publication.publish(ScanPhase.READING_DETAILS) }
                publication.publish(ScanPhase.IDLE, showProgress = false)
            }
            assertEquals(ScanPhase.IDLE, publication.index.value.phase)
            assertEquals(1_002L, publication.index.value.revision)
            assertFalse(publication.index.value.showProgress)
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test fun stateIsBroadcastAndLateCollectorsImmediatelyReceiveTheLatestSnapshot() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            publication.index.first { it.loaded }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            publication.index.first { it.loaded }
        }
        work.advanceGeneration(10)
        publication.publish(ScanPhase.IDLE, loaded = true)

        withTimeout(250) {
            assertEquals(publication.index.value, first.await())
            assertEquals(publication.index.value, second.await())
            assertEquals(publication.index.value, publication.index.first())
        }
    }

    @Test fun revisionsAdvanceEvenWhenThePublishedDataIsOtherwiseUnchanged() {
        publication.publish(ScanPhase.FINDING, loaded = true, showProgress = true)
        val before = publication.index.value
        publication.publish(ScanPhase.FINDING)

        assertEquals(before.copy(revision = before.revision + 1), publication.index.value)
        assertTrue(publication.index.value.showProgress)
    }

    @Test fun terminalSnapshotsKeepCachedEntriesWithoutMutatingEarlierSnapshots() {
        val first = ScannedPdfEntry("/books/a.pdf", 100, 1_000, null)
        val roots = mutableListOf("/books")
        work.restore(listOf(first), roots)
        publication.publish(ScanPhase.FINDING, loaded = true)
        val before = publication.index.value
        roots.clear()
        work.restore(listOf(first.copy(hash = "computed")), null)
        publication.publish(ScanPhase.INCOMPLETE, showProgress = false)

        assertEquals(listOf(first), before.entries)
        assertEquals(listOf("/books"), before.scopeRoots)
        assertEquals("computed", publication.index.value.entries.single().hash)
        assertFalse(publication.index.value.active)
        assertTrue(publication.index.value.loaded)
    }
}
