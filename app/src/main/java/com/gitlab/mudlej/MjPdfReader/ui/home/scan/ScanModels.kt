// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataFailure
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample

enum class ScanPhase {
    IDLE,
    FINDING,
    READING_DETAILS,
    PAUSED,
    INCOMPLETE,
}

enum class DiscoverySource { MEDIA_STORE, FILESYSTEM }

enum class ScanRefreshReason { FOREGROUND, OBSERVER, MANUAL, FULL_RESCAN }

data class LibraryIndex(
    val entries: List<ScannedPdfEntry> = emptyList(),
    val loaded: Boolean = false,
    val generation: Long = 0L,
    val phase: ScanPhase = ScanPhase.IDLE,
    val foundCount: Int = 0,
    val detailsCompleted: Int = 0,
    val detailsTotal: Int = 0,
    val sourceFailures: Set<DiscoverySource> = emptySet(),
    val showProgress: Boolean = false,
    val revision: Long = 0L,
    val scopeRoots: List<String>? = emptyList(),
) {
    val active: Boolean
        get() = when (phase) {
            ScanPhase.FINDING,
            ScanPhase.READING_DETAILS,
            -> true
            else -> false
        }

    val scanning: Boolean
        get() = active
}

data class MetadataResult(
    val generation: Long,
    val connectionToken: Long,
    val fingerprint: SourceFingerprint,
    val hash: String?,
    val pageCount: Int,
    val failure: ScannedPdfMetadataFailure?,
    val sourceChanged: Boolean,
    val sample: MetadataMemorySample,
    val workerPid: Int,
    val workerInstance: Long,
    val maxActiveDocuments: Int,
)

sealed interface ScanEvent {
    data class RefreshRequested(val reason: ScanRefreshReason) : ScanEvent
    data class ScopeChanged(val previousRoots: List<String>?) : ScanEvent
    data class CheckCompletion(val generation: Long) : ScanEvent
    data object ObserverChanged : ScanEvent
    data class ObserverDebounceExpired(val requestToken: Long) : ScanEvent
    data object ResumeMetadataDispatch : ScanEvent

    data class DiscoveryBatch(
        val generation: Long,
        val source: DiscoverySource,
        val entries: List<ScannedPdfEntry>,
    ) : ScanEvent
    data class DiscoverySourceFinished(val generation: Long, val source: DiscoverySource) : ScanEvent
    data class DiscoverySourceFailed(
        val generation: Long,
        val source: DiscoverySource,
        val throwable: Throwable,
    ) : ScanEvent

    data class WorkerConnected(
        val generation: Long,
        val connectionToken: Long,
        val pid: Int,
        val workerInstance: Long,
        val sample: MetadataMemorySample,
    ) : ScanEvent
    data class WorkerMemorySample(
        val generation: Long,
        val connectionToken: Long,
        val pid: Int,
        val workerInstance: Long,
        val sample: MetadataMemorySample,
    ) : ScanEvent
    data class WorkerResult(val result: MetadataResult) : ScanEvent
    data class WorkerAcknowledged(val generation: Long, val connectionToken: Long) : ScanEvent
    data class WorkerDied(val generation: Long, val connectionToken: Long, val expected: Boolean) : ScanEvent
    data class ReconnectDeadline(val generation: Long) : ScanEvent
    data class MemoryResampleDeadline(val generation: Long, val connectionToken: Long) : ScanEvent

    data class StorageAccessChanged(val hasFullAccess: Boolean) : ScanEvent
    data class FileRemoved(val path: String) : ScanEvent
    data class FileRenamed(val oldPath: String, val newPath: String) : ScanEvent
    data class HashComputed(val fingerprint: SourceFingerprint, val hash: String) : ScanEvent

}
