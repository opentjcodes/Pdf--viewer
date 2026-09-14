// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import android.content.Context
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object BackupDebugHooks {

    const val SUPPRESS_RESTART_KEY = "debug_backup_suppress_restart"

    const val OUTCOME_REJECTED = "rejected"
    const val OUTCOME_SAFETY_ABORTED = "safety-aborted"
    const val OUTCOME_IMPORTED = "imported"
    const val OUTCOME_FAILED_AFTER_WIPE = "failed-after-wipe"

    @Volatile
    var suppressRestart = false

    private val prepareImportFailurePending = AtomicBoolean(false)
    private val importCompletions = AtomicInteger(0)

    @Volatile
    private var importRunning = false

    @Volatile
    private var lastImportOutcome = ""

    fun shouldSuppressRestart(context: Context): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        if (suppressRestart) {
            return true
        }
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(SUPPRESS_RESTART_KEY, false)
    }

    fun failNextPrepareImport() {
        if (!BuildConfig.DEBUG) {
            return
        }
        prepareImportFailurePending.set(true)
    }

    fun consumePrepareImportFailure(): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        return prepareImportFailurePending.compareAndSet(true, false)
    }

    fun noteImportStarted() {
        if (!BuildConfig.DEBUG) {
            return
        }
        importRunning = true
    }

    fun noteImportFinished(outcome: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        lastImportOutcome = outcome
        importRunning = false
        importCompletions.incrementAndGet()
    }

    fun importRunning(): Boolean = importRunning

    fun importCompletions(): Int = importCompletions.get()

    fun lastImportOutcome(): String = lastImportOutcome
}
