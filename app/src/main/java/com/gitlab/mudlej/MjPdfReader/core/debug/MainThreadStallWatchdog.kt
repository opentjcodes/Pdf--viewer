// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MjPdfPerf"
private const val HEARTBEAT_INTERVAL_MS = 50L
private const val STALL_THRESHOLD_MS = 64L
private const val STACK_DUMP_INTERVAL_MS = 500L

class MainThreadStallWatchdog(private val activityOwned: Boolean = false) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) {
            return
        }
        running = true
        if (BuildConfig.DEBUG && activityOwned) {
            activityOwnedRunning.incrementAndGet()
        }
        thread = Thread(::loop, "MjPdfStallWatchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val wasRunning = running
        running = false
        if (BuildConfig.DEBUG && activityOwned && wasRunning) {
            activityOwnedRunning.decrementAndGet()
        }
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var lastDumpMs = 0L
        while (running) {
            val heartbeat = CountDownLatch(1)
            val postedMs = SystemClock.uptimeMillis()
            mainHandler.post { heartbeat.countDown() }
            try {
                if (!heartbeat.await(STALL_THRESHOLD_MS, TimeUnit.MILLISECONDS)) {
                    val nowMs = SystemClock.uptimeMillis()
                    if (nowMs - lastDumpMs >= STACK_DUMP_INTERVAL_MS) {
                        lastDumpMs = nowMs
                        val stack = Looper.getMainLooper().thread.stackTrace
                            .joinToString("\n") { "    at $it" }
                        Log.w(TAG, "main thread stall\n$stack")
                    }
                    heartbeat.await()
                    Log.w(TAG, "stall ended: ${SystemClock.uptimeMillis() - postedMs}ms")
                }
                Thread.sleep(HEARTBEAT_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    companion object {

        private val shared by lazy { MainThreadStallWatchdog() }
        private val activityOwnedRunning = AtomicInteger()

        fun debugStart() {
            if (!BuildConfig.DEBUG) {
                return
            }
            shared.start()
        }

        fun debugStop() {
            if (!BuildConfig.DEBUG) {
                return
            }
            shared.stop()
        }

        fun debugRunning(): Boolean {
            if (!BuildConfig.DEBUG) {
                return false
            }
            return shared.running
        }

        fun debugActivityOwnedRunningCount(): Int {
            if (!BuildConfig.DEBUG) {
                return 0
            }
            return activityOwnedRunning.get()
        }
    }
}
