// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.controls

import android.app.Activity
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.annotation.MainThread
import com.github.barteksc.pdfviewer.CropMarginDetection
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.PDFView.Configurator
import com.github.barteksc.pdfviewer.model.CropMargins
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.ReaderTestHooks
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CropMarginsController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val pdfRepository: PdfRepository,
    private val historyPolicy: HistoryPolicy,
    private val pdf: DocumentState,
    private val scope: CoroutineScope,
    private val isCropMarginsEnabled: () -> Boolean,
    private val setCropMarginsEnabled: (Boolean) -> Unit,
    private val isCurrentDocument: (Long, Uri?) -> Boolean,
    private val reloadWithCropMargins: (Configurator, Int, CropMargins, PDFView.ViewState?) -> Unit,
) {

    @Volatile
    private var pendingCropMargins: PendingCropMargins? = null
    private val detectionStatusState = CropDetectionStatusState()
    private var currentDetection: DetectionRun? = null
    private var nextDetectionRunId = 0L

    init {
        binding.cropDetectionCancelButton.setOnClickListener { cancelByUser() }
    }

    suspend fun findCached(fileHash: String?): CropMargins? {
        if (!isCropMarginsEnabled() || fileHash == null) {
            return null
        }
        val storedCropMargins = pdfRepository.findCropMargins(fileHash, CropMargins.STORAGE_VERSION)
            ?: return null
        return CropMargins.fromStorageString(storedCropMargins)
    }

    @MainThread
    fun startIfNeeded(
        cachedCropMargins: CropMargins?,
        fileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
        pageCount: Int,
    ) {
        if (!isCropMarginsEnabled()
            || cachedCropMargins != null
            || fileHash == null
            || documentUri == null
            || pageCount <= 0
        ) {
            skipDetectionRequest()
            return
        }

        cancel()
        val run = DetectionRun(++nextDetectionRunId, loadToken, documentUri, pdf.password)
        currentDetection = run
        processStatusEvent(CropDetectionStatusEvent.Started(run.id))
        ReaderTestHooks.cropDetectionStarted()

        run.job = scope.launch(Dispatchers.Default) {
            val result = detect(run)
            if (result == null || run.cancelFlag.get()) {
                withContext(Dispatchers.Main) {
                    finishDetection(run, ReaderTestHooks.CROP_CANCELLED)
                }
                return@launch
            }
            val abandoned = withContext(Dispatchers.Main) {
                if (!isCurrentDocument(run.loadToken, run.documentUri)) {
                    finishDetection(run, ReaderTestHooks.CROP_ABANDONED)
                    true
                } else {
                    false
                }
            }
            if (abandoned) {
                return@launch
            }

            save(fileHash, result)

            withContext(Dispatchers.Main) {
                if (!isCurrentDocument(run.loadToken, run.documentUri) || run.cancelFlag.get()) {
                    finishDetection(run, ReaderTestHooks.CROP_ABANDONED)
                    return@withContext
                }
                if (!result.isFullPage && isCropMarginsEnabled()) {
                    finishDetection(run, ReaderTestHooks.CROP_APPLIED) { apply(result) }
                } else {
                    finishDetection(run, ReaderTestHooks.CROP_FULL_PAGE)
                }
            }
        }
    }

    @MainThread
    fun startOrApply(fileHash: String?, loadToken: Long, documentUri: Uri?, pageCount: Int) {
        if (!isCropMarginsEnabled() || fileHash == null || documentUri == null || pageCount <= 0) {
            return
        }

        scope.launch {
            val cachedCropMargins = findCached(fileHash)
            withContext(Dispatchers.Main) {
                if (!isCurrentDocument(loadToken, documentUri) || !isCropMarginsEnabled()) {
                    return@withContext
                }
                if (cachedCropMargins == null) {
                    startIfNeeded(null, fileHash, loadToken, documentUri, pageCount)
                } else if (!cachedCropMargins.isFullPage) {
                    apply(cachedCropMargins)
                }
            }
        }
    }

    @MainThread
    fun cancel() {
        val run = currentDetection
        if (run == null) {
            hideStatus()
            ReaderTestHooks.cropDetectionFinished(ReaderTestHooks.CROP_CANCELLED)
            return
        }
        run.cancelFlag.set(true)
        run.job?.cancel()
        finishDetection(run, ReaderTestHooks.CROP_CANCELLED)
    }

    @MainThread
    internal fun abandonActiveDetection() {
        val run = currentDetection
        if (run == null) {
            hideStatus()
            return
        }
        run.cancelFlag.set(true)
        run.job?.cancel()
        finishDetection(run, ReaderTestHooks.CROP_ABANDONED)
    }

    private fun cancelByUser() {
        cancel()
        if (isCropMarginsEnabled()) {
            setCropMarginsEnabled(false)
        }
    }

    @MainThread
    private fun skipDetectionRequest() {
        abandonActiveDetection()
        processStatusEvent(CropDetectionStatusEvent.Skipped)
        ReaderTestHooks.cropDetectionSkipped()
    }

    suspend fun onRecordAvailable(fileHash: String) {
        if (!historyPolicy.canRecord()) {
            return
        }
        val pending = pendingCropMargins ?: return
        if (pending.fileHash != fileHash) {
            return
        }
        pdfRepository.setCropMargins(
            fileHash,
            pending.cropMargins.toStorageString(),
            pending.cropMargins.version,
        )
        if (pendingCropMargins == pending) {
            pendingCropMargins = null
        }
    }

    private suspend fun detect(run: DetectionRun): CropMargins? {
        return try {
            val openedDocument = openDocument(run.documentUri, run.password) ?: return null
            try {
                val total = openedDocument.core.getPageCount(openedDocument.document)
                CropMarginDetection.detect(
                    openedDocument.core,
                    openedDocument.document,
                    total,
                    { done, pageCount -> updateStatus(run, done, pageCount) },
                    { run.cancelFlag.get() },
                )
            } finally {
                openedDocument.core.closeDocument(openedDocument.document)
            }
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun openDocument(documentUri: Uri, password: String?): OpenedDocument? {
        val pdfiumCore = PdfiumCore(activity)
        val heldFile = OnlineDocumentStore.fileFor(activity, documentUri.toString())
        val fileDescriptor = if (heldFile != null) {
            ParcelFileDescriptor.open(heldFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            activity.contentResolver.openFileDescriptor(documentUri, "r") ?: return null
        }
        val document: PdfDocument = try {
            pdfiumCore.newDocument(fileDescriptor, password)
        } catch (throwable: Throwable) {
            runCatching { fileDescriptor.close() }
            throw throwable
        }
        return OpenedDocument(pdfiumCore, document)
    }

    private fun apply(cropMargins: CropMargins) {
        val uri = pdf.uri ?: return
        val configurator = currentConfigurator(uri) ?: return
        val viewState = binding.pdfView.captureViewState()
        val currentPage = viewState?.pageIndex ?: binding.pdfView.currentPage
        reloadWithCropMargins(configurator, currentPage, cropMargins, viewState)
    }

    private fun currentConfigurator(uri: Uri): Configurator? {
        val file = OnlineDocumentStore.fileFor(activity, uri.toString())
        return if (file != null) {
            binding.pdfView.fromFile(file)
        } else {
            binding.pdfView.fromUri(uri)
        }
    }

    private suspend fun save(fileHash: String, cropMargins: CropMargins) {
        if (!historyPolicy.canRecord()) {
            return
        }
        if (pdfRepository.hasRecord(fileHash)) {
            pdfRepository.setCropMargins(
                fileHash,
                cropMargins.toStorageString(),
                cropMargins.version,
            )
        } else {
            val pending = PendingCropMargins(fileHash, cropMargins)
            pendingCropMargins = pending
            if (pdfRepository.hasRecord(fileHash) && pendingCropMargins == pending) {
                pdfRepository.setCropMargins(
                    fileHash,
                    cropMargins.toStorageString(),
                    cropMargins.version,
                )
                if (pendingCropMargins == pending) {
                    pendingCropMargins = null
                }
            }
        }
    }

    private fun updateStatus(run: DetectionRun, done: Int, total: Int) {
        if (total <= 0 || run.cancelFlag.get()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (done < total && now - run.lastStatusTextUpdate < 150L) {
            return
        }
        run.lastStatusTextUpdate = now

        activity.runOnUiThread {
            if (!isCurrentDocument(run.loadToken, run.documentUri)) {
                return@runOnUiThread
            }
            processStatusEvent(CropDetectionStatusEvent.Progress(run.id, done, total))
        }
    }

    @MainThread
    private fun finishDetection(run: DetectionRun, outcome: String, onFinished: () -> Unit = {}) {
        if (currentDetection !== run) {
            return
        }
        currentDetection = null
        processStatusEvent(CropDetectionStatusEvent.Finished(run.id))
        onFinished()
        ReaderTestHooks.cropDetectionFinished(outcome)
    }

    @MainThread
    private fun processStatusEvent(event: CropDetectionStatusEvent) {
        when (val status = detectionStatusState.process(event)) {
            null -> Unit
            CropDetectionStatusView.Hidden -> hideStatus()
            is CropDetectionStatusView.Progress -> {
                binding.cropDetectionProgress.max = status.total
                binding.cropDetectionProgress.progress = status.done
                binding.cropDetectionStatusText.text = activity.getString(
                    R.string.analyzing_margins_progress,
                    status.done,
                    status.total,
                )
                binding.cropDetectionStatusCard.visibility = View.VISIBLE
            }
        }
    }

    private fun hideStatus() {
        binding.cropDetectionStatusCard.visibility = View.GONE
    }

    private data class PendingCropMargins(val fileHash: String, val cropMargins: CropMargins)

    private data class OpenedDocument(val core: PdfiumCore, val document: PdfDocument)

    private class DetectionRun(
        val id: Long,
        val loadToken: Long,
        val documentUri: Uri,
        val password: String?,
        val cancelFlag: AtomicBoolean = AtomicBoolean(false),
        var job: Job? = null,
        var lastStatusTextUpdate: Long = 0L,
    )
}

internal sealed interface CropDetectionStatusEvent {
    data class Started(val runId: Long) : CropDetectionStatusEvent
    data class Progress(val runId: Long, val done: Int, val total: Int) : CropDetectionStatusEvent
    data class Finished(val runId: Long) : CropDetectionStatusEvent
    data object Skipped : CropDetectionStatusEvent
}

internal sealed interface CropDetectionStatusView {
    data object Hidden : CropDetectionStatusView
    data class Progress(val done: Int, val total: Int) : CropDetectionStatusView
}

internal class CropDetectionStatusState {
    private var runningRunId: Long? = null

    var view: CropDetectionStatusView = CropDetectionStatusView.Hidden
        private set

    fun process(event: CropDetectionStatusEvent): CropDetectionStatusView? {
        val next = when (event) {
            is CropDetectionStatusEvent.Started -> {
                runningRunId = event.runId
                CropDetectionStatusView.Hidden
            }
            is CropDetectionStatusEvent.Progress -> {
                if (runningRunId != event.runId || event.total <= 0) {
                    return null
                }
                CropDetectionStatusView.Progress(event.done.coerceIn(0, event.total), event.total)
            }
            is CropDetectionStatusEvent.Finished -> {
                if (runningRunId != event.runId) {
                    return null
                }
                runningRunId = null
                CropDetectionStatusView.Hidden
            }
            CropDetectionStatusEvent.Skipped -> {
                runningRunId = null
                CropDetectionStatusView.Hidden
            }
        }
        view = next
        return next
    }
}
