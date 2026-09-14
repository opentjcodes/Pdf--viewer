// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.load

import android.net.Uri
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection.ReadingDirectionResolver
import java.time.LocalDateTime

internal class DocumentRecords(
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val pdfRepository: PdfRepository,
    private val historyPolicy: HistoryPolicy,
    private val readingDirectionResolver: ReadingDirectionResolver,
    private val source: DocumentSource,
    private val onRecordAvailable: (String) -> Unit,
) {
    private val doc get() = vm.doc
    private val context get() = binding.root.context

    suspend fun persistRecord(
        savePassword: Boolean,
        expectedFileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        val password = if (savePassword) doc.password else null
        val documentTitle = runCatching { binding.pdfView.documentMeta?.title }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }

        if (doc.fileHash == null && expectedFileHash == null) {
            val computedHash = pdfRepository.resolveIdentity(context, doc.uri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            doc.fileHash = computedHash
        }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }

        val fileHash = expectedFileHash ?: doc.fileHash
        if (fileHash == null) {
            Log.e(TAG, "createPdfRecord: Failed to compute fileHash while creating PdfRecord")
            return
        }
        doc.fileHash = fileHash

        if (!historyPolicy.canRecord()) {
            onRecordAvailable(fileHash)
            return
        }

        if (pdfRepository.hasRecord(fileHash)) {
            updateExistingRecord(fileHash, password, documentTitle, loadToken, documentUri)
        }
        else {
            insertNewRecord(fileHash, password, documentTitle, loadToken, documentUri)
        }
    }

    private suspend fun updateExistingRecord(
        fileHash: String,
        password: String?,
        documentTitle: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        pdfRepository.setLastOpened(fileHash, LocalDateTime.now())
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        pdfRepository.setLength(fileHash, doc.length)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        updateRecordUri(fileHash)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        if (documentTitle != null) {
            pdfRepository.setDocumentTitle(fileHash, documentTitle)
        }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        if (password != null) {
            pdfRepository.setPassword(fileHash, password)
        }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        doc.autoScrollSpeed?.let { pdfRepository.setAutoScrollSpeed(fileHash, it) }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        readingDirectionResolver.saveState(fileHash)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        onRecordAvailable(fileHash)
    }

    private suspend fun insertNewRecord(
        fileHash: String,
        password: String?,
        documentTitle: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        val record = doc.toPdfRecord(fileHash, password)
        pdfRepository.saveRecordInBackground(record)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        if (documentTitle != null) {
            pdfRepository.setDocumentTitle(fileHash, documentTitle)
        }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        updateRecordUri(fileHash)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        doc.autoScrollSpeed?.let { pdfRepository.setAutoScrollSpeed(fileHash, it) }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        onRecordAvailable(fileHash)
    }

    private suspend fun updateRecordUri(fileHash: String) {
        val currentUri = doc.uri ?: return
        val canonicalFile = source.canonicalFile(currentUri)
        val durableUri = canonicalFile?.let(Uri::fromFile) ?: currentUri
        val storedUri = pdfRepository.findRecord(fileHash)?.uri
        if (storedUri?.toString() == durableUri.toString()) {
            return
        }
        if (canonicalFile == null && storedUri != null && source.isReadable(storedUri)) {
            return
        }
        pdfRepository.updateRecordIdentity(
            fileHash,
            durableUri,
            doc.name.removeSuffix(".pdf"),
            LocalDateTime.now(),
        )
    }

    private companion object {
        const val TAG = "DocumentLoader"
    }
}
