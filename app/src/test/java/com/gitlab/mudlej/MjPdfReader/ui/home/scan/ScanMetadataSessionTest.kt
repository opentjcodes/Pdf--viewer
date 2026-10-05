// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import com.gitlab.mudlej.MjPdfReader.core.debug.MetadataWorkerTestHooks
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase_Impl
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.IPdfMetadataCallback
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.IPdfMetadataService
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataProtocol
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.PdfMetadataClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanMetadataSessionTest {
    private val work = ScanWorkIndex().also {
        it.advanceGeneration(10)
        it.restore(emptyList(), null)
    }
    private val publication = ScanPublication(work)
    private val first = ScannedPdfEntry("/books/a.pdf", 100, 1_000, null)
    private val second = ScannedPdfEntry("/books/b.pdf", 200, 2_000, null)
    private val memory = MetadataMemorySample(1_000_000_000, 100_000_000, 2_000_000_000, false, 10, 10)
    private val events = mutableListOf<ScanEvent>()
    private val posted = Channel<ScanEvent>(Channel.UNLIMITED)
    private val post: (ScanEvent) -> Unit = {
        events += it
        posted.trySend(it).getOrThrow()
    }
    private val worker = RecordingMetadataService()
    private var bindingAllowed = true
    private var bindAttempts = 0
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindAttempts++
            if (!bindingAllowed) return false
            connection.onServiceConnected(ComponentName("test", "metadata"), worker)
            return true
        }

        override fun unbindService(connection: ServiceConnection) = Unit
    }

    @Before @After fun resetHooks() = MetadataWorkerTestHooks.reset()

    @Test fun ensuringAnEmptyQueuePostsCompletionWithTheCurrentGeneration() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val session = session(this) { completed.complete(Unit) }

        session.ensureWorker()

        assertFalse(completed.isCompleted)
        work.advanceGeneration(11)
        assertEquals(listOf(ScanEvent.CheckCompletion(10)), events)
        assertTrue(worker.processCalls.isEmpty())
    }

    @Test fun readyWorkerWithNoCandidateAlsoPostsCompletion() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val session = session(this) { completed.complete(Unit) }
        work.rebuildPending(listOf(first))
        session.ensureWorker()
        work.clearPending()

        session.workerConnected(ScanEvent.WorkerConnected(10, 0, 21, 22, memory))

        assertFalse(completed.isCompleted)
        assertEquals(listOf(ScanEvent.CheckCompletion(10)), events)
        assertFalse(session.blocksCompletion)
        assertTrue(worker.processCalls.isEmpty())
    }

    @Test fun connectingBlocksCompletionAndRejectsStaleConnectionsWithoutBindingAgain() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first))

        session.ensureWorker()
        session.ensureWorker()
        session.workerConnected(ScanEvent.WorkerConnected(9, 0, 21, 22, memory))
        session.workerConnected(ScanEvent.WorkerConnected(10, 1, 21, 22, memory))
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))

        assertEquals(1, bindAttempts)
        assertTrue(session.blocksCompletion)
        assertNull(work.inFlight)
        assertTrue(worker.processCalls.isEmpty())

        session.workerConnected(ScanEvent.WorkerConnected(10, 0, 21, 22, memory))

        assertFalse(session.blocksCompletion)
        assertEquals(first.fingerprint, work.inFlight)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.DEBUG_NONE), worker.processCalls)
    }

    @Test fun failedBindingLeavesTheSessionAvailableForReconnection() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first))
        bindingAllowed = false
        try {
            session.ensureWorker()

            assertFalse(session.blocksCompletion)
            assertNull(work.inFlight)
            assertTrue(work.hasPending)
            session.workerConnected(ScanEvent.WorkerConnected(10, 0, 21, 22, memory))
            assertTrue(worker.processCalls.isEmpty())

            bindingAllowed = true
            connect(session)

            assertEquals(2, bindAttempts)
            assertEquals(first.fingerprint, work.inFlight)
        } finally {
            session.cancelTimers()
        }
    }

    @Test fun unsolicitedAcknowledgementCannotReleaseAProcessingReservation() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first, second))
        connect(session)

        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))
        session.ensureWorker()

        assertEquals(first.fingerprint, work.inFlight)
        assertTrue(work.hasPending)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.DEBUG_NONE), worker.processCalls)
    }

    @Test fun acknowledgementKeepsTheReservationAndRejectsStaleIdentityBeforeDispatchingLaterWork() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first, second))
        connect(session)
        assertEquals(first.fingerprint, work.inFlight)

        session.acknowledge(result(), refreshPending = false)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_CONTINUE), worker.acknowledgements)
        work.rebuildPending(listOf(first))

        assertEquals(first.fingerprint, work.inFlight)
        assertTrue(session.blocksCompletion)
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(9, 0))
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 1))
        assertEquals(first.fingerprint, work.inFlight)
        assertTrue(session.blocksCompletion)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.DEBUG_NONE), worker.processCalls)

        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))

        assertEquals(second.fingerprint, work.inFlight)
        assertEquals(listOf(first.fingerprint, second.fingerprint), worker.processCalls.map { it.first })
        assertFalse(session.blocksCompletion)
        assertFalse(work.hasPending)
    }

    @Test fun duplicateCallbacksCannotChangeAnOutstandingAcknowledgement() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first, second))
        connect(session)
        session.acknowledge(result(), refreshPending = false)

        session.workerConnected(ScanEvent.WorkerConnected(10, 0, 21, 22, memory.copy(lowMemory = true)))
        session.workerMemorySample(ScanEvent.WorkerMemorySample(10, 0, 21, 22, memory.copy(lowMemory = true)))
        session.acknowledge(result().copy(sample = memory.copy(lowMemory = true)), refreshPending = true)
        session.ensureWorker()

        assertTrue(session.blocksCompletion)
        assertEquals(first.fingerprint, work.inFlight)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_CONTINUE), worker.acknowledgements)
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))

        assertFalse(session.blocksCompletion)
        assertEquals(second.fingerprint, work.inFlight)
        assertEquals(listOf(first.fingerprint, second.fingerprint), worker.processCalls.map { it.first })
        assertTrue(events.isEmpty())
    }

    @Test fun stopAcknowledgementRunsAndAwaitsCompletionInline() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = session(this) {
            entered.complete(Unit)
            release.await()
        }
        work.rebuildPending(listOf(first))
        connect(session)
        finishDiscovery()
        session.acknowledge(result(), refreshPending = false)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_STOP), worker.acknowledgements)

        val acknowledging = async(start = CoroutineStart.UNDISPATCHED) {
            session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))
        }

        assertTrue(entered.isCompleted)
        assertFalse(acknowledging.isCompleted)
        assertNull(work.inFlight)
        assertFalse(session.blocksCompletion)
        release.complete(Unit)
        acknowledging.await()
    }

    @Test fun finalLowMemoryResultStopsRatherThanRecyclingAndRetiresTheConnection() = runBlocking {
        var completions = 0
        val session = session(this) { completions++ }
        work.rebuildPending(listOf(first))
        connect(session)
        finishDiscovery()

        session.acknowledge(result().copy(sample = memory.copy(lowMemory = true)), refreshPending = false)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_STOP), worker.acknowledgements)
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))

        work.rebuildPending(listOf(second))
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))
        session.workerConnected(ScanEvent.WorkerConnected(10, 0, 21, 22, memory))
        session.workerMemorySample(ScanEvent.WorkerMemorySample(10, 0, 21, 22, memory))

        assertEquals(1, completions)
        assertFalse(session.blocksCompletion)
        assertNull(work.inFlight)
        assertTrue(work.hasPending)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.DEBUG_NONE), worker.processCalls)
        assertTrue(events.isEmpty())
    }

    @Test fun pendingRefreshAloneMakesAcknowledgementContinueInsteadOfStop() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val session = session(this) { completed.complete(Unit) }
        work.rebuildPending(listOf(first))
        connect(session)
        finishDiscovery()

        session.acknowledge(result(), refreshPending = true)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_CONTINUE), worker.acknowledgements)
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))

        assertFalse(completed.isCompleted)
        assertNull(work.inFlight)
        assertFalse(session.blocksCompletion)
        assertEquals(listOf(ScanEvent.CheckCompletion(10)), events)
    }

    @Test fun lowMemoryWithPendingRefreshRecyclesInsteadOfContinuing() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = session(this) {
            entered.complete(Unit)
            release.await()
        }
        work.rebuildPending(listOf(first))
        connect(session)
        finishDiscovery()
        session.acknowledge(result().copy(sample = memory.copy(lowMemory = true)), refreshPending = true)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_RECYCLE), worker.acknowledgements)

        val acknowledging = async(start = CoroutineStart.UNDISPATCHED) {
            session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))
        }

        assertTrue(entered.isCompleted)
        assertFalse(acknowledging.isCompleted)
        release.complete(Unit)
        acknowledging.await()
    }

    @Test fun memoryPauseRejectsStaleSamplesAndDispatchesOnlyAfterAHealthyCurrentSample() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first))
        try {
            connect(session, memory.copy(lowMemory = true))
            session.cancelTimers()
            assertTrue(session.blocksCompletion)
            assertNull(work.inFlight)
            assertTrue(worker.processCalls.isEmpty())

            session.workerMemorySample(ScanEvent.WorkerMemorySample(9, 0, 21, 22, memory))
            session.workerMemorySample(ScanEvent.WorkerMemorySample(10, 1, 21, 22, memory))
            assertTrue(session.blocksCompletion)
            assertNull(work.inFlight)
            assertTrue(worker.processCalls.isEmpty())

            session.memoryResampleDeadline(ScanEvent.MemoryResampleDeadline(9, 0))
            session.memoryResampleDeadline(ScanEvent.MemoryResampleDeadline(10, 1))
            assertTrue(worker.memoryRequests.isEmpty())
            session.memoryResampleDeadline(ScanEvent.MemoryResampleDeadline(10, 0))
            assertEquals(listOf(10L to 0L), worker.memoryRequests)

            session.workerMemorySample(ScanEvent.WorkerMemorySample(10, 0, 21, 22, memory))
            session.memoryResampleDeadline(ScanEvent.MemoryResampleDeadline(10, 0))

            assertFalse(session.blocksCompletion)
            assertEquals(first.fingerprint, work.inFlight)
            assertEquals(listOf(first.fingerprint to MetadataProtocol.DEBUG_NONE), worker.processCalls)
            assertEquals(listOf(10L to 0L), worker.memoryRequests)
            assertTrue(events.isEmpty())
        } finally {
            session.cancelTimers()
        }
    }

    @Test fun repeatedLowMemorySamplesStayPausedAndUnrelatedCallbacksCannotResumeDispatch() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first))
        try {
            connect(session, memory.copy(lowMemory = true))
            session.cancelTimers()
            session.workerMemorySample(ScanEvent.WorkerMemorySample(10, 0, 21, 22, memory.copy(lowMemory = true)))
            session.cancelTimers()
            session.workerConnected(ScanEvent.WorkerConnected(10, 0, 21, 22, memory))
            session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))
            session.ensureWorker()

            assertEquals(1, bindAttempts)
            assertTrue(session.blocksCompletion)
            assertNull(work.inFlight)
            assertTrue(worker.processCalls.isEmpty())
        } finally {
            session.cancelTimers()
        }
    }

    @Test fun memoryPauseSchedulesOneResampleWithoutDispatchingUntilMemoryRecovers() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first))
        try {
            connect(session, memory.copy(lowMemory = true))
            session.workerMemorySample(ScanEvent.WorkerMemorySample(10, 0, 21, 22, memory.copy(lowMemory = true)))

            val deadline = withTimeout(5_000) { posted.receive() }
            assertEquals(ScanEvent.MemoryResampleDeadline(10, 0), deadline)
            assertEquals(listOf(deadline), events)
            assertTrue(session.blocksCompletion)
            assertTrue(worker.processCalls.isEmpty())

            session.memoryResampleDeadline(deadline as ScanEvent.MemoryResampleDeadline)
            assertEquals(listOf(10L to 0L), worker.memoryRequests)
            session.workerMemorySample(ScanEvent.WorkerMemorySample(10, 0, 21, 22, memory))

            assertFalse(session.blocksCompletion)
            assertEquals(first.fingerprint, work.inFlight)
            assertEquals(listOf(first.fingerprint to MetadataProtocol.DEBUG_NONE), worker.processCalls)
        } finally {
            session.cancelTimers()
        }
    }

    @Test fun resettingAGenerationRetiresItsAckAndConnectsWithTheNewGeneration() = runBlocking {
        val session = session(this)
        work.rebuildPending(listOf(first))
        connect(session)
        finishDiscovery()
        session.acknowledge(result(), refreshPending = false)
        assertTrue(session.blocksCompletion)

        session.cancelTimers()
        session.resetForGeneration()
        work.advanceGeneration(11)
        work.resetScope(null)
        work.rebuildPending(listOf(second))
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))
        session.workerConnected(ScanEvent.WorkerConnected(10, 0, 21, 22, memory))

        assertFalse(session.blocksCompletion)
        assertNull(work.inFlight)
        connect(session)
        assertEquals(second.fingerprint, work.inFlight)
        assertEquals(11L to 0L, worker.registration)
        assertEquals(listOf(first.fingerprint, second.fingerprint), worker.processCalls.map { it.first })
    }

    @Test fun accessLossClearsWorkAndAckWithoutResettingDiscoveryScopeOrFailures() = runBlocking {
        val session = session(this)
        work.resetScope(listOf("/books"))
        work.rebuildPending(listOf(first, second))
        connect(session)
        work.sourceFailed(ScanEvent.DiscoverySourceFailed(10, DiscoverySource.MEDIA_STORE, Exception()))
        finishDiscovery()
        session.acknowledge(result(), refreshPending = false)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_CONTINUE), worker.acknowledgements)

        work.advanceGeneration(11)
        session.pauseForAccessLoss()

        assertEquals(11L, work.generation)
        assertNull(work.inFlight)
        assertFalse(work.hasPending)
        assertFalse(session.blocksCompletion)
        assertTrue(work.discoveryComplete)
        assertEquals(listOf("/books"), work.scopeRoots)
        assertEquals(setOf(DiscoverySource.MEDIA_STORE), work.sourceFailures)
        assertEquals(listOf(first, second), work.snapshot(ScanPhase.PAUSED, true).entries)
        assertTrue(events.isEmpty())
    }

    @Test fun expectedStopDeathBeforeAckCompletesOnceAndIgnoresLateCallbacks() = runBlocking {
        var completions = 0
        val session = session(this) { completions++ }
        work.rebuildPending(listOf(first))
        connect(session)
        finishDiscovery()
        session.acknowledge(result(), refreshPending = false)
        val repository = PdfRepository(AppDatabase_Impl())

        session.workerDied(ScanEvent.WorkerDied(10, 0, expected = true), repository)
        session.workerAcknowledged(ScanEvent.WorkerAcknowledged(10, 0))
        session.workerDied(ScanEvent.WorkerDied(10, 0, expected = true), repository)

        assertEquals(1, completions)
        assertNull(work.inFlight)
        assertFalse(session.blocksCompletion)
        assertEquals(listOf(first.fingerprint to MetadataProtocol.ACK_STOP), worker.acknowledgements)
    }

    @Test fun persistenceFailureRetainsRetryFirstWithoutAcknowledgementOrCompletion() = runBlocking {
        var completions = 0
        val session = session(this) { completions++ }
        work.rebuildPending(listOf(first, second))
        connect(session)

        try {
            assertNull(session.prepareResult(ScanEvent.WorkerResult(result()), PdfRepository(AppDatabase_Impl())))
            assertEquals(0, completions)
            assertTrue(worker.acknowledgements.isEmpty())
            assertNull(work.inFlight)
            assertEquals(listOf(first, second), work.snapshot(ScanPhase.READING_DETAILS, true).entries)
            assertEquals(first.fingerprint, work.claimNext())
        } finally {
            session.cancelTimers()
        }
    }

    private fun session(scope: CoroutineScope, completion: suspend () -> Unit = {}): ScanMetadataSession {
        val client = PdfMetadataClient(context, post)
        return ScanMetadataSession(work, publication, client, scope, post, completion)
    }

    private fun connect(session: ScanMetadataSession, sample: MetadataMemorySample = memory) {
        session.ensureWorker()
        assertEquals(work.generation to 0L, worker.registration)
        session.workerConnected(ScanEvent.WorkerConnected(work.generation, 0, 21, 22, sample))
    }

    private fun finishDiscovery() {
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.MEDIA_STORE))
        work.sourceFinished(ScanEvent.DiscoverySourceFinished(10, DiscoverySource.FILESYSTEM))
    }

    private fun result() = MetadataResult(
        generation = 10,
        connectionToken = 0,
        fingerprint = first.fingerprint,
        hash = "computed",
        pageCount = 12,
        failure = null,
        sourceChanged = false,
        sample = memory,
        workerPid = 21,
        workerInstance = 22,
        maxActiveDocuments = 1,
    )

    private class RecordingMetadataService : IPdfMetadataService.Stub() {
        var registration: Pair<Long, Long>? = null
        val processCalls = mutableListOf<Pair<SourceFingerprint, Int>>()
        val acknowledgements = mutableListOf<Pair<SourceFingerprint, Int>>()
        val memoryRequests = mutableListOf<Pair<Long, Long>>()

        override fun queryLocalInterface(descriptor: String): IInterface = this

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true

        override fun registerCallback(callback: IPdfMetadataCallback?, generation: Long, connectionToken: Long) {
            registration = generation to connectionToken
        }

        override fun sampleMemory(generation: Long, connectionToken: Long) {
            memoryRequests += generation to connectionToken
        }

        override fun process(
            generation: Long,
            connectionToken: Long,
            path: String,
            size: Long,
            lastModified: Long,
            debugBehavior: Int,
        ) {
            processCalls += SourceFingerprint(path, size, lastModified) to debugBehavior
        }

        override fun acknowledge(
            generation: Long,
            connectionToken: Long,
            path: String,
            size: Long,
            lastModified: Long,
            action: Int,
        ) {
            acknowledgements += SourceFingerprint(path, size, lastModified) to action
        }
    }
}
