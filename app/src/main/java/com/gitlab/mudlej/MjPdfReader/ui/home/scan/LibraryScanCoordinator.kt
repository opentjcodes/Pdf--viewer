// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import android.content.Context
import android.os.OperationCanceledException
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.core.debug.MetadataWorkerTestHooks
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.ScanMode
import com.gitlab.mudlej.MjPdfReader.ui.home.ScanScope
import com.gitlab.mudlej.MjPdfReader.ui.home.StorageManager
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.PdfMetadataClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LibraryScanCoordinator(
    context: Context,
    private val repository: PdfRepository,
) {
    private val context = context.applicationContext
    private val preferences = Preferences(PreferenceManager.getDefaultSharedPreferences(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<ScanEvent>(Channel.UNLIMITED)
    private val ingressLock = Any()
    private val work = ScanWorkIndex()
    private val publication = ScanPublication(work)
    private val metadata = ScanMetadataSession(
        work,
        publication,
        PdfMetadataClient(context, ::post),
        scope,
        ::post,
        ::maybeFinishGeneration,
    )

    val index: StateFlow<LibraryIndex> = publication.index

    private var discoveryJob: Job? = null
    private var pendingRefresh: ScanRefreshReason? = null
    private var walks = emptyList<ScanWalk>()
    private var sessionIncomplete = false
    private var observerRequestToken = 0L

    init {
        scope.launch {
            try {
                try {
                    initialize()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    failGeneration(error)
                } catch (error: Exception) {
                    failGeneration(error)
                }
                for (event in events) {
                    try {
                        handle(event)
                    } catch (error: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        failGeneration(error)
                    } catch (error: Exception) {
                        failGeneration(error)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    retireGeneration()
                    cancelSource()
                }
            }
        }
    }

    fun post(event: ScanEvent) {
        synchronized(ingressLock) {
            check(events.trySend(event).isSuccess) { "Scan event ingress is closed" }
        }
    }

    suspend fun send(event: ScanEvent) = post(event)

    fun refresh(reason: ScanRefreshReason = ScanRefreshReason.FOREGROUND) = post(ScanEvent.RefreshRequested(reason))

    fun debounceObserverChange() = post(ScanEvent.ObserverChanged)

    fun resumeMetadataDispatch() {
        MetadataWorkerTestHooks.resumeDispatch()
        post(ScanEvent.ResumeMetadataDispatch)
    }

    internal suspend fun shutdownForTests() {
        check(BuildConfig.DEBUG)
        val job = scope.coroutineContext[Job] ?: return
        job.cancel()
        withTimeoutOrNull(SOURCE_CANCEL_TIMEOUT_MILLIS * 2) { job.join() }
    }

    fun libraryEntries(snapshot: LibraryIndex): List<ScannedPdfEntry> =
        snapshot.entries.filter { ScanScope.contains(snapshot.scopeRoots, it.path) }

    private suspend fun handle(event: ScanEvent) {
        when (event) {
            is ScanEvent.RefreshRequested -> requestRefresh(event.reason)
            is ScanEvent.ScopeChanged -> scopeChanged(event.previousRoots)
            is ScanEvent.CheckCompletion -> if (event.generation == work.generation) maybeFinishGeneration()
            ScanEvent.ObserverChanged -> scheduleObserverRefresh()
            is ScanEvent.ObserverDebounceExpired -> {
                if (event.requestToken == observerRequestToken) requestRefresh(ScanRefreshReason.OBSERVER)
            }
            ScanEvent.ResumeMetadataDispatch -> if (index.value.active) metadata.ensureWorker()
            is ScanEvent.DiscoveryBatch -> handleDiscoveryBatch(event)
            is ScanEvent.DiscoverySourceFinished -> sourceFinished(event)
            is ScanEvent.DiscoverySourceFailed -> sourceFailed(event)
            is ScanEvent.WorkerConnected -> metadata.workerConnected(event)
            is ScanEvent.WorkerMemorySample -> metadata.workerMemorySample(event)
            is ScanEvent.WorkerResult -> workerResult(event)
            is ScanEvent.WorkerAcknowledged -> metadata.workerAcknowledged(event)
            is ScanEvent.WorkerDied -> metadata.workerDied(event, repository)
            is ScanEvent.ReconnectDeadline -> if (event.generation == work.generation && index.value.active) metadata.ensureWorker()
            is ScanEvent.MemoryResampleDeadline -> metadata.memoryResampleDeadline(event)
            is ScanEvent.StorageAccessChanged -> storageAccessChanged(event.hasFullAccess)
            is ScanEvent.FileRemoved -> removeFile(event.path)
            is ScanEvent.FileRenamed -> renameFile(event.oldPath, event.newPath)
            is ScanEvent.HashComputed -> storeHash(event.fingerprint, event.hash)
        }
    }

    private suspend fun initialize() {
        val rows = repository.findAllScannedPdfs()
        work.restore(rows, currentScopeRoots())
        publication.publish(ScanPhase.IDLE, loaded = true)
    }

    private fun scheduleObserverRefresh() {
        val token = ++observerRequestToken
        scope.launch {
            delay(OBSERVER_DEBOUNCE_MILLIS)
            post(ScanEvent.ObserverDebounceExpired(token))
        }
    }

    private suspend fun requestRefresh(reason: ScanRefreshReason) {
        if (!PermissionManager.hasFullAccess(context)) {
            storageAccessChanged(false)
            return
        }
        if (index.value.active) {
            if (pendingRefresh != ScanRefreshReason.FULL_RESCAN) pendingRefresh = reason
            return
        }
        val current = currentScopeRoots()
        val retained = if (reason != ScanRefreshReason.FULL_RESCAN &&
            discoveryJob?.isCancelled == true && !work.filesystemComplete
        ) walks.map { it.within(current) } else emptyList()
        startGeneration(
            reason,
            if (reason == ScanRefreshReason.FULL_RESCAN) current else emptyList(),
            retainedWalks = retained,
        )
    }

    private suspend fun scopeChanged(previousRoots: List<String>?) {
        val current = currentScopeRoots()
        val fullRescan = pendingRefresh == ScanRefreshReason.FULL_RESCAN
        pendingRefresh = null
        if (fullRescan) {
            startGeneration(ScanRefreshReason.FULL_RESCAN, current)
        } else {
            val retained = if (work.filesystemComplete) emptyList() else walks.map { it.within(current) }
            startGeneration(
                ScanRefreshReason.FOREGROUND,
                ScanScope.newRoots(previousRoots, current),
                previousRoots.orEmpty(),
                retained,
            )
        }
    }

    private suspend fun startGeneration(
        reason: ScanRefreshReason,
        requestedWalkRoots: List<String>?,
        excluded: List<String> = emptyList(),
        retainedWalks: List<ScanWalk> = emptyList(),
    ) {
        retireGeneration()
        sessionIncomplete = false
        val roots = currentScopeRoots()
        if (!PermissionManager.hasFullAccess(context)) {
            work.resetScope(roots, setOf(DiscoverySource.MEDIA_STORE))
            publication.publish(ScanPhase.PAUSED, showProgress = false)
            cancelSource()
            return
        }
        val configured = preferences.getScanMode() != ScanMode.NOT_CONFIGURED
        walks = if (configured) {
            val requested = requestedWalkRoots?.toList() ?: StorageManager().volumeRoots(context).map { it.path }
            val explicitRoots = preferences.getScanMode() == ScanMode.SELECTED_LOCATIONS
            (retainedWalks + ScanWalk(requested, excluded.toList(), explicitRoots)).filter { it.roots.isNotEmpty() }
        } else emptyList()
        val sources = if (walks.isEmpty()) setOf(DiscoverySource.MEDIA_STORE) else DiscoverySource.entries.toSet()
        work.resetScope(roots, sources)
        val visible = configured && (reason == ScanRefreshReason.FULL_RESCAN || walks.isNotEmpty() ||
            (!work.hasEntries && preferences.getScanBaselineScope() != baselineScopeKey(roots)))
        publication.publish(ScanPhase.FINDING, showProgress = visible)
        if (!cancelSource()) {
            sessionIncomplete = true
            publication.publish(ScanPhase.INCOMPLETE)
            return
        }
        metadata.resetForGeneration()
        if (!index.value.loaded) work.restore(repository.findAllScannedPdfs(), roots)
        work.rebuildPending(repository.findPendingScannedPdfs())
        publication.publish(ScanPhase.FINDING, loaded = true)
        if (work.hasPending) metadata.ensureWorker()
        launchDiscovery()
    }

    private fun retireGeneration() {
        work.advanceGeneration(nextId())
        metadata.cancelTimers()
        metadata.pauseForAccessLoss()
    }

    private suspend fun cancelSource(): Boolean {
        val job = discoveryJob ?: return true
        if (job.isCancelled && !job.isCompleted) return false
        job.cancel()
        val completed = withTimeoutOrNull(SOURCE_CANCEL_TIMEOUT_MILLIS) {
            job.join()
            true
        } ?: false
        if (completed) discoveryJob = null
        return completed
    }

    private fun launchDiscovery() {
        val requestedWalks = walks
        val known = index.value.entries
        val startedGeneration = work.generation
        discoveryJob = scope.launch {
            ScanDebugSignals.noteDiscoveryStarted(startedGeneration)
            var source = DiscoverySource.MEDIA_STORE
            try {
                ScanDiscovery(context) { event ->
                    send(event)
                    if (event is ScanEvent.DiscoverySourceFinished) source = DiscoverySource.FILESYSTEM
                }.run(startedGeneration, requestedWalks, known)
            } catch (error: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    post(ScanEvent.DiscoverySourceFailed(startedGeneration, source, error))
                }
                throw error
            } finally {
                ScanDebugSignals.noteDiscoveryFinished(startedGeneration)
            }
        }
    }

    private suspend fun handleDiscoveryBatch(event: ScanEvent.DiscoveryBatch) {
        if (!index.value.active || !work.discover(event, repository)) return
        publication.publish(work.discoveryPhase)
        metadata.ensureWorker()
    }

    private suspend fun sourceFinished(event: ScanEvent.DiscoverySourceFinished) {
        if (!index.value.active || !work.sourceFinished(event)) return
        if (!sessionIncomplete && PermissionManager.hasFullAccess(context)) {
            val probe = ScanPathProbe(context)
            for (row in work.reconciliationCandidates(walks)) {
                work.reconcileSource(row.fingerprint, probe.read(row.path), repository)
            }
        }
        publication.publish(ScanPhase.READING_DETAILS)
        metadata.ensureWorker()
        maybeFinishGeneration()
    }

    private suspend fun sourceFailed(event: ScanEvent.DiscoverySourceFailed) {
        if (!index.value.active || !work.sourceFailed(event)) return
        sessionIncomplete = true
        ScanDebugSignals.noteSourceFailure(event.source, event.throwable)
        if (event.throwable is CancellationException || event.throwable is OperationCanceledException) throw event.throwable
        publication.publish(index.value.phase)
    }

    private suspend fun workerResult(event: ScanEvent.WorkerResult) {
        val result = metadata.prepareResult(event, repository) ?: return
        if (result.sourceChanged) {
            val state = ScanPathProbe(context).read(result.fingerprint.path)
            if (state == ScanPathState.Unavailable) {
                work.deferMetadata(result.fingerprint)
                sessionIncomplete = true
            } else if (state is ScanPathState.Present || (work.discoveryComplete && !sessionIncomplete)) {
                work.reconcileSource(result.fingerprint, state, repository)
            }
            publication.publish(work.discoveryPhase)
        }
        metadata.acknowledge(result, refreshPending = pendingRefresh != null)
    }

    private suspend fun maybeFinishGeneration() {
        if (!index.value.active || !work.discoveryComplete || work.hasPending || work.inFlight != null || metadata.blocksCompletion) return
        work.rebuildPending(repository.findPendingScannedPdfs())
        if (work.hasPending) {
            publication.publish(ScanPhase.READING_DETAILS)
            metadata.ensureWorker()
            return
        }
        metadata.finishGeneration()
        if (!sessionIncomplete) preferences.setScanBaselineScope(baselineScopeKey(work.scopeRoots))
        val pending = pendingRefresh
        pendingRefresh = null
        if (pending != null) {
            startGeneration(pending, if (pending == ScanRefreshReason.FULL_RESCAN) currentScopeRoots() else emptyList())
            return
        }
        publication.publish(
            if (sessionIncomplete) ScanPhase.INCOMPLETE else ScanPhase.IDLE,
            showProgress = sessionIncomplete && index.value.showProgress,
        )
    }

    private suspend fun storageAccessChanged(hasAccess: Boolean) {
        if (!hasAccess) {
            retireGeneration()
            pendingRefresh = null
            publication.publish(ScanPhase.PAUSED, showProgress = false)
            cancelSource()
            return
        }
        requestRefresh(ScanRefreshReason.FOREGROUND)
    }

    private suspend fun failGeneration(error: Exception) {
        Log.e(ScanDebugSignals.TAG, "scan generation failed generation=${work.generation}", error)
        retireGeneration()
        pendingRefresh = null
        sessionIncomplete = true
        publication.publish(ScanPhase.INCOMPLETE)
        cancelSource()
    }

    private suspend fun removeFile(path: String) {
        work.removeFile(path, repository)
        publication.publish(index.value.phase)
        if (index.value.active) maybeFinishGeneration()
    }

    private suspend fun renameFile(oldPath: String, newPath: String) {
        if (!work.renameFile(oldPath, newPath, repository)) return
        publication.publish(index.value.phase)
        if (index.value.active) metadata.ensureWorker() else requestRefresh(ScanRefreshReason.FOREGROUND)
    }

    private suspend fun storeHash(fingerprint: SourceFingerprint, hash: String) {
        if (!work.storeHash(fingerprint, hash, repository)) return
        publication.publish(index.value.phase)
    }

    private fun currentScopeRoots(): List<String>? =
        ScanScope.displayRoots(preferences.getScanMode(), preferences.getScanLocations())

    private fun nextId(): Long = maxOf(work.generation + 1, SystemClock.elapsedRealtimeNanos())

    companion object {
        private const val OBSERVER_DEBOUNCE_MILLIS = 2_000L
        private const val SOURCE_CANCEL_TIMEOUT_MILLIS = 250L

        internal fun baselineScopeKey(roots: List<String>?): String =
            roots?.sorted()?.joinToString(separator = "", prefix = "roots:") { "${it.length}:$it" } ?: "whole"
    }
}
