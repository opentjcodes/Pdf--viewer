// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gitlab.mudlej.MjPdfReader.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme != null && uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val indexDisplayName: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (indexDisplayName != -1) result = cursor.getString(indexDisplayName)
                }
            }
        } catch (e: Exception) {
            Log.w("getFileName", context.getString(R.string.error_load_file_name), e)
        }
    }

    val name = result ?: uri.lastPathSegment ?: return "Unknown PDF Name"

    // Check https://github.com/mudlej/mj_pdf/issues/24
    if (name.contains("SMB", ignoreCase = true)) {
        return try {
            decodeNameFromUrl(name)
        } catch (throwable: Throwable) {
            name
        }
    }
    return name
}
@Throws(IllegalArgumentException::class)
fun decodeNameFromUrl(encodedUrl: String): String {
    // First, decode the entire URL
    val decodedUrl = try {
        URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8.toString())
    } catch (e: IllegalArgumentException) {
        encodedUrl
    }

    // Extract the last segment from the decoded URL
    val lastSegment = decodedUrl.substringAfterLast('/')

    // Attempt to decode the last segment again in case of partial decoding
    return try {
        URLDecoder.decode(lastSegment, StandardCharsets.UTF_8.toString())
    } catch (e: IllegalArgumentException) {
        // If decoding fails, attempt to decode up to the last complete percent-encoded sequence
        val safePart = lastSegment.substringBeforeLast('%')
        URLDecoder.decode(safePart, StandardCharsets.UTF_8.toString()) + lastSegment.substringAfterLast('%')
    }
}
@Throws(IOException::class)
fun copyFileToDirectory(source: File, directory: File, fileName: String) {
    val target = File(directory, fileName)
    source.inputStream().use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output) }
    }
}
fun canWriteToDownloadFolder(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) true
    else ContextCompat.checkSelfPermission(context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
fun publicDownloadsCopy(name: String): File? = runCatching {
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ensurePdfFileName(name))
        .takeIf { name.isNotBlank() && it.isFile }
}.getOrNull()
val File.size get() = if (!exists()) 0.0 else length().toDouble()
val File.sizeInKb get() = size / 1024
val File.sizeInMb get() = sizeInKb / 1024

private const val PDF_SUFFIX = ".pdf"
private const val SHARE_CACHE_DIR = "share"
private const val SHARE_CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
private const val MAX_PDF_BASE_NAME_BYTES = 120

fun ensurePdfFileName(name: String): String {
    val cleaned = name
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()
        .trim('.')
    var base = cleaned
    val hasPdfSuffix = base.endsWith(PDF_SUFFIX, ignoreCase = true)
    val suffix = if (hasPdfSuffix) {
        base.takeLast(PDF_SUFFIX.length)
    } else {
        PDF_SUFFIX
    }
    if (hasPdfSuffix) {
        base = base.dropLast(PDF_SUFFIX.length)
    }
    base = base.trim().trim('.').ifBlank { "Shared PDF" }
    return base.takeUtf8Bytes(MAX_PDF_BASE_NAME_BYTES) + suffix
}

private fun String.takeUtf8Bytes(maxBytes: Int): String = buildString {
    var byteCount = 0
    var index = 0
    while (index < this@takeUtf8Bytes.length) {
        val codePoint = this@takeUtf8Bytes.codePointAt(index)
        val characters = Character.toChars(codePoint)
        val codePointBytes = String(characters).toByteArray(StandardCharsets.UTF_8).size
        if (byteCount + codePointBytes > maxBytes) break
        append(characters)
        byteCount += codePointBytes
        index += Character.charCount(codePoint)
    }
}

@Throws(IOException::class)
fun pdfShareUri(context: Context, sourceUri: Uri, displayName: String): Uri {
    val sourceHasPdfName = sourceUri.scheme == ContentResolver.SCHEME_CONTENT &&
        getFileName(context, sourceUri).endsWith(PDF_SUFFIX, ignoreCase = true)
    if (sourceHasPdfName) {
        return sourceUri
    }

    val shareDir = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
    val expiredBefore = System.currentTimeMillis() - SHARE_CACHE_MAX_AGE_MILLIS
    shareDir.listFiles()?.forEach { file ->
        if (file.name.endsWith(PDF_SUFFIX, ignoreCase = true) && file.lastModified() < expiredBefore) {
            file.delete()
        }
    }

    val target = File(shareDir, ensurePdfFileName(displayName))
    context.contentResolver.openInputStream(sourceUri).use { input ->
        input ?: throw IOException("Unable to open source for sharing")
        FileOutputStream(target).use { output -> input.copyTo(output) }
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
}

suspend fun pdfShareIntent(context: Context, sourceUri: Uri, displayName: String): Intent {
    val fileName = ensurePdfFileName(displayName)
    val shareUri = withContext(Dispatchers.IO) {
        runCatching { pdfShareUri(context, sourceUri, displayName) }.getOrNull()
    } ?: sourceUri
    return fileShareIntent(context.getString(R.string.share_file), fileName, shareUri)
}
