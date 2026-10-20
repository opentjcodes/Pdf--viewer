// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object MetadataWorkerTestHooks {
    private val dispatchPaused = AtomicBoolean(false)
    private val killOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val killAlways: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val hangOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val hangAlways: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pauseAfterCommitted = AtomicInteger(-1)
    private val injectedMemory = AtomicReference<MetadataMemorySample?>(null)

    fun pauseDispatch() { if (BuildConfig.DEBUG) dispatchPaused.set(true) }
    fun resumeDispatch() { dispatchPaused.set(false) }
    fun dispatchPaused(): Boolean = BuildConfig.DEBUG && dispatchPaused.get()

    fun killOnce(fingerprint: SourceFingerprint) {
        if (BuildConfig.DEBUG) killOnce.add(key(fingerprint))
    }

    fun killAlways(fingerprint: SourceFingerprint) {
        if (BuildConfig.DEBUG) killAlways.add(key(fingerprint))
    }

    fun clearKills() {
        killOnce.clear()
        killAlways.clear()
    }

    fun hangOnce(fingerprint: SourceFingerprint) {
        if (BuildConfig.DEBUG) hangOnce.add(key(fingerprint))
    }

    fun hangAlways(fingerprint: SourceFingerprint) {
        if (BuildConfig.DEBUG) hangAlways.add(key(fingerprint))
    }

    fun debugBehavior(fingerprint: SourceFingerprint): Int {
        if (!BuildConfig.DEBUG) return MetadataProtocol.DEBUG_NONE
        val key = key(fingerprint)
        return when {
            key in killAlways || killOnce.remove(key) -> MetadataProtocol.DEBUG_KILL
            key in hangAlways || hangOnce.remove(key) -> MetadataProtocol.DEBUG_HANG
            else -> MetadataProtocol.DEBUG_NONE
        }
    }

    fun pauseAfterCommittedResults(count: Int) {
        require(count > 0)
        if (BuildConfig.DEBUG) pauseAfterCommitted.set(count)
    }

    fun noteCommitted() {
        if (!BuildConfig.DEBUG) return
        val remaining = pauseAfterCommitted.get()
        if (remaining > 0 && pauseAfterCommitted.compareAndSet(remaining, remaining - 1) && remaining == 1) {
            dispatchPaused.set(true)
        }
    }

    fun injectMemorySample(sample: MetadataMemorySample) {
        if (BuildConfig.DEBUG) injectedMemory.set(sample)
    }

    fun effectiveMemorySample(real: MetadataMemorySample): MetadataMemorySample =
        if (BuildConfig.DEBUG) injectedMemory.getAndSet(null) ?: real else real

    fun reset() {
        dispatchPaused.set(false)
        pauseAfterCommitted.set(-1)
        clearKills()
        hangOnce.clear()
        hangAlways.clear()
        injectedMemory.set(null)
    }

    private fun key(fingerprint: SourceFingerprint): String =
        "${fingerprint.path}|${fingerprint.size}|${fingerprint.lastModified}"
}
