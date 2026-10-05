// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

object PdfExtractorDebug {

    private const val NO_PAGE = Int.MIN_VALUE

    private val liveCount = AtomicInteger(0)
    private val extractionCount = AtomicInteger(0)

    @Volatile
    private var failPageNumber = NO_PAGE

    private val setupGateLock = Any()
    private var setupGate: SetupGate? = null

    private class SetupGate {
        var claimed = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    fun blockNextTextModeSetup() {
        if (!BuildConfig.DEBUG) return
        synchronized(setupGateLock) {
            setupGate?.release?.countDown()
            setupGate = SetupGate()
        }
    }

    fun awaitTextModeSetupGate() {
        if (!BuildConfig.DEBUG) return
        val gate = synchronized(setupGateLock) {
            val current = setupGate ?: return
            if (current.claimed) return
            current.claimed = true
            current
        }
        gate.entered.countDown()
        gate.release.await()
    }

    fun isTextModeSetupBlocked(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return synchronized(setupGateLock) {
            setupGate?.let { it.entered.count == 0L && it.release.count > 0L } == true
        }
    }

    fun releaseTextModeSetup() {
        if (!BuildConfig.DEBUG) return
        synchronized(setupGateLock) {
            setupGate?.release?.countDown()
            setupGate = null
        }
    }

    fun liveInstances(): Int {
        if (!BuildConfig.DEBUG) {
            return 0
        }
        return liveCount.get()
    }

    fun pageExtractions(): Int {
        if (!BuildConfig.DEBUG) {
            return 0
        }
        return extractionCount.get()
    }

    fun resetPageExtractions() {
        if (!BuildConfig.DEBUG) {
            return
        }
        extractionCount.set(0)
    }

    fun failNextPageTextByNumber(pageNumber: Int) {
        if (!BuildConfig.DEBUG) {
            return
        }
        failPageNumber = pageNumber
    }

    fun failNextPageTextByIndex(pageIndex: Int) {
        failNextPageTextByNumber(pageIndex + 1)
    }

    fun isPageTextFaultArmed(): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        return failPageNumber != NO_PAGE
    }

    fun clearPageTextFault() {
        if (!BuildConfig.DEBUG) {
            return
        }
        failPageNumber = NO_PAGE
    }


    fun onExtractorCreated() {
        if (!BuildConfig.DEBUG) {
            return
        }
        liveCount.incrementAndGet()
    }

    fun onExtractorClosed() {
        if (!BuildConfig.DEBUG) {
            return
        }
        liveCount.decrementAndGet()
    }

    fun onPageExtraction() {
        if (!BuildConfig.DEBUG) {
            return
        }
        extractionCount.incrementAndGet()
    }

    @Synchronized
    fun consumePageTextFault(pageNumber: Int): Boolean {
        if (!BuildConfig.DEBUG || failPageNumber != pageNumber) {
            return false
        }
        failPageNumber = NO_PAGE
        return true
    }
}
