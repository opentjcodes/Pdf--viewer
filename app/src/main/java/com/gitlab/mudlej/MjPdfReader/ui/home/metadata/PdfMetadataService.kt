// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.shockwave.pdfium.PdfiumCore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class PdfMetadataService : Service() {
    private enum class State { IDLE, PROCESSING, WAITING_FOR_ACK, EXITING }

    private val executor = Executors.newSingleThreadExecutor()
    private val state = AtomicReference(State.IDLE)
    private val workerInstance = SystemClock.elapsedRealtimeNanos()
    private lateinit var extractor: PdfMetadataExtractor
    private var callback: IPdfMetadataCallback? = null
    private var baselineNativePssBytes = 0L
    private var waitingFingerprint: SourceFingerprint? = null

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
        state.set(State.EXITING)
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
            if (state.get() != State.IDLE) return
            executor.execute {
                if (state.get() != State.IDLE) return@execute
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
            check(state.compareAndSet(State.IDLE, State.PROCESSING)) {
                "Metadata worker received concurrent work"
            }
            executor.execute {
                processDocument(
                    Request(generation, connectionToken, SourceFingerprint(path, size, lastModified), debugBehavior)
                )
            }
        }

        override fun acknowledge(
            generation: Long,
            connectionToken: Long,
            path: String,
            size: Long,
            lastModified: Long,
            action: Int,
        ) {
            check(state.get() == State.WAITING_FOR_ACK)
            check(waitingFingerprint == SourceFingerprint(path, size, lastModified))
            waitingFingerprint = null
            val acknowledged = runCatching {
                callback?.onAcknowledged(generation, connectionToken)
            }.isSuccess
            if (!acknowledged) {
                exitWorker()
                return
            }
            if (action == MetadataProtocol.ACK_CONTINUE) {
                state.set(State.IDLE)
            } else {
                exitWorker()
            }
        }
    }

    private fun processDocument(request: Request) {
        if (request.debugBehavior == MetadataProtocol.DEBUG_KILL) {
            Process.killProcess(Process.myPid())
            return
        }
        val result = extractor.extract(request.fingerprint)
        val memory = memorySample()
        waitingFingerprint = request.fingerprint
        state.set(State.WAITING_FOR_ACK)
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
        if (state.getAndSet(State.EXITING) == State.EXITING) return
        stopSelf()
        Process.killProcess(Process.myPid())
    }
}
