// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ScanBatch(
    private val generation: Long,
    private val source: DiscoverySource,
    private val emit: suspend (ScanEvent) -> Unit,
    private val scope: CoroutineScope,
) {
    private val entries = LinkedHashMap<String, ScannedPdfEntry>()
    private val mutex = Mutex()
    private var timer: Job? = null

    suspend fun add(entry: ScannedPdfEntry) {
        val snapshot = mutex.withLock {
            if (entries.isEmpty()) {
                timer = scope.launch {
                    delay(EMIT_INTERVAL_MILLIS)
                    flushFromTimer()
                }
            }
            entries[entry.path] = entry
            if (entries.size < EMIT_BATCH_SIZE) return@withLock null
            timer?.cancel()
            timer = null
            drainLocked()
        }
        snapshot?.let { emit(ScanEvent.DiscoveryBatch(generation, source, it)) }
    }

    private suspend fun flushFromTimer() {
        mutex.withLock {
            val snapshot = drainLocked()
            if (snapshot.isNotEmpty()) {
                emit(ScanEvent.DiscoveryBatch(generation, source, snapshot))
            }
        }
    }

    suspend fun flush() {
        val snapshot = mutex.withLock {
            timer?.cancel()
            timer = null
            drainLocked()
        }
        if (snapshot.isNotEmpty()) emit(ScanEvent.DiscoveryBatch(generation, source, snapshot))
    }

    suspend fun close() {
        withContext(NonCancellable) { timer?.cancelAndJoin() }
    }

    private fun drainLocked(): List<ScannedPdfEntry> {
        if (entries.isEmpty()) return emptyList()
        val snapshot = entries.values.toList()
        entries.clear()
        return snapshot
    }

    companion object {
        private const val EMIT_BATCH_SIZE = 50
        private const val EMIT_INTERVAL_MILLIS = 300L
    }
}
