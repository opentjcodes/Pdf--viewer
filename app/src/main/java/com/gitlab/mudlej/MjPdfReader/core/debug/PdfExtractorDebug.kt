// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

object PdfExtractorDebug {

    private const val NO_PAGE = Int.MIN_VALUE

    private val liveCount = AtomicInteger(0)
    private val extractionCount = AtomicInteger(0)

    @Volatile
    private var failPageNumber = NO_PAGE

    @Volatile
    private var pageDelayMs = 0L


    fun delayEachPageBy(millis: Long) {
        if (!BuildConfig.DEBUG) {
            return
        }
        pageDelayMs = millis.coerceAtLeast(0L)
    }

    fun clearPageDelay() {
        if (!BuildConfig.DEBUG) {
            return
        }
        pageDelayMs = 0L
    }

    fun awaitPageDelay() {
        if (!BuildConfig.DEBUG) {
            return
        }
        val millis = pageDelayMs
        if (millis <= 0L) {
            return
        }
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
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
