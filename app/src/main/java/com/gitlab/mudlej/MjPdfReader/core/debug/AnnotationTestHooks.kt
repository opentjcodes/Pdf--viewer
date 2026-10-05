// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import android.content.Context
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred

object AnnotationTestHooks {
    const val SAVE_FAULT_MODE_KEY = "debugSaveFaultMode"
    const val FAULT_NONE = "none"
    const val FAULT_FAIL_WRITE = "failWrite"
    const val FAULT_CORRUPT_WRITE = "corruptWrite"
    const val FAULT_FAIL_VERIFY = "failVerify"
    const val FAULT_SLOW_WRITE = "slowWrite"

    class SaveFault internal constructor(internal val mode: String, internal val generation: Long)

    private val lock by lazy { ReentrantLock() }
    private val slowWriteReleased by lazy { lock.newCondition() }
    private var generation = 0L
    private var slowWritePermits = 0
    private var slowWriteWaiters = 0
    private var identityGate: CompletableDeferred<Unit>? = null
    private var failNextHighlight = false

    fun failNextHighlightWrite() {
        if (!BuildConfig.DEBUG) return
        lock.withLock { failNextHighlight = true }
    }

    fun consumeHighlightWriteFailure(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return lock.withLock {
            val fail = failNextHighlight
            failNextHighlight = false
            fail
        }
    }

    fun saveFault(context: Context): SaveFault {
        if (!BuildConfig.DEBUG) return SaveFault(FAULT_NONE, 0)
        return lock.withLock {
            val mode = runCatching {
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(SAVE_FAULT_MODE_KEY, FAULT_NONE)
            }.getOrNull().orEmpty().ifEmpty { FAULT_NONE }
            SaveFault(mode, generation)
        }
    }

    fun saveFault(mode: String): SaveFault {
        if (!BuildConfig.DEBUG) return SaveFault(FAULT_NONE, 0)
        return lock.withLock { SaveFault(mode, generation) }
    }

    fun setSaveFaultMode(context: Context, mode: String) {
        if (!BuildConfig.DEBUG) return
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(SAVE_FAULT_MODE_KEY, mode)
            .commit()
    }

    fun clearSaveFaultMode(context: Context) {
        if (!BuildConfig.DEBUG) return
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(SAVE_FAULT_MODE_KEY)
            .commit()
    }

    fun copySaveBytes(fault: SaveFault, input: InputStream, output: OutputStream) {
        if (!BuildConfig.DEBUG) {
            input.copyTo(output)
            return
        }
        when (fault.mode) {
            FAULT_FAIL_WRITE -> throw IOException("$SAVE_FAULT_MODE_KEY=$FAULT_FAIL_WRITE")
            FAULT_CORRUPT_WRITE -> copyTruncated(input, output)
            else -> input.copyTo(output)
        }
    }

    fun verificationFails(fault: SaveFault): Boolean = BuildConfig.DEBUG && fault.mode == FAULT_FAIL_VERIFY

    fun isSlowWriteBlocked(): Boolean = BuildConfig.DEBUG && lock.withLock { slowWriteWaiters > 0 }

    fun releaseSlowWrite() {
        if (!BuildConfig.DEBUG) return
        lock.withLock {
            slowWritePermits++
            slowWriteReleased.signalAll()
        }
    }

    fun holdIdentityResolution() {
        if (!BuildConfig.DEBUG) return
        lock.withLock {
            if (identityGate == null) identityGate = CompletableDeferred()
        }
    }

    fun isIdentityResolutionHeld(): Boolean = BuildConfig.DEBUG && lock.withLock { identityGate != null }

    fun releaseIdentityResolution() {
        if (!BuildConfig.DEBUG) return
        val gate = lock.withLock {
            val held = identityGate
            identityGate = null
            held
        }
        gate?.complete(Unit)
    }

    suspend fun awaitIdentityRelease() {
        if (!BuildConfig.DEBUG) return
        val gate = lock.withLock { identityGate }
        gate?.await()
    }

    fun reset() {
        if (!BuildConfig.DEBUG) return
        val gate = lock.withLock {
            generation++
            slowWritePermits = 0
            slowWriteReleased.signalAll()
            failNextHighlight = false
            val held = identityGate
            identityGate = null
            held
        }
        gate?.complete(Unit)
    }

    fun awaitSaveWrite(fault: SaveFault) {
        if (!BuildConfig.DEBUG || fault.mode != FAULT_SLOW_WRITE) return
        lock.withLock {
            slowWriteWaiters++
            try {
                while (fault.generation == generation && slowWritePermits == 0) {
                    slowWriteReleased.await()
                }
                if (fault.generation != generation) throw IOException("Slow write cancelled by reset")
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                slowWritePermits--
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Slow write interrupted").apply { initCause(error) }
            } finally {
                slowWriteWaiters--
            }
        }
    }

    private fun copyTruncated(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE + CORRUPT_WRITE_DROP_BYTES)
        var retained = 0
        while (true) {
            val read = input.read(buffer, retained, buffer.size - retained)
            if (read < 0) return
            val total = retained + read
            val written = (total - CORRUPT_WRITE_DROP_BYTES).coerceAtLeast(0)
            if (written > 0) output.write(buffer, 0, written)
            buffer.copyInto(buffer, 0, written, total)
            retained = total - written
        }
    }

    private const val CORRUPT_WRITE_DROP_BYTES = 512
}
