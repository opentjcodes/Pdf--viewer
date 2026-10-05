// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import android.content.Context
import android.net.Uri
import com.github.barteksc.pdfviewer.source.UriSource
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.math.min

fun computeTailHash(file: File): String? {
    return runCatching {
        java.io.RandomAccessFile(file, "r").use { access ->
            val length = access.length()
            if (length <= 0L) return@use null
            val size = min(PDF.HASH_SIZE.toLong(), length).toInt()
            access.seek(length - size)
            val buffer = ByteArray(size)
            access.readFully(buffer)
            digestOf(buffer, size)
        }
    }.getOrNull()
}

suspend fun computeTailHash(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            OnlineDocumentStore.fileFor(context, uri.toString())?.let { return@runCatching computeTailHash(it) }
            UriSource.openReadDescriptor(context, uri)?.use { descriptor ->
                val length = descriptor.statSize
                if (length <= 0L) return@use null
                val size = min(PDF.HASH_SIZE.toLong(), length).toInt()
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.position(length - size)
                    val buffer = ByteArray(size)
                    var totalRead = 0
                    while (totalRead < size) {
                        val amountRead = stream.read(buffer, totalRead, size - totalRead)
                        if (amountRead == -1) break
                        totalRead += amountRead
                    }
                    if (totalRead < size) null else digestOf(buffer, size)
                }
            }
        }.getOrNull()
    }
}

private fun digestOf(buffer: ByteArray, size: Int): String {
    val digester = MessageDigest.getInstance("MD5")
    digester.update(buffer, 0, size)
    return String.format("%032x", BigInteger(1, digester.digest()))
}

fun computeHash(bytes: ByteArray): String? {
    return runCatching {
        val digester = MessageDigest.getInstance("MD5")
        digester.update(bytes, 0, min(PDF.HASH_SIZE, bytes.size))
        String.format("%032x", BigInteger(1, digester.digest()))
    }.getOrNull()
}

fun computeHash(file: File): String? {
    return runCatching {
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(PDF.HASH_SIZE)
            var totalRead = 0
            while (totalRead < buffer.size) {
                val amountRead = stream.read(buffer, totalRead, buffer.size - totalRead)
                if (amountRead == -1) break
                totalRead += amountRead
            }
            if (totalRead == 0) return@use null
            val digester = MessageDigest.getInstance("MD5")
            digester.update(buffer, 0, totalRead)
            String.format("%032x", BigInteger(1, digester.digest()))
        }
    }.getOrNull()
}

suspend fun computeHash(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    return try {
        val digester = MessageDigest.getInstance("MD5")
        withContext(Dispatchers.IO) {
            OnlineDocumentStore.fileFor(context, uri.toString())?.let { return@withContext computeHash(it) }
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            inputStream.use { stream ->
                val buffer = ByteArray(PDF.HASH_SIZE)
                var totalRead = 0
                while (totalRead < buffer.size) {
                    val amountRead = stream.read(buffer, totalRead, buffer.size - totalRead)
                    if (amountRead == -1) break
                    totalRead += amountRead
                }
                if (totalRead == 0) return@withContext null
                digester.update(buffer, 0, totalRead)
            }
        }
        String.format("%032x", BigInteger(1, digester.digest()))
    } catch (e: NoSuchAlgorithmException) {
        Log.e("FileHash", "NoSuchAlgorithmException: computeHash failed!", e)
        null
    } catch (e: IOException) {
        Log.e("FileHash", "IOException: computeHash failed!", e)
        null
    } catch (e: SecurityException) {
        Log.e("FileHash", "SecurityException: computeHash failed!", e)
        null
    } catch (e: Throwable) {
        Log.e("FileHash", "computeHash failed!", e)
        null
    }
}
