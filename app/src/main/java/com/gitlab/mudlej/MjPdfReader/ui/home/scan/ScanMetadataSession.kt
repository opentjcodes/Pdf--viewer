// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import android.os.SystemClock
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.core.debug.MetadataWorkerTestHooks
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataHeadroomDecision
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataHeadroomPolicy
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataProtocol
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.PdfMetadataClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ScanMetadataSession(
    private val work: ScanWorkIndex,
    private val publication: ScanPublication,
    private val metadataClient: PdfMetadataClient,
    private val scope: CoroutineScope,
    private val post: (ScanEvent) -> Unit,
    private val maybeFinishGeneration: suspend () -> Unit,
) {
    private val memoryPolicy = MetadataHeadroomPolicy()
    private val reconnect = RetryTimer(RECONNECT_INITIAL_MILLIS)
    private val memoryResample = RetryTimer(MEMORY_PAUSE_INITIAL_MILLIS)
    private var worker: WorkerState = WorkerState.Disconnected

    private data class Connection(val generation: Long, val token: Long)

    private sealed interface WorkerState {
        val connection: Connection? get() = null

        data object Disconnected : WorkerState
        data class Connecting(override val connection: Connection) : WorkerState
        data class Serving(override val connection: Connection) : WorkerState
        data class MemoryPaused(override val connection: Connection) : WorkerState
        data class AwaitingAck(override val connection: Connection, val action: Int) : WorkerState
    }

    private val generation: Long get() = work.generation
    val blocksCompletion: Boolean
        get() = when (worker) {
            is WorkerState.Connecting, is WorkerState.MemoryPaused, is WorkerState.AwaitingAck -> true
            WorkerState.Disconnected, is WorkerState.Serving -> false
        }

    fun cancelTimers() {
        reconnect.cancel()
        memoryResample.cancel()
    }

    fun resetForGeneration() {
        worker = WorkerState.Disconnected
        metadataClient.disconnect(expected = true)
        reconnect.resetBackoff()
        memoryResample.resetBackoff()
        memoryPolicy.reset()
    }

    fun pauseForAccessLoss() {
        worker = WorkerState.Disconnected
        metadataClient.disconnect(expected = true)
        work.releaseActive()
        work.clearPending()
    }

    fun finishGeneration() {
        if (worker is WorkerState.Serving) {
            worker = WorkerState.Disconnected
            metadataClient.disconnect(expected = true)
        }
    }

    fun ensureWorker() {
        if (MetadataWorkerTestHooks.dispatchPaused() || work.inFlight != null) return
        if (worker is WorkerState.Serving) {
            dispatchNext()
            return
        }
        if (worker != WorkerState.Disconnected) return
        if (!work.hasPending) {
            post(ScanEvent.CheckCompletion(generation))
            return
        }
        val connection = Connection(generation, SystemClock.elapsedRealtimeNanos())
        worker = WorkerState.Connecting(connection)
        if (!metadataClient.connect(connection.generation, connection.token)) {
            worker = WorkerState.Disconnected
            reconnect.schedule(ScanEvent.ReconnectDeadline(generation))
        }
    }

    fun workerConnected(event: ScanEvent.WorkerConnected) {
        if (worker !is WorkerState.Connecting) return
        val connection = currentConnection(event.generation, event.connectionToken) ?: return
        reconnect.resetBackoff()
        memoryResample.resetBackoff()
        memoryPolicy.reset()
        val decision = evaluatePreDispatchMemory(event.pid, event.workerInstance, event.sample)
        if (decision.recycle) {
            worker = WorkerState.MemoryPaused(connection)
            publication.publish(work.discoveryPhase)
            memoryResample.schedule(ScanEvent.MemoryResampleDeadline(connection.generation, connection.token))
            return
        }
        worker = WorkerState.Serving(connection)
        dispatchNext()
    }

    fun workerMemorySample(event: ScanEvent.WorkerMemorySample) {
        if (worker !is WorkerState.MemoryPaused) return
        val connection = currentConnection(event.generation, event.connectionToken) ?: return
        val decision = evaluatePreDispatchMemory(event.pid, event.workerInstance, event.sample)
        if (decision.recycle) {
            memoryResample.schedule(ScanEvent.MemoryResampleDeadline(connection.generation, connection.token))
            return
        }
        memoryPolicy.reset()
        memoryPolicy.evaluate(event.sample)
        memoryResample.resetBackoff()
        worker = WorkerState.Serving(connection)
        dispatchNext()
    }

    private fun evaluatePreDispatchMemory(
        pid: Int,
        workerInstance: Long,
        sample: MetadataMemorySample,
    ): MetadataHeadroomDecision {
        ScanDebugSignals.workerConnected(pid, workerInstance, sample.baselineNativePssBytes, maxActiveDocuments = 0)
        val decision = memoryPolicy.evaluate(sample)
        ScanDebugSignals.logPreDispatchMemory(generation, pid, sample, decision)
        return decision
    }

    fun memoryResampleDeadline(event: ScanEvent.MemoryResampleDeadline) {
        if (worker !is WorkerState.MemoryPaused) return
        val connection = currentConnection(event.generation, event.connectionToken) ?: return
        metadataClient.sampleMemory(connection.generation, connection.token)
    }

    private fun dispatchNext() {
        val serving = worker as? WorkerState.Serving ?: return
        val connection = serving.connection
        if (work.inFlight != null || MetadataWorkerTestHooks.dispatchPaused()) return
        val candidate = work.claimNext()
        if (candidate != null) {
            ScanDebugSignals.noteDispatch(candidate)
            metadataClient.process(
                connection.generation,
                connection.token,
                candidate,
                MetadataWorkerTestHooks.debugBehavior(candidate),
            )
            return
        }
        post(ScanEvent.CheckCompletion(generation))
    }

    suspend fun prepareResult(event: ScanEvent.WorkerResult, repository: PdfRepository): MetadataResult? {
        val result = event.result
        val current = acceptResult(result) ?: return null
        val committed = try {
            work.commitMetadata(result, repository)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(ScanDebugSignals.TAG, "metadata commit failed; reconstructing pending work", error)
            worker = WorkerState.Disconnected
            metadataClient.disconnect(expected = true)
            work.releaseActive()
            work.retryFirst(current)
            publication.publish(work.discoveryPhase)
            reconnect.schedule(ScanEvent.ReconnectDeadline(generation))
            return null
        }
        if (committed) {
            work.applyCommittedMetadata(result)
            MetadataWorkerTestHooks.noteCommitted()
            ScanDebugSignals.noteCommitted()
            publication.publish(work.discoveryPhase)
        } else if (!result.sourceChanged) {
            work.reconcilePath(current.path, repository)
        }
        return result
    }

    private fun acceptResult(result: MetadataResult): SourceFingerprint? {
        if (worker !is WorkerState.Serving || currentConnection(result.generation, result.connectionToken) == null) {
            ScanDebugSignals.noteStaleResultRejected()
            return null
        }
        val current = work.inFlight
        if (current == null || current != result.fingerprint) {
            ScanDebugSignals.noteStaleResultRejected()
            return null
        }
        ScanDebugSignals.workerConnected(
            result.workerPid,
            result.workerInstance,
            result.sample.baselineNativePssBytes,
            result.maxActiveDocuments,
        )
        return current
    }

    fun acknowledge(result: MetadataResult, refreshPending: Boolean) {
        if (worker !is WorkerState.Serving || work.inFlight != result.fingerprint) return
        val connection = currentConnection(result.generation, result.connectionToken) ?: return
        val fingerprint = result.fingerprint
        val decision = evaluateResultMemory(result)
        val hasMore = work.hasPending || !work.discoveryComplete || refreshPending
        val action = when {
            decision.recycle && hasMore -> MetadataProtocol.ACK_RECYCLE
            !hasMore -> MetadataProtocol.ACK_STOP
            else -> MetadataProtocol.ACK_CONTINUE
        }
        if (action == MetadataProtocol.ACK_RECYCLE) {
            ScanDebugSignals.noteRecycle(decision.reason.name)
        }
        worker = WorkerState.AwaitingAck(connection, action)
        metadataClient.acknowledge(connection.generation, connection.token, fingerprint, action)
    }

    private fun evaluateResultMemory(result: MetadataResult): MetadataHeadroomDecision {
        val realSample = result.sample
        val effectiveSample = MetadataWorkerTestHooks.effectiveMemorySample(realSample)
        val decision = memoryPolicy.evaluate(effectiveSample)
        ScanDebugSignals.logMemory(
            generation,
            result.fingerprint,
            result.workerPid,
            realSample,
            effectiveSample,
            decision,
        )
        return decision
    }

    suspend fun workerAcknowledged(event: ScanEvent.WorkerAcknowledged) {
        val awaiting = worker as? WorkerState.AwaitingAck ?: return
        if (currentConnection(event.generation, event.connectionToken) == null) return
        completeAcknowledgement(awaiting)
    }

    private suspend fun completeAcknowledgement(awaiting: WorkerState.AwaitingAck) {
        work.releaseActive()
        ScanDebugSignals.workerIdle()
        if (awaiting.action == MetadataProtocol.ACK_CONTINUE) {
            worker = WorkerState.Serving(awaiting.connection)
            dispatchNext()
            return
        }
        worker = WorkerState.Disconnected
        metadataClient.disconnect(expected = true)
        if (work.hasPending) reconnect.schedule(ScanEvent.ReconnectDeadline(generation)) else maybeFinishGeneration()
    }

    suspend fun workerDied(event: ScanEvent.WorkerDied, repository: PdfRepository) {
        if (currentConnection(event.generation, event.connectionToken) == null) return
        val previous = worker
        if (event.expected && previous is WorkerState.AwaitingAck && previous.action != MetadataProtocol.ACK_CONTINUE) {
            completeAcknowledgement(previous)
            return
        }
        val interrupted = work.inFlight
        worker = WorkerState.Disconnected
        metadataClient.disconnect(expected = true)
        work.releaseActive()
        if (!event.expected && interrupted != null && previous !is WorkerState.AwaitingAck) {
            work.recoverInterrupted(interrupted, repository)
        }

        if (event.expected) return
        publication.publish(work.discoveryPhase)
        if (work.hasPending) reconnect.schedule(ScanEvent.ReconnectDeadline(generation)) else maybeFinishGeneration()
    }

    private fun currentConnection(eventGeneration: Long, token: Long): Connection? =
        worker.connection?.takeIf {
            eventGeneration == generation && it.generation == eventGeneration && it.token == token
        }

    private inner class RetryTimer(private val initialDelayMillis: Long) {
        private var attempt = 0
        private var job: Job? = null

        fun cancel() {
            job?.cancel()
        }

        fun resetBackoff() {
            attempt = 0
        }

        fun schedule(event: ScanEvent) {
            if (job?.isActive == true) return
            val delayMillis = (initialDelayMillis shl attempt.coerceAtMost(5)).coerceAtMost(RETRY_MAX_MILLIS)
            attempt++
            job = scope.launch {
                delay(delayMillis)
                post(event)
            }
        }
    }

    companion object {
        private const val RECONNECT_INITIAL_MILLIS = 250L
        private const val MEMORY_PAUSE_INITIAL_MILLIS = 500L
        private const val RETRY_MAX_MILLIS = 8_000L
    }
}
