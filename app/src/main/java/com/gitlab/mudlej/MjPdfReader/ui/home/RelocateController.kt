// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.core.io.PersistedGrantKeeper
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RelocateController(
    private val activity: AppCompatActivity,
    private val pdfRepository: PdfRepository,
    private val libraryScanner: LibraryScanner,
    private val vm: HomeViewModel,
    private val scope: CoroutineScope,
    private val onOpen: (Uri, String?) -> Unit,
    private val onHealed: () -> Unit,
) {

    private val relocatePicker =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val pendingHash = vm.pendingRelocateHash
            vm.pendingRelocateHash = null
            if (uri != null) {
                scope.launch {
                    val record = pendingHash?.let { pdfRepository.findRecord(it) }
                    if (record != null) {
                        onFilePicked(record, uri)
                    } else {
                        PersistedGrantKeeper.takeReadGrant(activity, uri)
                        onOpen(uri, null)
                    }
                }
            }
        }

    fun handleMissingFile(hash: String) {
        scope.launch {
            val record = pdfRepository.findRecord(hash) ?: return@launch

            val healedPath = libraryScanner.findPathByHash(hash)
            if (healedPath != null) {
                val file = File(healedPath)
                pdfRepository.updateRecordIdentity(
                    hash, Uri.fromFile(file), file.nameWithoutExtension, record.lastOpened
                )
                toast(activity, R.string.home_relocate_found, Toast.LENGTH_SHORT)
                onHealed()
                return@launch
            }

            showRelocateDialog(record)
        }
    }

    private fun showRelocateDialog(record: PdfRecord) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_relocate_title)
            .setMessage(activity.getString(R.string.home_relocate_message, record.fileName))
            .setPositiveButton(R.string.home_relocate_action) { _, _ ->
                vm.pendingRelocateHash = record.hash
                relocatePicker.launch(arrayOf(PDF.FILE_TYPE))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onFilePicked(record: PdfRecord, uri: Uri) {
        scope.launch {
            val identities = DocumentIdentity.of(activity, uri)
            if (identities != null && identities.matches(record.hash)) {
                val pickedHash = pdfRepository.resolveIdentity(identities)
                PersistedGrantKeeper.takeReadGrant(activity, uri)
                val canonicalFile = UriCanonicalizer.canonicalize(activity, uri)
                val durableUri = canonicalFile?.let(Uri::fromFile) ?: uri
                pdfRepository.updateRecordIdentity(
                    pickedHash, durableUri, record.fileName, record.lastOpened
                )
                onHealed()
                onOpen(durableUri, pickedHash)
            } else {
                showMismatchDialog(uri)
            }
        }
    }

    private fun showMismatchDialog(uri: Uri) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_relocate_mismatch_title)
            .setMessage(R.string.home_relocate_mismatch_message)
            .setPositiveButton(R.string.home_open_anyway) { _, _ ->
                PersistedGrantKeeper.takeReadGrant(activity, uri)
                onOpen(uri, null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
