// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import java.io.File
import java.io.IOException
import java.util.UUID

object DurableCopyStore {

    class SavedCopy(val uri: Uri, val isPublic: Boolean)

    private val directoryPublishLock = Any()

    fun saveCopy(context: Context, source: Uri, displayName: String, expectedHash: String?): SavedCopy? {
        val fileName = ensurePdfFileName(displayName)
        val publicCopy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            saveToMediaStore(context, source, fileName)
        } else if (canWriteToPublicStorage(context)) {
            saveToDirectory(context, source, publicDirectory(), fileName, expectedHash, isPublic = true)
        } else {
            null
        }
        return publicCopy
            ?: saveToDirectory(context, source, privateDirectory(context), fileName, expectedHash, isPublic = false)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun saveToMediaStore(context: Context, source: Uri, fileName: String): SavedCopy? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, PDF.FILE_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + File.separator + FOLDER_NAME)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return runCatching {
            resolver.openOutputStream(target).use { output ->
                if (output == null) {
                    throw IOException("No output stream for $target")
                }
                copySource(context, source, output)
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            SavedCopy(target, isPublic = true)
        }.getOrElse {
            runCatching { resolver.delete(target, null, null) }
            null
        }
    }

    private fun saveToDirectory(
        context: Context,
        source: Uri,
        directory: File,
        fileName: String,
        expectedHash: String?,
        isPublic: Boolean,
    ): SavedCopy? {
        return runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) {
                return@runCatching null
            }
            val target = synchronized(directoryPublishLock) {
                val candidate = resolveTarget(directory, fileName, expectedHash) ?: return@runCatching null
                if (candidate.exists()) {
                    return@runCatching SavedCopy(Uri.fromFile(candidate), isPublic)
                }
                candidate
            }
            val partial = File(directory, "${target.name}.${UUID.randomUUID()}$PARTIAL_SUFFIX")
            val published = try {
                partial.outputStream().use { output -> copySource(context, source, output) }
                synchronized(directoryPublishLock) {
                    val candidate = resolveTarget(directory, fileName, expectedHash)
                        ?: throw IOException("No available name for $fileName")
                    if (!candidate.exists() && !partial.renameTo(candidate)) {
                        throw IOException("Rename failed for $candidate")
                    }
                    candidate
                }
            } finally {
                partial.delete()
            }
            if (isPublic) {
                notifyMediaScanner(context, published)
            }
            SavedCopy(Uri.fromFile(published), isPublic)
        }.getOrNull()
    }

    private fun notifyMediaScanner(context: Context, target: File) {
        runCatching {
            MediaScannerConnection.scanFile(
                context, arrayOf(target.absolutePath), arrayOf(PDF.FILE_TYPE), null
            )
        }
    }

    private fun copySource(context: Context, source: Uri, output: java.io.OutputStream) {
        context.contentResolver.openInputStream(source).use { input ->
            if (input == null) {
                throw IOException("No input stream for $source")
            }
            input.copyTo(output)
        }
    }

    private fun resolveTarget(directory: File, fileName: String, expectedHash: String?): File? {
        val baseName = fileName.dropLast(PDF_SUFFIX.length)
        var candidate = File(directory, fileName)
        var index = 2
        while (candidate.exists()) {
            if (expectedHash != null && DocumentIdentity.of(candidate)?.matches(expectedHash) == true) {
                return candidate
            }
            if (index > MAX_NAME_ATTEMPTS) {
                return null
            }
            candidate = File(directory, "$baseName ($index)$PDF_SUFFIX")
            index++
        }
        return candidate
    }

    private fun canWriteToPublicStorage(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun publicDirectory(): File {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER_NAME)
    }

    private fun privateDirectory(context: Context): File {
        return File(context.filesDir, PRIVATE_FOLDER_NAME)
    }

    private const val FOLDER_NAME = "MJ PDF"
    private const val PRIVATE_FOLDER_NAME = "imported"
    private const val PDF_SUFFIX = ".pdf"
    private const val PARTIAL_SUFFIX = ".part"
    private const val MAX_NAME_ATTEMPTS = 100
}
