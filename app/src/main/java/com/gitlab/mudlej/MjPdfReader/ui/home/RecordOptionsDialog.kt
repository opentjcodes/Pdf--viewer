// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.databinding.DialogRecordOptionsBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogRenameRecordBinding
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.HistoryCleaner
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.ui.reader.showMetaDialog
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.appDateFormatter
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentity
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentRemover
import com.gitlab.mudlej.MjPdfReader.core.io.pdfShareIntent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordOptionsDialog(
    private val activity: AppCompatActivity,
    private val pdfRepository: PdfRepository,
    private val coverCache: CoverCache,
    private val libraryScanner: LibraryScanner,
    private val historyPolicy: HistoryPolicy,
    private val historyCleaner: HistoryCleaner,
    private val scope: CoroutineScope,
    private val onOpenIncognito: (HomeItem) -> Unit,
    private val onChanged: () -> Unit,
) {

    fun show(item: HomeItem) {
        scope.launch {
            val record = pdfRepository.findRecord(item.hash)
            buildAndShow(item, record)
        }
    }

    private fun buildAndShow(item: HomeItem, record: PdfRecord?) {
        val binding = DialogRecordOptionsBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.CompactMaterialAlertDialog)
            .setView(binding.root)
            .create()

        binding.optionsTitle.text = item.title
        binding.optionsInfo.text = buildInfoLine(item, item.length)
        coverCache.bind(binding.optionsCover, item.coverKey, item.uri, COVER_WIDTH_PX, scope)

        if (item.length <= 0) {
            scope.launch {
                val meta = withContext(Dispatchers.IO) { readDocumentMeta(item.uri) }
                val pages = meta?.totalPages ?: 0
                if (pages > 0) {
                    binding.optionsInfo.text = buildInfoLine(item, pages)
                }
            }
        }

        if (item.progressPercent > 0) {
            binding.optionsProgress.visibility = View.VISIBLE
            binding.optionsProgress.progress = item.progressPercent
            binding.optionsPercent.visibility = View.VISIBLE
            binding.optionsPercent.text = activity.getString(
                R.string.home_percent_position_template,
                item.progressPercent,
                item.pageNumber + 1,
                item.length,
            )
        }

        if (item.hasBeenOpened) {
            binding.optionsLastOpened.visibility = View.VISIBLE
            binding.optionsLastOpened.text = activity.getString(
                R.string.home_last_opened, item.lastOpened.format(appDateFormatter)
            )
        }

        bindStatus(binding, item, record)

        if (item.hasBeenOpened && record != null) {
            binding.removeRecentRow.visibility = View.VISIBLE
            binding.removeRecentRow.setOnClickListener {
                dialog.dismiss()
                scope.launch {
                    pdfRepository.setLastOpened(
                        record.hash, LocalDateTime.parse(PdfRecord.UNSET_DATE)
                    )
                    onChanged()
                }
            }
        }

        binding.hideRowLabel.setText(
            if (record?.hidden == true) {
                R.string.home_show_in_library
            } else {
                R.string.home_hide_from_library
            }
        )
        binding.hideRow.setOnClickListener {
            dialog.dismiss()
            scope.launch {
                val hash = record?.hash ?: resolveContentHash(item) ?: return@launch
                if (record == null && !pdfRepository.hasRecord(hash)) {
                    if (!historyPolicy.canRecord()) {
                        toast(activity, R.string.history_action_blocked, Toast.LENGTH_SHORT)
                        return@launch
                    }
                    pdfRepository.saveRecordInBackground(newRecord(item, hash))
                }
                pdfRepository.setHidden(hash, !(record?.hidden ?: false))
                onChanged()
            }
        }

        binding.openIncognitoRow.setOnClickListener {
            dialog.dismiss()
            onOpenIncognito(item)
        }

        binding.sharePdfRow.setOnClickListener {
            dialog.dismiss()
            sharePdf(item, record)
        }

        binding.optionsInfoButton.setOnClickListener { showFullProperties(item, record) }

        if (item.uri.scheme == "file") {
            binding.renameRow.setOnClickListener {
                dialog.dismiss()
                showRenameDialog(item, record)
            }
        } else {
            binding.renameRow.visibility = View.GONE
        }

        binding.deleteRow.setOnClickListener {
            dialog.dismiss()
            confirmDelete(item, record)
        }

        dialog.show()
    }

    private fun buildInfoLine(item: HomeItem, length: Int): String {
        val size = fileSizeBytes(item.uri)
        val sizeText = size?.let { String.format(Locale.US, "%.2f MB", it / (1024.0 * 1024.0)) }
        return when {
            length > 0 && sizeText != null ->
                activity.getString(R.string.home_pages_size_template, length, sizeText)
            length > 0 ->
                activity.resources.getQuantityString(R.plurals.home_pages, length, length)
            else -> sizeText.orEmpty()
        }
    }

    private fun bindStatus(binding: DialogRecordOptionsBinding, item: HomeItem, record: PdfRecord?) {
        val statuses = listOf(
            ReadingStatus.UNSET to activity.getString(R.string.none),
            ReadingStatus.TO_READ to activity.getString(R.string.home_chip_to_read),
            ReadingStatus.READING to activity.getString(R.string.home_chip_reading),
            ReadingStatus.ON_HOLD to activity.getString(R.string.home_chip_on_hold),
            ReadingStatus.COMPLETED to activity.getString(R.string.home_chip_completed),
            ReadingStatus.ABANDONED to activity.getString(R.string.home_chip_abandoned),
        )
        binding.statusDropdown.setSimpleItems(statuses.map { it.second }.toTypedArray())

        var currentStatus = record?.reading ?: ReadingStatus.UNSET
        binding.statusDropdown.setText(statuses.first { it.first == currentStatus }.second, false)

        var resolvedHash = record?.hash
        binding.statusDropdown.setOnItemClickListener { _, _, position, _ ->
            val status = statuses[position].first
            if (status == currentStatus) {
                return@setOnItemClickListener
            }
            currentStatus = status
            scope.launch {
                val hash = resolvedHash ?: resolveContentHash(item) ?: return@launch
                resolvedHash = hash
                if (!pdfRepository.hasRecord(hash)) {
                    if (!historyPolicy.canRecord()) {
                        toast(activity, R.string.history_action_blocked, Toast.LENGTH_SHORT)
                        return@launch
                    }
                    pdfRepository.saveRecordInBackground(newRecord(item, hash))
                }
                pdfRepository.setReading(hash, status)
                onChanged()
            }
        }
    }

    suspend fun ensureRecordHash(item: HomeItem): String? {
        if (!item.isScanOnly) {
            return item.hash
        }
        val hash = resolveContentHash(item) ?: return null
        if (!pdfRepository.hasRecord(hash)) {
            if (!historyPolicy.canRecord()) {
                return null
            }
            pdfRepository.saveRecordInBackground(newRecord(item, hash))
        }
        return hash
    }

    private suspend fun resolveContentHash(item: HomeItem): String? {
        val path = item.uri.path ?: return null
        val fingerprint = libraryScanner.fingerprintFor(path) ?: return null
        val known = libraryScanner.index.value.entries.find { it.path == path }?.hash
            ?.takeIf { !DocumentIdentity.isLegacy(it) }
        if (known != null) {
            return known
        }
        val computed = withContext(Dispatchers.IO) { DocumentIdentity.of(File(path))?.identity } ?: return null
        libraryScanner.onHashComputed(fingerprint, computed)
        return computed
    }

    private fun newRecord(item: HomeItem, hash: String): PdfRecord {
        return PdfRecord(
            hash,
            0,
            item.uri,
            item.length,
            fileNameOf(item, null),
            null,
            LocalDateTime.parse(PdfRecord.UNSET_DATE),
            ReadingStatus.UNSET,
            false,
        )
    }

    private fun showFullProperties(item: HomeItem, record: PdfRecord?) {
        scope.launch {
            val meta = withContext(Dispatchers.IO) { readDocumentMeta(item.uri) }
            val filePath = withContext(Dispatchers.IO) {
                UriCanonicalizer.canonicalize(activity, item.uri)?.absolutePath
            }
            showMetaDialog(activity, meta, fileNameOf(item, record), fileSizeBytes(item.uri), filePath = filePath)
        }
    }

    private fun fileNameOf(item: HomeItem, record: PdfRecord?): String {
        return record?.fileName
            ?: item.uri.path?.let { File(it).nameWithoutExtension }
            ?: item.title
    }

    private fun readDocumentMeta(uri: Uri): PdfDocument.Meta? {
        return runCatching {
            val core = PdfiumCore(activity)
            val fd = activity.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val document = try {
                core.newDocument(fd)
            } catch (throwable: Throwable) {
                runCatching { fd.close() }
                return null
            }
            try {
                core.getDocumentMeta(document)
            } finally {
                core.closeDocument(document)
            }
        }.getOrNull()
    }

    private fun fileSizeBytes(uri: Uri): Long? {
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it).length() }
            else -> runCatching {
                activity.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()
        }?.takeIf { it > 0 }
    }

    private fun sharePdf(item: HomeItem, record: PdfRecord?) {
        scope.launch {
            try {
                activity.startActivity(pdfShareIntent(activity, item.uri, fileNameOf(item, record)))
            } catch (e: Throwable) {
                toast(activity, R.string.home_share_failed, Toast.LENGTH_SHORT)
            }
        }
    }

    private fun showRenameDialog(item: HomeItem, record: PdfRecord?) {
        val binding = DialogRenameRecordBinding.inflate(activity.layoutInflater)
        binding.renameInput.setText(fileNameOf(item, record))

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_rename)
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newName = binding.renameInput.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && !newName.contains(File.separatorChar)) {
                    performRename(item, record, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performRename(item: HomeItem, record: PdfRecord?, newName: String) {
        scope.launch {
            val path = item.uri.path ?: return@launch
            val oldFile = File(path)
            val target = File(oldFile.parentFile, "$newName.pdf")

            if (target.exists()) {
                toast(activity, R.string.home_rename_exists, Toast.LENGTH_SHORT)
                return@launch
            }
            val renamed = withContext(Dispatchers.IO) { oldFile.renameTo(target) }
            if (!renamed) {
                toast(activity, R.string.home_rename_failed, Toast.LENGTH_SHORT)
                return@launch
            }

            if (record != null) {
                pdfRepository.updateRecordIdentity(
                    record.hash, Uri.fromFile(target), newName, record.lastOpened
                )
                pdfRepository.setDocumentTitle(record.hash, null)
            }
            libraryScanner.onFileRenamed(oldFile.absolutePath, target.absolutePath)
            onChanged()
        }
    }

    private fun confirmDelete(item: HomeItem, record: PdfRecord?) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_dialog_title)
            .setMessage(
                activity.getString(R.string.home_delete_confirm_message, fileNameOf(item, record))
            )
            .setPositiveButton(R.string.delete) { _, _ -> performDelete(item, record) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDelete(item: HomeItem, record: PdfRecord?) {
        scope.launch {
            val removal = withContext(Dispatchers.IO) {
                DocumentRemover.remove(activity, item.uri)
            }
            if (!removal.deleted) {
                toast(activity, R.string.home_delete_failed, Toast.LENGTH_SHORT)
                return@launch
            }

            historyCleaner.deleteDocument(record?.hash ?: item.hash)
            coverCache.invalidate(item.coverKey)
            removal.path?.let { libraryScanner.onFileRemoved(it) }
            onChanged()
        }
    }

    companion object {
        private const val COVER_WIDTH_PX = 256
    }
}
