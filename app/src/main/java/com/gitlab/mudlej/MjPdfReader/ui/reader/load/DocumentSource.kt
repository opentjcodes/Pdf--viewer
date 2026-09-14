// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.load

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DocumentSource(private val context: Context) {

    suspend fun isReadable(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).canRead() } == true
            } else {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { } != null
                }.getOrDefault(false)
            }
        }
    }

    suspend fun canonicalFile(uri: Uri): File? {
        return withContext(Dispatchers.IO) { UriCanonicalizer.canonicalize(context, uri) }
    }

    suspend fun sizeBytes(uri: Uri?): Long? {
        if (uri == null) {
            return null
        }
        return withContext(Dispatchers.IO) {
            OnlineDocumentStore.fileFor(context, uri.toString())?.let { return@withContext it.length() }
            runCatching {
                when (uri.scheme) {
                    ContentResolver.SCHEME_CONTENT ->
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                        }
                    ContentResolver.SCHEME_FILE -> uri.path?.let { path -> File(path).length().takeIf { it > 0 } }
                    else -> null
                }
            }.getOrNull()
        }
    }
}
