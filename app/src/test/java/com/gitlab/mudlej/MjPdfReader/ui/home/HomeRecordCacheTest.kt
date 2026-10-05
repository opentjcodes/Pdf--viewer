// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataState
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanPhase
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeRecordCacheTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun completedMoveRechecksTheOldFileAndContentButRetainsUnrelatedFileProbes() = runBlocking {
        val original = files.newFile("original.pdf")
        val unrelated = files.newFile("unrelated.pdf")
        val oldUri = original.toURI().toString()
        val unrelatedUri = unrelated.toURI().toString()
        val contentUri = "content://books/1"
        val cache = HomeRecordCache()
        cache.updateRecords(listOf(
            HomeRecordCache.Identity("moving", oldUri, null),
            HomeRecordCache.Identity("unrelated", unrelatedUri, null),
            HomeRecordCache.Identity("provider", contentUri, null),
        ))
        val originalEntry = ScannedPdfEntry(original.path, original.length(), original.lastModified(), "moving")
        val unrelatedEntry = ScannedPdfEntry(unrelated.path, unrelated.length(), unrelated.lastModified(), "unrelated")
        val initial = LibraryIndex(entries = listOf(originalEntry, unrelatedEntry), loaded = true, generation = 1)
        cache.onLibraryIndex(initial)
        val probes = mutableMapOf<String, Int>()
        val probe: suspend (String) -> Availability = { uri ->
            probes[uri] = probes.getOrDefault(uri, 0) + 1
            if (uri == contentUri || File(URI(uri)).canRead()) Availability.AVAILABLE else Availability.MISSING
        }
        assertEquals(Availability.AVAILABLE, cache.resolve(probe)?.availability?.get(oldUri))

        val moved = File(files.root, "moved.pdf")
        assertTrue(original.renameTo(moved))
        assertFalse(cache.onLibraryIndex(initial.copy(generation = 2, phase = ScanPhase.FINDING)))
        val completed = initial.copy(generation = 2, entries = listOf(originalEntry.copy(path = moved.path), unrelatedEntry))
        assertTrue(cache.onLibraryIndex(completed))
        assertEquals(Availability.MISSING, cache.resolve(probe)?.availability?.get(oldUri))
        assertEquals(mapOf(oldUri to 2, unrelatedUri to 1, contentUri to 2), probes)

        val metadataOnly = completed.copy(revision = 10, entries = completed.entries.map {
            it.copy(pageCount = 15, metadataState = ScannedPdfMetadataState.COMPLETE)
        })
        assertFalse(cache.onLibraryIndex(metadataOnly))
        assertFalse(cache.onLibraryIndex(metadataOnly.copy(generation = 3)))
        cache.resolve(probe)
        assertEquals(mapOf(oldUri to 2, unrelatedUri to 1, contentUri to 2), probes)
    }

    @Test fun activeFileRemovalInvalidatesItsUriAndDefersContentRecheckUntilCompletion() = runBlocking {
        val file = files.newFile("removed.pdf")
        val fileUri = file.toURI().toString()
        val contentUri = "content://books/1"
        val cache = HomeRecordCache()
        cache.updateRecords(listOf(
            HomeRecordCache.Identity("removed", fileUri, null),
            HomeRecordCache.Identity("provider", contentUri, null),
        ))
        val initial = LibraryIndex(
            entries = listOf(ScannedPdfEntry(file.path, 0, file.lastModified(), "removed")),
            loaded = true, generation = 1,
        )
        cache.onLibraryIndex(initial)
        cache.resolve { Availability.AVAILABLE }
        assertTrue(file.delete())
        val removed = initial.copy(entries = emptyList(), phase = ScanPhase.READING_DETAILS)
        assertTrue(cache.onLibraryIndex(removed))
        val probes = mutableListOf<String>()
        val snapshot = cache.resolve {
            probes += it
            if (File(URI(it)).canRead()) Availability.AVAILABLE else Availability.MISSING
        }
        assertEquals(listOf(fileUri), probes)
        assertEquals(Availability.MISSING, snapshot?.availability?.get(fileUri))
        assertTrue(cache.onLibraryIndex(removed.copy(phase = ScanPhase.IDLE)))
        cache.resolve { probes += it; Availability.AVAILABLE }
        assertEquals(listOf(fileUri, contentUri), probes)
        assertFalse(cache.onLibraryIndex(removed.copy(phase = ScanPhase.IDLE, revision = 12)))
    }

    @Test fun sourceChangeRejectsInFlightResultsButMetadataAndIdenticalGenerationsDoNot() = runBlocking {
        val file = files.newFile("changed.pdf")
        val uri = file.toURI().toString()
        val cache = HomeRecordCache()
        cache.updateRecords(listOf(HomeRecordCache.Identity("changed", uri, null)))
        val entry = ScannedPdfEntry(file.path, 0, 1_000, null)
        val initial = LibraryIndex(entries = listOf(entry), loaded = true, generation = 1)
        cache.onLibraryIndex(initial)
        assertNotNull(cache.resolve {
            assertFalse(cache.onLibraryIndex(initial.copy(generation = 2, entries = listOf(
                entry.copy(hash = "known", pageCount = 7, lastModified = 1_999),
            ))))
            Availability.AVAILABLE
        })
        cache.invalidate()
        assertNull(cache.resolve {
            assertTrue(cache.onLibraryIndex(initial.copy(generation = 3, entries = listOf(entry.copy(size = 20)))))
            Availability.AVAILABLE
        })
        var probes = 0
        cache.resolve { probes++; Availability.AVAILABLE }
        assertEquals(1, probes)
    }

    @Test fun unchangedRecordIdentitiesReuseAvailabilityUntilExplicitInvalidation() = runBlocking {
        val cache = HomeRecordCache()
        val identities = listOf(HomeRecordCache.Identity("book", "content://books/1", null))
        var probes = 0
        cache.updateRecords(identities)
        val first = cache.resolve { probes++; Availability.AVAILABLE }
        cache.updateRecords(identities.toList())
        assertEquals(first, cache.resolve { probes++; Availability.MISSING })
        assertEquals(1, probes)

        cache.invalidate()
        val refreshed = cache.resolve { probes++; Availability.MISSING }
        assertNotNull(refreshed)
        assertEquals(Availability.MISSING, refreshed?.availability?.get("content://books/1"))
        assertEquals(2, probes)
    }

    @Test fun identityChangesProbeOnlyAffectedUrisAndForgetRemovedUris() = runBlocking {
        val cache = HomeRecordCache()
        val first = HomeRecordCache.Identity("first", "content://books/1", null)
        val second = HomeRecordCache.Identity("second", "content://books/2", null)
        cache.updateRecords(listOf(first, second))
        cache.resolve { Availability.AVAILABLE }
        val probes = mutableListOf<String>()
        cache.updateRecords(listOf(first, second.copy(uri = "content://books/3")))
        val changed = cache.resolve { probes += it; Availability.MISSING }
        assertEquals(listOf("content://books/3"), probes)
        assertEquals(
            mapOf("content://books/1" to Availability.AVAILABLE, "content://books/3" to Availability.MISSING),
            changed?.availability,
        )

        probes.clear()
        cache.updateRecords(listOf(first.copy(sourceUri = "content://source/1")))
        cache.resolve { probes += it; Availability.AVAILABLE }
        assertEquals(listOf("content://books/1"), probes)
    }

    @Test fun rekeyingARecordRetainsItsUriAvailability() = runBlocking {
        val cache = HomeRecordCache()
        val identity = HomeRecordCache.Identity("legacy", "content://books/1", null)
        cache.updateRecords(listOf(identity))
        cache.resolve { Availability.AVAILABLE }
        cache.updateRecords(listOf(identity.copy(hash = "current")))
        val rekeyed = cache.resolve { error("unchanged URI must not be reopened") }
        assertEquals(mapOf("content://books/1" to Availability.AVAILABLE), rekeyed?.availability)
    }

    @Test fun accessEpochChangeRejectsAnInFlightProviderResult() = runBlocking {
        val cache = HomeRecordCache()
        cache.updateRecords(listOf(HomeRecordCache.Identity("book", "content://books/1", null)))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            cache.resolve {
                started.complete(Unit)
                release.await()
                Availability.AVAILABLE
            }
        }
        started.await()
        cache.invalidate()
        release.complete(Unit)
        assertNull(old.await())
        assertEquals(
            Availability.ACCESS_UNKNOWN,
            cache.resolve { Availability.ACCESS_UNKNOWN }?.availability?.get("content://books/1"),
        )
    }

    @Test fun recordReplacementRejectsAnInFlightProviderResult() = runBlocking {
        val cache = HomeRecordCache()
        val oldIdentity = HomeRecordCache.Identity("book", "content://books/old", null)
        cache.updateRecords(listOf(oldIdentity))
        assertNull(cache.resolve {
            cache.updateRecords(listOf(oldIdentity.copy(uri = "content://books/new")))
            Availability.AVAILABLE
        })
        val probes = mutableListOf<String>()
        val current = cache.resolve { probes += it; Availability.MISSING }
        assertEquals(listOf("content://books/new"), probes)
        assertEquals(mapOf("content://books/new" to Availability.MISSING), current?.availability)
    }

    @Test fun failedProbeDoesNotPoisonTheNextRecordSnapshot() = runBlocking {
        val cache = HomeRecordCache()
        cache.updateRecords(listOf(HomeRecordCache.Identity("book", "content://books/1", null)))
        val failed = runCatching { cache.resolve { error("provider failed") } }
        assertEquals("provider failed", failed.exceptionOrNull()?.message)
        assertNotNull(cache.resolve { Availability.AVAILABLE })
    }

    @Test fun sharedUrisUseOneProbeAndPublishedSnapshotsDoNotChange() = runBlocking {
        val cache = HomeRecordCache()
        cache.updateRecords(listOf(
            HomeRecordCache.Identity("first", "content://books/1", null),
            HomeRecordCache.Identity("second", "content://books/1", null),
        ))
        var probes = 0
        val first = cache.resolve { probes++; Availability.AVAILABLE }
        assertEquals(1, probes)
        cache.invalidate()
        cache.resolve { Availability.MISSING }
        cache.updateRecords(emptyList())
        assertEquals(emptyMap<String, Availability>(), cache.resolve { error("empty records") }?.availability)
        assertEquals(mapOf("content://books/1" to Availability.AVAILABLE), first?.availability)
    }
}
