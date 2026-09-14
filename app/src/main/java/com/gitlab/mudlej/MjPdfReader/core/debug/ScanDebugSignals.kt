// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import android.os.Process
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataHeadroomDecision
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.DiscoverySource
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object ScanDebugSignals {
    const val TAG = "MjPdfScan"
    const val METADATA_TAG = "MjPdfMetadata"

    private val discoveryRunCount = AtomicInteger()
    private val filesystemDirectoryCount = AtomicInteger()
    private val mediaStoreQueryCount = AtomicInteger()
    private val pathProbeCount = AtomicInteger()
    private val settlementCommitCount = AtomicInteger()
    private val activeDiscovery = AtomicInteger()
    private val maxDiscovery = AtomicInteger()
    private val committed = AtomicInteger()
    private val staleResultsRejected = AtomicInteger()
    private val dispatches = FingerprintCounts()
    private val workerDeaths = FingerprintCounts()
    private val workerRetries = FingerprintCounts()
    private val appendSnapshots = CopyOnWriteArrayList<List<String>>()
    private val settlementSnapshots = CopyOnWriteArrayList<List<String>>()
    private val current = AtomicReference(Snapshot())

    data class Snapshot(
        val index: LibraryIndex = LibraryIndex(),
        val mainPid: Int = Process.myPid(),
        val workerPid: Int = 0,
        val workerInstance: Long = 0L,
        val workerPids: Set<Int> = emptySet(),
        val workerInstances: Set<Long> = emptySet(),
        val workerInstancePids: Map<Long, Int> = emptyMap(),
        val workerBaselines: Map<Long, Long> = emptyMap(),
        val maxActiveMetadataDocuments: Int = 0,
        val inFlight: SourceFingerprint? = null,
        val intentionalRecycles: Int = 0,
        val lastRecycleReason: String? = null,
        val realMemorySamples: Int = 0,
        val effectiveMemorySamples: Int = 0,
        val lastRealMemorySample: MetadataMemorySample? = null,
        val lastEffectiveMemorySample: MetadataMemorySample? = null,
    )

    fun noteDiscoveryStarted(generation: Long) {
        if (!BuildConfig.DEBUG) return
        discoveryRunCount.incrementAndGet()
        val active = activeDiscovery.incrementAndGet()
        maxDiscovery.accumulateAndGet(active) { previous, next -> maxOf(previous, next) }
        Log.i(TAG, "discovery start generation=$generation active=$active")
    }

    fun noteDiscoveryFinished(generation: Long) {
        if (!BuildConfig.DEBUG) return
        val active = activeDiscovery.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
        Log.i(TAG, "discovery finish generation=$generation active=$active")
    }

    fun noteFilesystemDirectory() {
        if (BuildConfig.DEBUG) filesystemDirectoryCount.incrementAndGet()
    }

    fun noteMediaStoreQuery() {
        if (BuildConfig.DEBUG) mediaStoreQueryCount.incrementAndGet()
    }

    fun notePathProbe() {
        if (BuildConfig.DEBUG) pathProbeCount.incrementAndGet()
    }

    fun noteAppend(keys: List<String>) {
        if (BuildConfig.DEBUG) appendSnapshots += keys.toList()
    }

    fun noteSettlement(generation: Long, token: Long, keys: List<String>) {
        if (!BuildConfig.DEBUG) return
        settlementCommitCount.incrementAndGet()
        settlementSnapshots += keys.toList()
        Log.i(TAG, "settlement generation=$generation token=$token size=${keys.size}")
    }

    fun noteStaleResultRejected() {
        if (BuildConfig.DEBUG) staleResultsRejected.incrementAndGet()
    }

    fun noteDispatch(fingerprint: SourceFingerprint) {
        if (!BuildConfig.DEBUG) return
        dispatches.increment(fingerprint)
        current.updateAndGet { it.copy(inFlight = fingerprint) }
    }

    fun noteWorkerDeath(fingerprint: SourceFingerprint?) {
        if (!BuildConfig.DEBUG || fingerprint == null) return
        workerDeaths.increment(fingerprint)
        Log.w(TAG, "worker death fingerprint=${opaque(fingerprint)}")
    }

    fun noteRetry(fingerprint: SourceFingerprint) {
        if (!BuildConfig.DEBUG) return
        workerRetries.increment(fingerprint)
        Log.i(TAG, "worker retry fingerprint=${opaque(fingerprint)}")
    }

    fun noteCommitted() {
        if (BuildConfig.DEBUG) committed.incrementAndGet()
    }

    fun publish(index: LibraryIndex) {
        if (BuildConfig.DEBUG) current.updateAndGet { it.copy(index = index) }
    }

    fun workerConnected(pid: Int, instance: Long, baseline: Long, maxActiveDocuments: Int) {
        if (!BuildConfig.DEBUG) return
        current.updateAndGet {
            it.copy(
                workerPid = pid,
                workerInstance = instance,
                workerPids = it.workerPids + pid,
                workerInstances = it.workerInstances + instance,
                workerInstancePids = it.workerInstancePids + (instance to pid),
                workerBaselines = it.workerBaselines + (instance to baseline),
                maxActiveMetadataDocuments = maxOf(
                    it.maxActiveMetadataDocuments,
                    maxActiveDocuments,
                ),
            )
        }
        Log.i(TAG, "worker connected pid=$pid instance=$instance baselineNativePss=$baseline")
    }

    fun workerIdle() {
        if (BuildConfig.DEBUG) current.updateAndGet { it.copy(inFlight = null) }
    }

    fun noteRecycle(reason: String) {
        if (!BuildConfig.DEBUG) return
        current.updateAndGet {
            it.copy(intentionalRecycles = it.intentionalRecycles + 1, lastRecycleReason = reason)
        }
        Log.i(TAG, "intentional recycle reason=$reason")
    }

    fun noteSourceFailure(source: DiscoverySource, error: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, "source failure source=$source", error)
    }

    fun logPreDispatchMemory(
        generation: Long,
        workerPid: Int,
        sample: MetadataMemorySample,
        decision: MetadataHeadroomDecision,
    ) {
        if (!BuildConfig.DEBUG) return
        current.updateAndGet {
            it.copy(
                realMemorySamples = it.realMemorySamples + 1,
                lastRealMemorySample = sample,
            )
        }
        Log.i(
            METADATA_TAG,
            "generation=$generation preDispatch=true pid=$workerPid " + memoryFields(sample, sample, decision)
        )
    }

    fun logMemory(
        generation: Long,
        fingerprint: SourceFingerprint,
        workerPid: Int,
        realSample: MetadataMemorySample,
        effectiveSample: MetadataMemorySample,
        decision: MetadataHeadroomDecision,
    ) {
        if (!BuildConfig.DEBUG) return
        current.updateAndGet {
            it.copy(
                realMemorySamples = it.realMemorySamples + 1,
                effectiveMemorySamples = it.effectiveMemorySamples + 1,
                lastRealMemorySample = realSample,
                lastEffectiveMemorySample = effectiveSample,
            )
        }
        Log.i(
            METADATA_TAG,
            "generation=$generation fingerprint=${opaque(fingerprint)} pid=$workerPid " +
                memoryFields(realSample, effectiveSample, decision)
        )
    }

    private fun memoryFields(
        real: MetadataMemorySample,
        effective: MetadataMemorySample,
        decision: MetadataHeadroomDecision,
    ): String =
        "realBaselineNativePss=${real.baselineNativePssBytes} realNativePss=${real.nativePssBytes} " +
            "realAvail=${real.availableBytes} realThreshold=${real.thresholdBytes} " +
            "realTotal=${real.totalBytes} realLowMemory=${real.lowMemory} " +
            "effectiveBaselineNativePss=${effective.baselineNativePssBytes} " +
            "effectiveNativePss=${effective.nativePssBytes} retained=${decision.workerGrowth} " +
            "effectiveAvail=${effective.availableBytes} effectiveThreshold=${effective.thresholdBytes} " +
            "effectiveTotal=${effective.totalBytes} effectiveLowMemory=${effective.lowMemory} " +
            "raw=${decision.rawHeadroom} reserve=${decision.safetyReserve} " +
            "usable=${decision.usableHeadroom} budget=${decision.workerGrowthBudget} " +
            "recentMedian=${decision.recentMedianGrowth} lastPositive=${decision.lastPositiveGrowth} " +
            "predicted=${decision.predictedGrowth} reason=${decision.reason}"

    fun snapshot(): Snapshot = current.get()
    fun discoveryRuns(): Int = discoveryRunCount.get()
    fun filesystemDirectories(): Int = filesystemDirectoryCount.get()
    fun mediaStoreQueries(): Int = mediaStoreQueryCount.get()
    fun pathProbes(): Int = pathProbeCount.get()
    fun settlementCommits(): Int = settlementCommitCount.get()
    fun committedResults(): Int = committed.get()
    fun maxDiscoveryProducers(): Int = maxDiscovery.get()
    fun appendPublications(): List<List<String>> = appendSnapshots.toList()
    fun settlementPublications(): List<List<String>> = settlementSnapshots.toList()
    fun dispatchCount(fingerprint: SourceFingerprint): Int = dispatches.get(fingerprint)
    fun workerDeathCount(fingerprint: SourceFingerprint): Int = workerDeaths.get(fingerprint)
    fun workerRetryCount(fingerprint: SourceFingerprint): Int = workerRetries.get(fingerprint)
    fun staleResultRejections(): Int = staleResultsRejected.get()

    private class FingerprintCounts {
        private val counts = ConcurrentHashMap<String, AtomicInteger>()

        fun increment(fingerprint: SourceFingerprint) {
            counts.getOrPut(key(fingerprint)) { AtomicInteger() }.incrementAndGet()
        }

        fun get(fingerprint: SourceFingerprint): Int = counts[key(fingerprint)]?.get() ?: 0
    }

    private fun key(value: SourceFingerprint) = "${value.path}|${value.size}|${value.lastModified}"
    private fun opaque(value: SourceFingerprint) = key(value).hashCode().toUInt().toString(16)
}
