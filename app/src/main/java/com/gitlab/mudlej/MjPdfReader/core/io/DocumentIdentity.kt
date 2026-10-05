// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import android.content.Context
import android.net.Uri
import com.github.barteksc.pdfviewer.source.UriSource
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.min

data class DocumentIdentities(val identity: String, val legacy: String) {
    fun matches(hash: String): Boolean = hash == identity || hash == legacy
}

object DocumentIdentity {

    const val PREFIX = "h2"

    private const val WINDOW = PDF.HASH_SIZE
    private const val WHOLE_FILE_LIMIT = 2L * PDF.HASH_SIZE

    fun isLegacy(identity: String): Boolean {
        return !identity.startsWith(PREFIX)
    }

    fun of(file: File): DocumentIdentities? {
        return runCatching {
            RandomAccessFile(file, "r").use { access ->
                val length = access.length()
                if (length <= 0L) return@use null
                fromSeekable(length) { position, buffer, count ->
                    access.seek(position)
                    access.readFully(buffer, 0, count)
                }
            }
        }.getOrNull()
    }

    suspend fun of(context: Context, uri: Uri?): DocumentIdentities? {
        if (uri == null) {
            return null
        }
        return withContext(Dispatchers.IO) {
            OnlineDocumentStore.fileFor(context, uri.toString())?.let { return@withContext of(it) }
            fromDescriptor(context, uri) ?: fromStream(context, uri)
        }
    }

    private fun fromDescriptor(context: Context, uri: Uri): DocumentIdentities? {
        return runCatching {
            UriSource.openReadDescriptor(context, uri)?.use { descriptor ->
                val length = descriptor.statSize
                if (length <= 0L) return@use null
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    fromSeekable(length) { position, buffer, count ->
                        stream.channel.position(position)
                        readFully(stream, buffer, count)
                    }
                }
            }
        }.getOrNull()
    }

    private fun fromStream(context: Context, uri: Uri): DocumentIdentities? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream -> fromForwardRead(stream) }
        }.getOrNull()
    }

    private inline fun fromSeekable(
        length: Long,
        read: (position: Long, buffer: ByteArray, count: Int) -> Unit,
    ): DocumentIdentities? {
        val headCount = min(WINDOW.toLong(), length).toInt()
        val head = ByteArray(headCount)
        read(0L, head, headCount)
        val legacy = digestOf(head, headCount)
        val digester = MessageDigest.getInstance("MD5")
        if (length <= WHOLE_FILE_LIMIT) {
            digester.update(head, 0, headCount)
            var position = headCount.toLong()
            val chunk = ByteArray(WINDOW)
            while (position < length) {
                val count = min(WINDOW.toLong(), length - position).toInt()
                read(position, chunk, count)
                digester.update(chunk, 0, count)
                position += count
            }
        } else {
            digester.update(head, 0, headCount)
            val tail = ByteArray(WINDOW)
            read(length - WINDOW, tail, WINDOW)
            digester.update(tail, 0, WINDOW)
        }
        return DocumentIdentities(finish(digester, length), legacy)
    }

    private fun fromForwardRead(stream: InputStream): DocumentIdentities? {
        val head = ByteArray(WINDOW)
        val headCount = readUpTo(stream, head, WINDOW)
        if (headCount <= 0) {
            return null
        }
        val legacy = digestOf(head, headCount)
        val ring = ByteArray(WINDOW)
        var ringPosition = 0
        var ringFilled = 0
        var total = headCount.toLong()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) {
                break
            }
            total += read
            var offset = 0
            var remaining = read
            while (remaining > 0) {
                val count = min(remaining, WINDOW - ringPosition)
                System.arraycopy(chunk, offset, ring, ringPosition, count)
                ringPosition = (ringPosition + count) % WINDOW
                ringFilled = min(WINDOW, ringFilled + count)
                offset += count
                remaining -= count
            }
        }
        val digester = MessageDigest.getInstance("MD5")
        digester.update(head, 0, headCount)
        if (total <= WHOLE_FILE_LIMIT) {
            val start = if (ringFilled < WINDOW) 0 else ringPosition
            appendRing(digester, ring, start, ringFilled)
        } else {
            appendRing(digester, ring, ringPosition, WINDOW)
        }
        return DocumentIdentities(finish(digester, total), legacy)
    }

    private fun appendRing(digester: MessageDigest, ring: ByteArray, start: Int, count: Int) {
        if (count <= 0) {
            return
        }
        val first = min(count, WINDOW - start)
        digester.update(ring, start, first)
        if (first < count) {
            digester.update(ring, 0, count - first)
        }
    }

    private fun readUpTo(stream: InputStream, buffer: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val read = stream.read(buffer, total, count - total)
            if (read < 0) {
                break
            }
            total += read
        }
        return total
    }

    private fun readFully(stream: FileInputStream, buffer: ByteArray, count: Int) {
        var total = 0
        while (total < count) {
            val read = stream.read(buffer, total, count - total)
            if (read < 0) {
                throw java.io.EOFException("Short read at $total of $count")
            }
            total += read
        }
    }

    private fun finish(digester: MessageDigest, length: Long): String {
        val lengthBytes = ByteArray(8)
        for (index in 0 until 8) {
            lengthBytes[index] = (length shr (56 - index * 8)).toByte()
        }
        digester.update(lengthBytes)
        return PREFIX + String.format("%032x", BigInteger(1, digester.digest()))
    }

    private fun digestOf(buffer: ByteArray, count: Int): String {
        val digester = MessageDigest.getInstance("MD5")
        digester.update(buffer, 0, count)
        return String.format("%032x", BigInteger(1, digester.digest()))
    }
}
