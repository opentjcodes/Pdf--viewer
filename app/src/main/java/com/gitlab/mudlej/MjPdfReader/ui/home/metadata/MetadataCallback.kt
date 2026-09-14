// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataFailure
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.MetadataResult
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanEvent

internal class MetadataCallback(
    private val isCurrent: () -> Boolean,
    private val emit: (ScanEvent) -> Unit,
) : IPdfMetadataCallback.Stub() {
    override fun onReady(
        generation: Long,
        connectionToken: Long,
        workerPid: Int,
        workerInstance: Long,
        baselineNativePssBytes: Long,
        nativePssBytes: Long,
        availableBytes: Long,
        thresholdBytes: Long,
        totalBytes: Long,
        lowMemory: Boolean,
    ) {
        if (!isCurrent()) return
        val sample = MetadataMemorySample(
            availableBytes = availableBytes,
            thresholdBytes = thresholdBytes,
            totalBytes = totalBytes,
            lowMemory = lowMemory,
            baselineNativePssBytes = baselineNativePssBytes,
            nativePssBytes = nativePssBytes,
        )
        emit(
            ScanEvent.WorkerConnected(
                generation = generation,
                connectionToken = connectionToken,
                pid = workerPid,
                workerInstance = workerInstance,
                sample = sample,
            )
        )
    }

    override fun onMemorySample(
        generation: Long,
        connectionToken: Long,
        workerPid: Int,
        workerInstance: Long,
        baselineNativePssBytes: Long,
        nativePssBytes: Long,
        availableBytes: Long,
        thresholdBytes: Long,
        totalBytes: Long,
        lowMemory: Boolean,
    ) {
        if (!isCurrent()) return
        val sample = MetadataMemorySample(
            availableBytes = availableBytes,
            thresholdBytes = thresholdBytes,
            totalBytes = totalBytes,
            lowMemory = lowMemory,
            baselineNativePssBytes = baselineNativePssBytes,
            nativePssBytes = nativePssBytes,
        )
        emit(
            ScanEvent.WorkerMemorySample(
                generation = generation,
                connectionToken = connectionToken,
                pid = workerPid,
                workerInstance = workerInstance,
                sample = sample,
            )
        )
    }

    override fun onResult(
        generation: Long,
        connectionToken: Long,
        path: String,
        size: Long,
        lastModified: Long,
        hash: String?,
        pageCount: Int,
        failureCode: String?,
        workerPid: Int,
        workerInstance: Long,
        baselineNativePssBytes: Long,
        maxActiveDocuments: Int,
        nativePssBytes: Long,
        availableBytes: Long,
        thresholdBytes: Long,
        totalBytes: Long,
        lowMemory: Boolean,
    ) {
        if (!isCurrent()) return
        val sourceChanged = failureCode == MetadataProtocol.FAILURE_SOURCE_CHANGED
        val failure = if (failureCode.isNullOrBlank() || sourceChanged) null else {
            runCatching { ScannedPdfMetadataFailure.valueOf(failureCode) }
                .getOrDefault(ScannedPdfMetadataFailure.INTERNAL_ERROR)
        }
        val sample = MetadataMemorySample(
            availableBytes = availableBytes,
            thresholdBytes = thresholdBytes,
            totalBytes = totalBytes,
            lowMemory = lowMemory,
            baselineNativePssBytes = baselineNativePssBytes,
            nativePssBytes = nativePssBytes,
        )
        val result = MetadataResult(
            generation = generation,
            connectionToken = connectionToken,
            fingerprint = SourceFingerprint(path = path, size = size, lastModified = lastModified),
            hash = hash,
            pageCount = pageCount,
            failure = failure,
            sourceChanged = sourceChanged,
            sample = sample,
            workerPid = workerPid,
            workerInstance = workerInstance,
            maxActiveDocuments = maxActiveDocuments,
        )
        emit(ScanEvent.WorkerResult(result))
    }

    override fun onAcknowledged(generation: Long, connectionToken: Long) {
        if (!isCurrent()) return
        emit(ScanEvent.WorkerAcknowledged(generation = generation, connectionToken = connectionToken))
    }
}
