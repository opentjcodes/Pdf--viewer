// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataFailure
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.MetadataResult
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCallbackTest {
    private var current = true
    private var acceptanceChecks = 0
    private val events = mutableListOf<ScanEvent>()
    private val callback = MetadataCallback(
        isCurrent = {
            acceptanceChecks++
            current
        },
        emit = { events.add(it) },
    )

    @Test fun readyPreservesEveryField() {
        callback.onReady(101, 102, 103, 104, 105, 106, 107, 108, 109, true)

        assertEquals(
            listOf(
                ScanEvent.WorkerConnected(
                    generation = 101,
                    connectionToken = 102,
                    pid = 103,
                    workerInstance = 104,
                    sample = MetadataMemorySample(
                        availableBytes = 107,
                        thresholdBytes = 108,
                        totalBytes = 109,
                        lowMemory = true,
                        baselineNativePssBytes = 105,
                        nativePssBytes = 106,
                    ),
                )
            ),
            events,
        )
        assertEquals(1, acceptanceChecks)
    }

    @Test fun memorySamplePreservesEveryField() {
        callback.onMemorySample(201, 202, 203, 204, 205, 206, 207, 208, 209, false)

        assertEquals(
            listOf(
                ScanEvent.WorkerMemorySample(
                    generation = 201,
                    connectionToken = 202,
                    pid = 203,
                    workerInstance = 204,
                    sample = MetadataMemorySample(
                        availableBytes = 207,
                        thresholdBytes = 208,
                        totalBytes = 209,
                        lowMemory = false,
                        baselineNativePssBytes = 205,
                        nativePssBytes = 206,
                    ),
                )
            ),
            events,
        )
        assertEquals(1, acceptanceChecks)
    }

    @Test fun resultPreservesEveryField() {
        sendResult()

        assertEquals(
            listOf(
                ScanEvent.WorkerResult(
                    MetadataResult(
                        generation = 301,
                        connectionToken = 302,
                        fingerprint = SourceFingerprint("/books/metadata.pdf", 303, 304),
                        hash = "distinct-hash",
                        pageCount = 305,
                        failure = null,
                        sourceChanged = false,
                        sample = MetadataMemorySample(
                            availableBytes = 311,
                            thresholdBytes = 312,
                            totalBytes = 313,
                            lowMemory = true,
                            baselineNativePssBytes = 308,
                            nativePssBytes = 310,
                        ),
                        workerPid = 306,
                        workerInstance = 307,
                        maxActiveDocuments = 309,
                    )
                )
            ),
            events,
        )
        assertEquals(1, acceptanceChecks)
    }

    @Test fun acknowledgementPreservesCallbackIdentity() {
        callback.onAcknowledged(401, 402)

        assertEquals(listOf(ScanEvent.WorkerAcknowledged(401, 402)), events)
        assertEquals(1, acceptanceChecks)
    }

    @Test fun staleCallbacksEmitNoEvents() {
        current = false

        callback.onReady(101, 102, 103, 104, 105, 106, 107, 108, 109, true)
        callback.onMemorySample(201, 202, 203, 204, 205, 206, 207, 208, 209, false)
        sendResult(failureCode = ScannedPdfMetadataFailure.INVALID_PDF.name)
        callback.onAcknowledged(401, 402)

        assertTrue(events.isEmpty())
        assertEquals(4, acceptanceChecks)
    }

    @Test fun everyCallbackObservesPredicateUpdates() {
        for (accepted in listOf(false, true, false, true)) {
            current = accepted
            val previousCount = events.size

            callback.onReady(101, 102, 103, 104, 105, 106, 107, 108, 109, true)
            callback.onMemorySample(201, 202, 203, 204, 205, 206, 207, 208, 209, false)
            sendResult()
            callback.onAcknowledged(401, 402)

            assertEquals(previousCount + if (accepted) 4 else 0, events.size)
        }
        assertEquals(16, acceptanceChecks)
    }

    @Test fun nullAndBlankFailureCodesMeanSuccess() {
        for (failureCode in listOf(null, MetadataProtocol.FAILURE_NONE, " \t\n")) {
            sendResult(failureCode = failureCode, hash = null)

            val result = (events.last() as ScanEvent.WorkerResult).result
            assertNull(result.failure)
            assertFalse(result.sourceChanged)
            assertNull(result.hash)
        }
        assertEquals(3, events.size)
    }

    @Test fun sourceChangedIsNotAMetadataFailure() {
        sendResult(failureCode = MetadataProtocol.FAILURE_SOURCE_CHANGED)

        val result = (events.single() as ScanEvent.WorkerResult).result
        assertNull(result.failure)
        assertTrue(result.sourceChanged)
    }

    @Test fun knownFailureCodesPreserveTheirEnum() {
        for (failure in ScannedPdfMetadataFailure.values()) {
            sendResult(failureCode = failure.name)

            val result = (events.last() as ScanEvent.WorkerResult).result
            assertEquals(failure, result.failure)
            assertFalse(result.sourceChanged)
        }
        assertEquals(ScannedPdfMetadataFailure.values().size, events.size)
    }

    @Test fun unknownFailureCodesBecomeInternalError() {
        for (failureCode in listOf("UNKNOWN_FAILURE", "invalid_pdf", " INVALID_PDF ")) {
            sendResult(failureCode = failureCode)

            val result = (events.last() as ScanEvent.WorkerResult).result
            assertEquals(ScannedPdfMetadataFailure.INTERNAL_ERROR, result.failure)
            assertFalse(result.sourceChanged)
        }
        assertEquals(3, events.size)
    }

    private fun sendResult(
        failureCode: String? = null,
        hash: String? = "distinct-hash",
    ) {
        callback.onResult(
            generation = 301,
            connectionToken = 302,
            path = "/books/metadata.pdf",
            size = 303,
            lastModified = 304,
            hash = hash,
            pageCount = 305,
            failureCode = failureCode,
            workerPid = 306,
            workerInstance = 307,
            baselineNativePssBytes = 308,
            maxActiveDocuments = 309,
            nativePssBytes = 310,
            availableBytes = 311,
            thresholdBytes = 312,
            totalBytes = 313,
            lowMemory = true,
        )
    }
}
