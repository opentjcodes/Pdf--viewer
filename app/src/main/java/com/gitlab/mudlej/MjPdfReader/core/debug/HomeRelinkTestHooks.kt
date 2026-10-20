// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

object HomeRelinkTestHooks {
    private val starts = AtomicInteger(0)
    private val completions = AtomicInteger(0)
    private val active = AtomicInteger(0)
    private val gate = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun blockNextRelink() {
        if (BuildConfig.DEBUG) gate.set(CompletableDeferred())
    }

    fun noteStarted() {
        if (!BuildConfig.DEBUG) return
        starts.incrementAndGet()
        active.incrementAndGet()
    }

    suspend fun awaitRelease() {
        if (!BuildConfig.DEBUG) return
        val pending = gate.get() ?: return
        pending.await()
        gate.compareAndSet(pending, null)
    }

    fun release() {
        gate.getAndSet(null)?.complete(Unit)
    }

    fun noteFinished() {
        if (!BuildConfig.DEBUG) return
        completions.incrementAndGet()
        active.decrementAndGet()
    }

    fun starts(): Int = starts.get()

    fun completions(): Int = completions.get()

    fun isRunning(): Boolean = active.get() > 0

    fun reset() {
        starts.set(0)
        completions.set(0)
        active.set(0)
        gate.getAndSet(null)?.complete(Unit)
    }
}
