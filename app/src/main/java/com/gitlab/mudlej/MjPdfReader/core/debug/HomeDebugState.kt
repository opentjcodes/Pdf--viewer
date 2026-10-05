// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import android.view.View
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeTab
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object HomeDebugState {

    private val generation = AtomicInteger(0)
    private val renderedCounts = ConcurrentHashMap<String, Int>()
    private val uiRevision = AtomicLong()
    @Volatile
    private var publishedAccessRevision = 0L

    @Volatile
    private var publishedStorageAccess = false


    fun noteRendered(tab: HomeTab, count: Int) {
        if (!BuildConfig.DEBUG) {
            return
        }
        renderedCounts[tab.name] = count
    }

    fun notePresentationCommitted(): Long = if (BuildConfig.DEBUG) uiRevision.incrementAndGet() else 0L

    fun publish(
        root: View,
        tab: HomeTab,
        searching: Boolean,
        index: LibraryIndex,
        accessRevision: Long,
        storageAccess: Boolean,
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        publishedAccessRevision = accessRevision
        publishedStorageAccess = storageAccess
        put(
            root,
            generation.incrementAndGet(),
            tab,
            searching,
            index,
            accessRevision,
            storageAccess,
        )
    }

    fun republish(root: View, tab: HomeTab, searching: Boolean, index: LibraryIndex) {
        if (!BuildConfig.DEBUG) {
            return
        }
        put(
            root,
            generation.get(),
            tab,
            searching,
            index,
            publishedAccessRevision,
            publishedStorageAccess,
        )
    }

    fun generation(): Int = generation.get()

    fun renderedCount(tab: HomeTab): Int = renderedCounts[tab.name] ?: 0

    private fun put(
        root: View,
        gen: Int,
        tab: HomeTab,
        searching: Boolean,
        index: LibraryIndex,
        accessRevision: Long,
        storageAccess: Boolean,
    ) {
        DebugRootDescription.put(
            root,
            "home",
            composeHome(gen, tab, searching, accessRevision, storageAccess),
        )
        DebugRootDescription.put(root, "scan", composeScan(index))
    }

    private fun composeHome(
        gen: Int,
        tab: HomeTab,
        searching: Boolean,
        accessRevision: Long,
        storageAccess: Boolean,
    ): String {
        return buildString {
            append("gen=").append(gen)
            append(";items=").append(renderedCount(tab))
            append(";tab=").append(tab.name)
            append(";search=").append(searching)
            append(";accessRevision=").append(accessRevision)
            append(";storageAccess=").append(storageAccess)
        }
    }

    private fun composeScan(index: LibraryIndex): String {
        return buildString {
            append("loaded=").append(index.loaded)
            append(";scanning=").append(index.scanning)
            append(";entries=").append(index.entries.size)
            append(";generation=").append(index.generation)
            append(";phase=").append(index.phase.name)
            append(";found=").append(index.foundCount)
            append(";details=").append(index.detailsCompleted).append('/').append(index.detailsTotal)
            append(";backendRevision=").append(index.revision)
            append(";uiRevision=").append(uiRevision.get())
            append(";showProgress=").append(index.showProgress)
            append(";settlementCount=").append(ScanDebugSignals.settlementCommits())
            append(";sourceFailures=").append(index.sourceFailures.joinToString(",") { it.name })
        }
    }
}
