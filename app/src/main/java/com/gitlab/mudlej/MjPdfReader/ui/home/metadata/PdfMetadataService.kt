// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.shockwave.pdfium.PdfiumCore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PdfMetadataService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val watchdog = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
    }
    private val state = MetadataWorkerState()
    private val workerInstance = SystemClock.elapsedRealtimeNanos()
    private lateinit var extractor: PdfMetadataExtractor
    private var callback: IPdfMetadataCallback? = null
    private var baselineNativePssBytes = 0L

    private data class Request(
        val generation: Long,
        val connectionToken: Long,
        val fingerprint: SourceFingerprint,
        val debugBehavior: Int,
    )

    override fun onCreate() {
        super.onCreate()
        extractor = PdfMetadataExtractor(PdfiumCore(this))
        baselineNativePssBytes = nativePssBytes()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        exitWorker()
        return false
    }

    override fun onDestroy() {
        state.exit()
        watchdog.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    private val binder = object : IPdfMetadataService.Stub() {
        override fun registerCallback(
            value: IPdfMetadataCallback?,
            generation: Long,
            connectionToken: Long,
        ) {
            callback = value
            runCatching {
                value?.asBinder()?.linkToDeath({ exitWorker() }, 0)
                deliverMemorySample(generation, connectionToken, ready = true)
            }.onFailure { exitWorker() }
        }

        override fun sampleMemory(generation: Long, connectionToken: Long) {
            if (!state.isIdle()) return
            executor.execute {
                if (!state.isIdle()) return@execute
                runCatching {
                    deliverMemorySample(generation, connectionToken, ready = false)
                }.onFailure { exitWorker() }
            }
        }

        override fun process(
            generation: Long,
            connectionToken: Long,
            path: String,
            size: Long,
            lastModified: Long,
            debugBehavior: Int,
        ) {
            val request = Request(generation, connectionToken, SourceFingerprint(path, size, lastModified), debugBehavior)
            val attempt = state.begin(request.fingerprint)
            runCatching {
                val deadline = watchdog.schedule({
                    if (state.expire(attempt)) {
                        try {
                            Log.w("PdfMetadataService", "Metadata watchdog expired after ${EXTRACTION_TIMEOUT_MS}ms; generation=$generation token=$connectionToken")
                        } finally {
                            Process.killProcess(Process.myPid())
                        }
                    }
                }, EXTRACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                executor.execute { processDocument(request, attempt, deadline) }
            }.onFailure { exitWorker() }
        }

        override fun acknowledge(
            generation: Long,
            connectionToken: Long,
            path: String,
            size: Long,
            lastModified: Long,
            action: Int,
        ) {
            val continueWork = action == MetadataProtocol.ACK_CONTINUE
            if (!state.acknowledge(SourceFingerprint(path, size, lastModified), continueWork)) return
            val acknowledged = runCatching {
                callback?.onAcknowledged(generation, connectionToken)
            }.isSuccess
            if (!acknowledged) {
                exitWorker()
                return
            }
            if (!continueWork) {
                exitWorker()
            }
        }
    }

    private fun processDocument(
        request: Request,
        attempt: MetadataWorkerState.Attempt,
        deadline: ScheduledFuture<*>,
    ) {
        if (BuildConfig.DEBUG && request.debugBehavior == MetadataProtocol.DEBUG_KILL) {
            Process.killProcess(Process.myPid())
            return
        }
        if (BuildConfig.DEBUG && request.debugBehavior == MetadataProtocol.DEBUG_HANG) {
            val neverReleased = CountDownLatch(1)
            while (true) {
                try {
                    neverReleased.await()
                } catch (_: InterruptedException) {
                }
            }
        }
        val result = extractor.extract(request.fingerprint)
        val memory = memorySample()
        if (!state.complete(attempt)) return
        deadline.cancel(false)
        deliverResult(request, result, memory)
    }

    private fun deliverResult(
        request: Request,
        result: PdfMetadataExtractor.Result,
        memory: MetadataMemorySample,
    ) {
        runCatching {
            callback?.onResult(
                request.generation,
                request.connectionToken,
                request.fingerprint.path,
                request.fingerprint.size,
                request.fingerprint.lastModified,
                result.hash,
                result.pageCount,
                result.failure,
                Process.myPid(),
                workerInstance,
                baselineNativePssBytes,
                extractor.maxActiveDocuments,
                memory.nativePssBytes,
                memory.availableBytes,
                memory.thresholdBytes,
                memory.totalBytes,
                memory.lowMemory,
            )
        }.onFailure { exitWorker() }
    }

    private fun deliverMemorySample(generation: Long, connectionToken: Long, ready: Boolean) {
        val memory = memorySample()
        val client = callback ?: return
        val deliver = if (ready) client::onReady else client::onMemorySample
        deliver(
            generation,
            connectionToken,
            Process.myPid(),
            workerInstance,
            baselineNativePssBytes,
            memory.nativePssBytes,
            memory.availableBytes,
            memory.thresholdBytes,
            memory.totalBytes,
            memory.lowMemory,
        )
    }

    private fun memorySample(): MetadataMemorySample {
        val memoryInfo = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        return MetadataMemorySample(
            availableBytes = memoryInfo.availMem,
            thresholdBytes = memoryInfo.threshold,
            totalBytes = memoryInfo.totalMem,
            lowMemory = memoryInfo.lowMemory,
            baselineNativePssBytes = baselineNativePssBytes,
            nativePssBytes = nativePssBytes(),
        )
    }

    private fun nativePssBytes(): Long {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.nativePss * 1_024L
    }

    private fun exitWorker() {
        state.exit()
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val EXTRACTION_TIMEOUT_MS = 5_000L
    }
}
