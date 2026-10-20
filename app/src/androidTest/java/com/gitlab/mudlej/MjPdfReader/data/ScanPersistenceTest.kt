package com.gitlab.mudlej.MjPdfReader.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataFailure
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataState
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: PdfRepository
    private val entry = ScannedPdfEntry("/books/a.pdf", 100, 2_000, "stored")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repository = PdfRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun discoveryReplacesByPathAndQueriesReturnPathOrder() = runBlocking {
        val old = entry.copy(
            size = 50,
            lastModified = 1_000,
            hash = "old",
            pageCount = 12,
            metadataState = ScannedPdfMetadataState.FAILED,
            metadataFailure = ScannedPdfMetadataFailure.IO_ERROR,
            metadataWorkerDeaths = 2,
        )
        val complete = entry.copy(
            path = "/books/b.pdf",
            pageCount = 3,
            metadataState = ScannedPdfMetadataState.COMPLETE,
        )
        val failed = entry.copy(
            path = "/books/c.pdf",
            hash = null,
            metadataState = ScannedPdfMetadataState.FAILED,
            metadataFailure = ScannedPdfMetadataFailure.SOURCE_MISSING,
        )
        val pending = entry.copy(path = "/books/z.pdf")
        repository.persistDiscoveredPdfs(listOf(pending, failed, complete, old))

        repository.persistDiscoveredPdfs(listOf(entry))

        assertEquals(listOf(entry, complete, failed, pending), repository.findAllScannedPdfs())
        assertEquals(listOf(entry, pending), repository.findPendingScannedPdfs())
        assertEquals(listOf(entry, complete, pending), repository.findScannedPdfsByHash("stored"))
        assertTrue(repository.findScannedPdfsByHash("old").isEmpty())
    }

    @Test
    fun staleAndUnnormalizedFingerprintsCannotMutateRows() = runBlocking {
        repository.persistDiscoveredPdfs(listOf(entry))
        val stale = listOf(
            entry.fingerprint.copy(path = "/books/missing.pdf"),
            entry.fingerprint.copy(size = 101),
            entry.fingerprint.copy(lastModified = 3_000),
            SourceFingerprint(entry.path, entry.size, 2_999),
        )

        for (fingerprint in stale) {
            assertFalse(fingerprint.toString(), repository.completeScannedPdfMetadata(fingerprint, "new", 7))
            assertFalse(fingerprint.toString(), repository.failScannedPdfMetadata(
                fingerprint, "new", ScannedPdfMetadataFailure.INVALID_PDF,
            ))
            assertNull(fingerprint.toString(), repository.recordScannedPdfWorkerDeath(fingerprint))
            assertFalse(fingerprint.toString(), repository.updateScannedPdfHash(fingerprint, "new"))
            assertEquals(listOf(entry), repository.findAllScannedPdfs())
        }
    }

    @Test
    fun terminalRowsRejectMetadataResultsAndWorkerDeaths() = runBlocking {
        val complete = entry.copy(pageCount = 8, metadataState = ScannedPdfMetadataState.COMPLETE)
        val failed = entry.copy(
            path = "/books/b.pdf",
            metadataState = ScannedPdfMetadataState.FAILED,
            metadataFailure = ScannedPdfMetadataFailure.WORKER_CRASH,
            metadataWorkerDeaths = 2,
        )
        val rows = listOf(complete, failed)
        repository.persistDiscoveredPdfs(rows)

        for (row in rows) {
            assertFalse(repository.completeScannedPdfMetadata(row.fingerprint, "new", 17))
            assertFalse(repository.failScannedPdfMetadata(
                row.fingerprint, "new", ScannedPdfMetadataFailure.INVALID_PDF,
            ))
            assertNull(repository.recordScannedPdfWorkerDeath(row.fingerprint))
        }

        assertEquals(rows, repository.findAllScannedPdfs())
        assertTrue(repository.findPendingScannedPdfs().isEmpty())
    }

    @Test
    fun completeAndFailedMetadataKeepTheirDistinctNullHashSemantics() = runBlocking {
        val pending = entry.copy(
            pageCount = 9,
            metadataFailure = ScannedPdfMetadataFailure.IO_ERROR,
            metadataWorkerDeaths = 1,
        )
        val other = pending.copy(path = "/books/b.pdf")
        repository.persistDiscoveredPdfs(listOf(pending, other))

        assertTrue(repository.completeScannedPdfMetadata(pending.fingerprint, null, 17))
        assertTrue(repository.failScannedPdfMetadata(
            other.fingerprint, null, ScannedPdfMetadataFailure.PASSWORD_REQUIRED,
        ))

        assertEquals(
            listOf(
                pending.copy(
                    hash = null,
                    pageCount = 17,
                    metadataState = ScannedPdfMetadataState.COMPLETE,
                    metadataFailure = null,
                ),
                other.copy(
                    pageCount = 0,
                    metadataState = ScannedPdfMetadataState.FAILED,
                    metadataFailure = ScannedPdfMetadataFailure.PASSWORD_REQUIRED,
                ),
            ),
            repository.findAllScannedPdfs(),
        )
        assertTrue(repository.findPendingScannedPdfs().isEmpty())
    }

    @Test
    fun workerDeathReturnsCommittedRetryThenPermanentFailure() = runBlocking {
        val worker = entry.copy(
            path = "/books/z.pdf",
            pageCount = 9,
            metadataFailure = ScannedPdfMetadataFailure.IO_ERROR,
        )
        repository.persistDiscoveredPdfs(listOf(worker, entry))
        val retry = worker.copy(pageCount = 0, metadataFailure = null, metadataWorkerDeaths = 1)

        assertEquals(retry, repository.recordScannedPdfWorkerDeath(worker.fingerprint))
        assertEquals(listOf(entry, retry), repository.findAllScannedPdfs())
        assertEquals(listOf(entry, retry), repository.findPendingScannedPdfs())

        val failed = retry.copy(
            metadataState = ScannedPdfMetadataState.FAILED,
            metadataFailure = ScannedPdfMetadataFailure.WORKER_CRASH,
            metadataWorkerDeaths = 2,
        )
        assertEquals(failed, repository.recordScannedPdfWorkerDeath(worker.fingerprint))
        assertEquals(listOf(entry, failed), repository.findAllScannedPdfs())
        assertNull(repository.recordScannedPdfWorkerDeath(worker.fingerprint))
        assertEquals(listOf(entry, failed), repository.findAllScannedPdfs())
        assertEquals(listOf(entry), repository.findPendingScannedPdfs())
    }

    @Test
    fun hashUpdatesPreserveMetadataForPendingAndTerminalRows() = runBlocking {
        val pending = entry.copy(
            pageCount = 9,
            metadataFailure = ScannedPdfMetadataFailure.IO_ERROR,
            metadataWorkerDeaths = 1,
        )
        val complete = pending.copy(
            path = "/books/b.pdf",
            pageCount = 17,
            metadataState = ScannedPdfMetadataState.COMPLETE,
            metadataFailure = null,
        )
        val failed = pending.copy(
            path = "/books/c.pdf",
            pageCount = 0,
            metadataState = ScannedPdfMetadataState.FAILED,
            metadataFailure = ScannedPdfMetadataFailure.WORKER_CRASH,
            metadataWorkerDeaths = 2,
        )
        val rows = listOf(pending, complete, failed)
        repository.persistDiscoveredPdfs(rows)

        for (row in rows) {
            assertTrue(repository.updateScannedPdfHash(row.fingerprint, "new"))
        }

        val expected = listOf(pending.copy(hash = "new"), complete.copy(hash = "new"), failed.copy(hash = "new"))
        assertEquals(expected, repository.findAllScannedPdfs())
        assertEquals(expected, repository.findScannedPdfsByHash("new"))
        assertEquals(listOf(pending.copy(hash = "new")), repository.findPendingScannedPdfs())
        assertTrue(repository.findScannedPdfsByHash("stored").isEmpty())
    }

    @Test
    fun renameAndPrunePreserveOtherRowsAndPropagatePathConflicts() = runBlocking {
        val destination = entry.copy(
            path = "/books/b.pdf",
            hash = "other",
            pageCount = 6,
            metadataState = ScannedPdfMetadataState.COMPLETE,
        )
        repository.persistDiscoveredPdfs(listOf(entry, destination))

        val failure = runCatching {
            repository.renameScannedPdfThrowing(entry.path, destination.path)
        }.exceptionOrNull()
        assertTrue("Expected a path constraint failure, got $failure", failure is SQLiteConstraintException)
        assertEquals(listOf(entry, destination), repository.findAllScannedPdfs())
        assertFalse(repository.renameScannedPdfThrowing("/books/missing.pdf", "/books/z.pdf"))

        val renamed = entry.copy(path = "/books/z.pdf")
        assertTrue(repository.renameScannedPdfThrowing(entry.path, renamed.path))
        assertEquals(listOf(destination, renamed), repository.findAllScannedPdfs())

        repository.pruneScannedPdfsThrowing(listOf(destination.path, "/books/missing.pdf"))
        assertEquals(listOf(renamed), repository.findAllScannedPdfs())
        repository.pruneScannedPdfsThrowing(emptyList())
        assertEquals(listOf(renamed), repository.findAllScannedPdfs())
        repository.pruneScannedPdfsThrowing(listOf(renamed.path))
        assertTrue(repository.findAllScannedPdfs().isEmpty())
    }

    @Test
    fun discoveryBatchRollsBackAndPropagatesInsertFailure() = runBlocking {
        repository.persistDiscoveredPdfs(listOf(entry))
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER reject_second_discovery BEFORE INSERT ON ScannedPdfCache
                WHEN NEW.path = '/books/c.pdf'
                BEGIN
                    SELECT RAISE(ABORT, 'injected discovery failure');
                END
                """.trimIndent()
            )
        }

        val failure = runCatching {
            repository.persistDiscoveredPdfs(listOf(
                entry.copy(path = "/books/b.pdf"),
                entry.copy(path = "/books/c.pdf"),
            ))
        }.exceptionOrNull()

        assertTrue("Expected the trigger failure, got $failure", failure is SQLiteConstraintException)
        assertTrue(failure?.message.orEmpty().contains("injected discovery failure"))
        assertEquals(listOf(entry), repository.findAllScannedPdfs())
    }

    @Test
    fun cancelledCallerCannotWriteScannedPdfs() = runBlocking {
        repository.persistDiscoveredPdfs(listOf(entry))
        val writes = listOf<suspend () -> Unit>(
            { repository.persistDiscoveredPdfs(listOf(entry.copy(size = 200))) },
            { repository.completeScannedPdfMetadata(entry.fingerprint, "new", 17) },
            { repository.failScannedPdfMetadata(entry.fingerprint, "new", ScannedPdfMetadataFailure.IO_ERROR) },
            { repository.recordScannedPdfWorkerDeath(entry.fingerprint) },
            { repository.updateScannedPdfHash(entry.fingerprint, "new") },
            { repository.pruneScannedPdfsThrowing(listOf(entry.path)) },
            { repository.renameScannedPdfThrowing(entry.path, "/books/z.pdf") },
        )

        for ((index, write) in writes.withIndex()) {
            val job = Job()
            var failure: Throwable? = null
            runCatching {
                runBlocking(job) {
                    job.cancel()
                    failure = runCatching { write() }.exceptionOrNull()
                }
            }
            assertTrue("Write $index must propagate cancellation, got $failure", failure is CancellationException)
            assertEquals("Write $index must not change rows", listOf(entry), repository.findAllScannedPdfs())
        }
    }
}
