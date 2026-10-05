// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

object SearchTestHooks {
    @Volatile
    var pageScanObserver: ((String?, String, Int) -> Unit)? = null

    fun notePageScanned(fileHash: String?, query: String, page: Int) {
        if (BuildConfig.DEBUG) pageScanObserver?.invoke(fileHash, query, page)
    }

    @Volatile
    private var failNextSearchHighlight = false

    private val navigationActions = AtomicInteger()
    private val searchStarts = AtomicInteger()
    private val searchCompletions = AtomicInteger()

    fun noteSearchCompleted() {
        if (BuildConfig.DEBUG) searchCompletions.incrementAndGet()
    }

    fun searchCompletionCount(): Int = searchCompletions.get()

    fun noteSearchStarted() {
        if (BuildConfig.DEBUG) searchStarts.incrementAndGet()
    }

    fun searchStartCount(): Int = searchStarts.get()
    private val batchLock = Any()

    @Volatile
    private var blockNextBatch = false

    @Volatile
    private var batchBlocked = false

    private var batchRelease = CountDownLatch(0)

    fun failNextSearchHighlight() {
        if (!BuildConfig.DEBUG) {
            return
        }
        failNextSearchHighlight = true
    }

    fun consumeSearchHighlightFailure(): Boolean {
        if (!BuildConfig.DEBUG || !failNextSearchHighlight) {
            return false
        }
        failNextSearchHighlight = false
        return true
    }

    fun noteNavigationAction() {
        if (BuildConfig.DEBUG) {
            navigationActions.incrementAndGet()
        }
    }

    fun navigationActionCount(): Int = if (BuildConfig.DEBUG) navigationActions.get() else 0

    fun blockAfterNextResultBatch() {
        if (!BuildConfig.DEBUG) {
            return
        }
        synchronized(batchLock) {
            batchRelease.countDown()
            batchRelease = CountDownLatch(1)
            batchBlocked = false
            blockNextBatch = true
        }
    }

    fun blockIfResultBatchRequested() {
        if (!BuildConfig.DEBUG) {
            return
        }
        val release = synchronized(batchLock) {
            if (!blockNextBatch) {
                return
            }
            blockNextBatch = false
            batchBlocked = true
            batchRelease
        }
        try {
            release.await()
        } finally {
            batchBlocked = false
        }
    }

    fun isResultBatchBlocked(): Boolean = BuildConfig.DEBUG && batchBlocked

    fun releaseResultBatch() {
        synchronized(batchLock) {
            blockNextBatch = false
            batchRelease.countDown()
        }
    }

    fun reset() {
        if (!BuildConfig.DEBUG) {
            return
        }
        failNextSearchHighlight = false
        navigationActions.set(0)
        pageScanObserver = null
        searchStarts.set(0)
        searchCompletions.set(0)
        releaseResultBatch()
        batchBlocked = false
    }
}
