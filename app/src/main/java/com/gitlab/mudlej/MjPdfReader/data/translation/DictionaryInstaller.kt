// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.translation

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.core.net.contentLengthCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

object DictionaryInstaller {

    const val downloadUrl =
        "https://gitlab.com/api/v4/projects/mudlej_android%2Fmj_pdf_reader/packages/generic/dictionary/1/mj-pdf-dictionary-en-1.db.gz"
    const val downloadSizeBytes = 7_398_851L
    const val installedSizeBytes = 17_715_200L
    private const val expectedSha256 = "43a61ca76226897d2cd9e066869328b64a62a5c422328786d5fa8e0407513891"
    private const val connectTimeoutMillis = 15_000
    private const val readTimeoutMillis = 30_000

    suspend fun install(context: Context, onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val target = DictionaryStore.file(context)
        val downloadFile = File(target.path + ".gz.tmp")
        val databaseFile = File(target.path + ".tmp")
        try {
            target.parentFile?.mkdirs()
            download(downloadFile, onProgress)
            decompress(downloadFile, databaseFile)
            if (target.exists()) {
                target.delete()
            }
            if (!databaseFile.renameTo(target)) {
                throw IOException("Could not move the dictionary into place")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Result.failure(e)
        } finally {
            downloadFile.delete()
            databaseFile.delete()
        }
    }

    private suspend fun download(destination: File, onProgress: (Int) -> Unit) {
        val connection = URL(DictionaryDebugConfig.resolveUrl(downloadUrl)).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Server responded with HTTP ${connection.responseCode}")
            }
            val totalBytes = connection.contentLengthCompat()
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress(((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100))
                        }
                        DictionaryDebugConfig.throttleChunk()
                    }
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != DictionaryDebugConfig.resolveSha256(expectedSha256)) {
                throw IOException("Downloaded file failed the integrity check")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun decompress(source: File, destination: File) {
        GZIPInputStream(source.inputStream()).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}
