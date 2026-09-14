// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toUri
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.PreviewDiskCoordinator
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfAnnotationSaveDestination
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.ui.reader.PostSaveAction
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.gitlab.mudlej.MjPdfReader.core.debug.AnnotationTestHooks
import com.gitlab.mudlej.MjPdfReader.core.debug.AnnotationTestHooks.SaveFault
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.canWriteToDownloadFolder
import com.gitlab.mudlej.MjPdfReader.core.io.computeHash
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentity
import com.gitlab.mudlej.MjPdfReader.core.io.computeTailHash
import com.gitlab.mudlej.MjPdfReader.core.io.getFileName
import com.gitlab.mudlej.MjPdfReader.core.io.publicDownloadsCopy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SaveOutcome {
    data class Success(
        val destinationUri: Uri,
        val destinationName: String,
        val newHash: String,
        val sizeBytes: Long,
        val isCurrent: Boolean,
        val saveDestinationDurable: Boolean,
    ) : SaveOutcome

    data class Failure(
        val rescueFileName: String?,
        val unconfirmed: Boolean,
        val overwritesSource: Boolean = false,
    ) : SaveOutcome
}

class AnnotationSaveController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val pdf: DocumentState,
    private val annotationController: AnnotationController,
    private val pdfRepository: PdfRepository,
    private val historyPolicy: HistoryPolicy,
    private val vm: ReaderViewModel,
    private val scope: CoroutineScope,
    private val backgroundSaveScope: CoroutineScope,
    private val updateDestinationLauncher: ActivityResultLauncher<Intent>,
    private val createDestinationLauncher: ActivityResultLauncher<Intent>,
    private val clearActiveSearchResultHighlight: () -> Unit,
    private val updateDirtyUi: () -> Unit,
    private val beforeSave: () -> Boolean,
    private val runPostSaveAction: (PostSaveAction, Uri?) -> Unit,
    private val onDocumentSaved: () -> Unit,
) {
    private sealed interface WriteResult {
        data class Success(val hash: String, val identity: String, val sizeBytes: Long) : WriteResult
        data class Failure(val rescueFileName: String?, val unconfirmed: Boolean) : WriteResult
    }

    fun saveHighlights(postSaveAction: PostSaveAction? = null, actionUri: Uri? = null) {
        if (!annotationController.hasUnsavedAnnotations || annotationController.isSaving) {
            return
        }
        if (!beforeSave()) {
            return
        }
        vm.pendingPostSaveAction = postSaveAction
        vm.pendingPostSaveActionUri = actionUri
        val destinationUri = annotationController.currentSaveDestinationUri
        if (destinationUri != null) {
            saveHighlightsToUri(
                destinationUri,
                pdf.uri ?: return,
                saveDestinationDurably = annotationController.currentSaveDestinationDurable,
            )
        } else {
            showSaveDestinationSheet()
        }
    }

    fun clearPendingRequests() {
        vm.pendingAnnotationSourceUri = null
        vm.pendingPostSaveAction = null
        vm.pendingPostSaveActionUri = null
        vm.pendingAnnotationDestinationIsCopy = false
    }

    fun handleDestinationResult(intent: Intent?) {
        val destinationUri = intent?.data
        if (destinationUri == null) {
            clearPendingRequests()
            return
        }
        val sourceUri = vm.pendingAnnotationSourceUri
        if (sourceUri == null) {
            clearPendingRequests()
            return
        }
        val isCopyRequest = vm.pendingAnnotationDestinationIsCopy
        vm.pendingAnnotationSourceUri = null
        vm.pendingAnnotationDestinationIsCopy = false
        if (!vm.acceptsDocumentUri(sourceUri)) {
            vm.pendingPostSaveAction = null
            vm.pendingPostSaveActionUri = null
            return
        }
        val sameDocument = resolvesToSameDocument(destinationUri, sourceUri)
        if (isCopyRequest && sameDocument) {
            vm.pendingPostSaveAction = null
            vm.pendingPostSaveActionUri = null
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.annotation_save_same_file_title)
                .setMessage(R.string.annotation_save_same_file_message)
                .setPositiveButton(R.string.annotation_save_copy_action) { _, _ -> launchCreateDestinationPicker() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val grantPersisted = persistWritePermission(destinationUri, intent)
        if (!isCopyRequest && !sameDocument) {
            confirmOverwritingDifferentFile(destinationUri, sourceUri, grantPersisted)
            return
        }
        saveHighlightsToUri(destinationUri, sourceUri, saveDestinationDurably = grantPersisted)
    }

    private fun confirmOverwritingDifferentFile(destinationUri: Uri, sourceUri: Uri, durable: Boolean) {
        val destinationName = runCatching { getFileName(activity, destinationUri) }.getOrNull().orEmpty()
        val postSaveAction = vm.pendingPostSaveAction
        val postSaveActionUri = vm.pendingPostSaveActionUri
        vm.pendingPostSaveAction = null
        vm.pendingPostSaveActionUri = null
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.annotation_save_different_file_title)
            .setMessage(activity.getString(R.string.annotation_save_different_file_message, destinationName))
            .setPositiveButton(R.string.annotation_save_overwrite_action) { _, _ ->
                vm.pendingPostSaveAction = postSaveAction
                vm.pendingPostSaveActionUri = postSaveActionUri
                saveHighlightsToUri(destinationUri, sourceUri, saveDestinationDurably = durable)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resolvesToSameDocument(first: Uri, second: Uri): Boolean {
        if (first == second) {
            return true
        }
        val firstFile = runCatching { UriCanonicalizer.canonicalize(activity, first)?.canonicalFile }.getOrNull()
        val secondFile = runCatching { UriCanonicalizer.canonicalize(activity, second)?.canonicalFile }.getOrNull()
        if (firstFile != null && secondFile != null) {
            return firstFile == secondFile
        }
        if (first.authority != null && first.authority == second.authority) {
            val firstId = runCatching { DocumentsContract.getDocumentId(first) }.getOrNull()
            val secondId = runCatching { DocumentsContract.getDocumentId(second) }.getOrNull()
            if (firstId != null && secondId != null) {
                return firstId == secondId
            }
        }
        return false
    }

    private fun showSaveDestinationSheet() {
        val sourceUri = pdf.uri ?: return
        scope.launch {
            val savedCopyDestination = findDurableSavedCopyDestination(sourceUri, pdf.fileHash)
            showSaveDestinationSheet(savedCopyDestination)
        }
    }

    private suspend fun findDurableSavedCopyDestination(sourceUri: Uri, fileHash: String?): Uri? {
        val sourceKeyDestination = pdfRepository.findAnnotationSaveDestinationBySourceKey(AnnotationController.sourceKey(sourceUri))
        val hashDestination = fileHash?.let { pdfRepository.findAnnotationSaveDestinationByLastSavedHash(it) }
        return listOfNotNull(sourceKeyDestination, hashDestination)
            .map { it.destinationUri.toUri() }
            .firstOrNull { destinationUri ->
                destinationUri.toString() != sourceUri.toString() && hasPersistedWritePermission(destinationUri)
            }
    }

    private fun hasPersistedWritePermission(uri: Uri): Boolean {
        return activity.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isWritePermission
        }
    }

    private fun showSaveDestinationSheet(savedCopyDestination: Uri?) {
        val sourceUri = pdf.uri ?: return
        val options = arrayOf(
            activity.getString(R.string.update_existing_file),
            activity.getString(R.string.save_as_new_copy),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.save_highlights)
            .setItems(options) { _, which ->
                if (which == 0) {
                    val directDestination = directWriteDestination(sourceUri)
                    when {
                        directDestination != null ->
                            saveHighlightsToUri(directDestination, sourceUri, saveDestinationDurably = true)
                        savedCopyDestination != null ->
                            confirmOverwritingDifferentFile(savedCopyDestination, sourceUri, durable = true)
                        else -> launchUpdateDestinationPicker()
                    }
                } else {
                    launchCreateDestinationPicker()
                }
            }
            .setOnCancelListener { clearPendingRequests() }
            .show()
    }

    private fun directWriteDestination(sourceUri: Uri): Uri? {
        if (sourceUri.scheme?.startsWith("http") == true) {
            if (!canWriteToDownloadFolder(activity)) {
                return null
            }
            val downloadsCopy = publicDownloadsCopy(pdf.name)?.takeIf { it.canWrite() } ?: return null
            return Uri.fromFile(downloadsCopy)
        }
        if (!PermissionManager.hasFullAccess(activity)) {
            return null
        }
        val file = UriCanonicalizer.canonicalize(activity, sourceUri) ?: return null
        if (!file.canWrite()) {
            return null
        }
        return Uri.fromFile(file)
    }

    private fun launchUpdateDestinationPicker() {
        val sourceUri = pdf.uri ?: return
        vm.pendingAnnotationSourceUri = sourceUri
        vm.pendingAnnotationDestinationIsCopy = false
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PDF.FILE_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, sourceUri)
        updateDestinationLauncher.launch(intent)
    }

    private fun launchCreateDestinationPicker() {
        val sourceUri = pdf.uri ?: return
        vm.pendingAnnotationSourceUri = sourceUri
        vm.pendingAnnotationDestinationIsCopy = true
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(PDF.FILE_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_TITLE, suggestedAnnotatedFileName())
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, sourceUri)
        createDestinationLauncher.launch(intent)
    }

    @SuppressLint("WrongConstant")
    private fun persistWritePermission(uri: Uri, intent: Intent): Boolean {
        val flags = intent.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            return false
        }
        return runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
    }

    private fun suggestedAnnotatedFileName(): String {
        val name = pdf.name.ifBlank { "document.pdf" }
        if (name.endsWith("-annotated.pdf", ignoreCase = true)) {
            return name
        }
        return if (name.endsWith(".pdf", ignoreCase = true)) {
            name.dropLast(4) + "-annotated.pdf"
        } else {
            "$name-annotated.pdf"
        }
    }

    private fun saveHighlightsToUri(destinationUri: Uri, sourceUri: Uri, saveDestinationDurably: Boolean = true) {
        val fault = AnnotationTestHooks.saveFault(activity)
        clearActiveSearchResultHighlight()
        annotationController.setSaving(true)
        updateDirtyUi()
        val loadToken = vm.currentLoadToken
        val oldHash = pdf.fileHash
        val overwritesSource = resolvesToSameDocument(destinationUri, sourceUri)

        backgroundSaveScope.launch {
            var outcome: SaveOutcome =
                SaveOutcome.Failure(rescueFileName = null, unconfirmed = false, overwritesSource = overwritesSource)
            try {
                val writeResult = writeDocumentToSaf(destinationUri, fault)
                if (writeResult is WriteResult.Success) {
                    val destinationName = getFileName(activity, destinationUri)
                    val newHash = writeResult.identity
                    val isCurrent = vm.isCurrent(loadToken, sourceUri)
                    if (historyPolicy.canRecord()) {
                        if (oldHash != null) {
                            pdfRepository.copyOrUpdateRecordIdentity(
                                oldHash,
                                newHash,
                                sourceUri,
                                destinationUri,
                                destinationName.removeSuffix(".pdf"),
                            )
                        } else if (isCurrent) {
                            pdfRepository.saveRecordInBackground(
                                pdf.toPdfRecord(newHash, pdf.password).copy(
                                    uri = destinationUri,
                                    fileName = destinationName.removeSuffix(".pdf"),
                                )
                            )
                        }
                        if (saveDestinationDurably) {
                            pdfRepository.saveAnnotationSaveDestination(
                                PdfAnnotationSaveDestination(
                                    sourceKey = AnnotationController.sourceKey(sourceUri),
                                    destinationUri = destinationUri.toString(),
                                    lastSavedHash = newHash,
                                    lastSavedAt = LocalDateTime.now(),
                                )
                            )
                        }
                    }
                    annotationController.clearJournal(sourceUri, resetContentTags = false)
                    if (sourceUri.scheme?.startsWith("http") == true && destinationUri.scheme == "file") {
                        refreshOnlineCacheCopy(destinationUri, sourceUri)
                    }
                    outcome = SaveOutcome.Success(
                        destinationUri = destinationUri,
                        destinationName = destinationName,
                        newHash = newHash,
                        sizeBytes = writeResult.sizeBytes,
                        isCurrent = isCurrent,
                        saveDestinationDurable = saveDestinationDurably,
                    )
                } else if (writeResult is WriteResult.Failure) {
                    outcome = SaveOutcome.Failure(
                        writeResult.rescueFileName,
                        writeResult.unconfirmed,
                        overwritesSource,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to save annotations", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    annotationController.setSaving(false)
                    vm.onSaveComplete?.invoke(outcome)
                }
            }
        }
    }

    fun deliverSaveOutcome(outcome: SaveOutcome) {
        updateDirtyUi()
        when (outcome) {
            is SaveOutcome.Success -> onSaveSucceeded(outcome)
            is SaveOutcome.Failure -> {
                vm.pendingPostSaveAction = null
                vm.pendingPostSaveActionUri = null
                showSaveFailed(outcome)
            }
        }
    }

    private fun onSaveSucceeded(outcome: SaveOutcome.Success) {
        if (!outcome.isCurrent) {
            vm.pendingPostSaveAction = null
            vm.pendingPostSaveActionUri = null
            return
        }
        pdf.uri = outcome.destinationUri
        pdf.name = outcome.destinationName
        pdf.fileHash = outcome.newHash
        PreviewDiskCoordinator.attach(
            pdfView = binding.pdfView,
            cacheDir = activity.cacheDir,
            fileHash = outcome.newHash,
            pageCount = binding.pdfView.getPageCount(),
            sizeBytes = outcome.sizeBytes,
            incognito = vm.incognito,
            hasPassword = pdf.password != null,
        )
        annotationController.resetContentTags()
        annotationController.setCurrentSaveDestination(outcome.destinationUri, durable = outcome.saveDestinationDurable)
        onDocumentSaved()
        activity.setTaskDescription(ActivityManager.TaskDescription(pdf.name))
        AppSnackbar.make(binding.root, R.string.highlights_saved, Snackbar.LENGTH_SHORT).show()
        val postSaveAction = vm.pendingPostSaveAction
        val postSaveActionUri = vm.pendingPostSaveActionUri
        vm.pendingPostSaveAction = null
        vm.pendingPostSaveActionUri = null
        postSaveAction?.let { runPostSaveAction(it, postSaveActionUri) }
    }

    private suspend fun refreshOnlineCacheCopy(destinationUri: Uri, sourceUri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            val saved = destinationUri.path?.let { File(it) }?.takeIf { it.isFile } ?: return@runCatching
            saved.inputStream().use { input ->
                OnlineDocumentStore.write(activity, sourceUri.toString(), vm.incognito, input, saved.length())
            }
        }
    }

    private suspend fun writeDocumentToSaf(destinationUri: Uri, fault: SaveFault): WriteResult = withContext(Dispatchers.IO) {
        val tmp = File(activity.cacheDir, "annotation-save-${System.currentTimeMillis()}.pdf")
        try {
            if (!runCatching { binding.pdfView.saveAsCopy(tmp) }.getOrDefault(false)) {
                return@withContext WriteResult.Failure(rescueFileName = null, unconfirmed = false)
            }
            val sizeBytes = tmp.length()
            val identities = DocumentIdentity.of(tmp)
                ?: return@withContext WriteResult.Failure(rescueFileName = null, unconfirmed = false)
            val hash = identities.legacy
            val tailHash = computeTailHash(tmp)
                ?: return@withContext WriteResult.Failure(rescueFileName = null, unconfirmed = false)
            if (destinationUri.scheme == "file") {
                writeToFileDestination(destinationUri, tmp, hash, identities.identity, tailHash, sizeBytes, fault)
            } else {
                writeToContentDestination(destinationUri, tmp, hash, identities.identity, tailHash, sizeBytes, fault)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun writeToFileDestination(
        destinationUri: Uri,
        tmp: File,
        hash: String,
        identity: String,
        tailHash: String,
        sizeBytes: Long,
        fault: SaveFault,
    ): WriteResult {
        val target = destinationUri.path?.let { File(it) }
        val parent = target?.parentFile
        if (target == null || parent == null) {
            return failure(tmp, unconfirmed = false)
        }
        val sibling = File(parent, "${target.name}.saving")
        AnnotationTestHooks.awaitSaveWrite(fault)
        val wrote = runCatching {
            FileOutputStream(sibling).use { output ->
                tmp.inputStream().use { input -> AnnotationTestHooks.copySaveBytes(fault, input, output) }
                output.fd.sync()
            }
            true
        }.getOrDefault(false)
        if (!wrote || AnnotationTestHooks.verificationFails(fault) ||
            sibling.length() != sizeBytes || computeHash(sibling) != hash ||
            computeTailHash(sibling) != tailHash
        ) {
            sibling.delete()
            return failure(tmp, unconfirmed = false)
        }
        if (!sibling.renameTo(target)) {
            sibling.delete()
            return failure(tmp, unconfirmed = false)
        }
        return WriteResult.Success(hash, identity, sizeBytes)
    }

    private suspend fun writeToContentDestination(
        destinationUri: Uri,
        tmp: File,
        hash: String,
        identity: String,
        tailHash: String,
        sizeBytes: Long,
        fault: SaveFault,
    ): WriteResult {
        var wrote = attemptContentWrite(destinationUri, tmp, fault)
        if (wrote.isFailure) {
            if (destinationMatches(destinationUri, hash, tailHash, sizeBytes, fault)) {
                return WriteResult.Success(hash, identity, sizeBytes)
            }
            wrote = attemptContentWrite(destinationUri, tmp, fault)
        }
        if (!wrote.getOrDefault(false)) {
            return failure(tmp, unconfirmed = false)
        }
        return if (destinationMatches(destinationUri, hash, tailHash, sizeBytes, fault)) {
            WriteResult.Success(hash, identity, sizeBytes)
        } else {
            failure(tmp, unconfirmed = true)
        }
    }

    private suspend fun destinationMatches(
        destinationUri: Uri,
        hash: String,
        tailHash: String,
        sizeBytes: Long,
        fault: SaveFault,
    ): Boolean {
        if (AnnotationTestHooks.verificationFails(fault)) {
            return false
        }
        if (!destinationSizeMatches(destinationUri, sizeBytes)) {
            return false
        }
        if (runCatching { computeHash(activity, destinationUri) }.getOrNull() != hash) {
            return false
        }
        return runCatching { computeTailHash(activity, destinationUri) }.getOrNull() == tailHash
    }

    private fun attemptContentWrite(destinationUri: Uri, tmp: File, fault: SaveFault): Result<Boolean> {
        AnnotationTestHooks.awaitSaveWrite(fault)
        return runCatching {
            activity.contentResolver.openOutputStream(destinationUri, "wt")?.use { output ->
                tmp.inputStream().use { input -> AnnotationTestHooks.copySaveBytes(fault, input, output) }
            } != null
        }
    }

    private fun destinationSizeMatches(destinationUri: Uri, expected: Long): Boolean {
        val size = runCatching {
            activity.contentResolver.openFileDescriptor(destinationUri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
        return size < 0 || size == expected
    }

    private fun failure(tmp: File, unconfirmed: Boolean): WriteResult.Failure {
        val rescueFileName = if (!vm.incognito) writeRescueCopy(tmp) else null
        return WriteResult.Failure(rescueFileName = rescueFileName, unconfirmed = unconfirmed)
    }

    private fun writeRescueCopy(tmp: File): String? = runCatching {
        val dir = File(activity.filesDir, RESCUE_DIR).apply { mkdirs() }
        val displayName = suggestedAnnotatedFileName()
        val rescue = File(dir, "${System.currentTimeMillis()}-$displayName")
        tmp.inputStream().use { input ->
            FileOutputStream(rescue).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(RESCUE_KEEP)
            ?.forEach { it.delete() }
        displayName
    }.getOrNull()

    private fun showSaveFailed(outcome: SaveOutcome.Failure) {
        val titleRes: Int
        val message: CharSequence
        when {
            outcome.overwritesSource -> {
                titleRes = R.string.annotation_save_incomplete_title
                message = if (outcome.rescueFileName != null) {
                    activity.getString(R.string.annotation_save_incomplete_rescue_message, outcome.rescueFileName)
                } else {
                    activity.getString(R.string.annotation_save_incomplete_message)
                }
            }
            outcome.unconfirmed -> {
                titleRes = R.string.annotation_save_unconfirmed_title
                message = activity.getString(R.string.annotation_save_unconfirmed_message)
            }
            outcome.rescueFileName != null -> {
                titleRes = R.string.annotation_save_failed_title
                message = activity.getString(R.string.annotation_save_failed_rescue_message, outcome.rescueFileName)
            }
            else -> {
                titleRes = R.string.annotation_save_failed_title
                message = activity.getString(R.string.annotation_save_failed_message)
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.annotation_save_copy_action) { _, _ -> launchCreateDestinationPicker() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val TAG = "AnnotationSaveController"
        const val RESCUE_DIR = "annotation-rescue"
        const val RESCUE_KEEP = 2
    }
}
