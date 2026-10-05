package com.gitlab.mudlej.MjPdfReader.suite.fixtures

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StubSharerProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        publishedContext = context?.applicationContext
        return true
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = nameOf(uri)
        if (mode != READ_ONLY_MODE && !writableNames.contains(name)) {
            throw FileNotFoundException(
                "$AUTHORITY serves read only descriptors, so mode \"$mode\" is refused for $uri"
            )
        }
        val file = resolveFile(name)
            ?: throw FileNotFoundException("$AUTHORITY has nothing published under $name")
        openGates[name]?.let { gate ->
            if (gate.condition() && gate.claimed.compareAndSet(false, true)) {
                gate.entered.countDown()
                if (!gate.release.await(OPEN_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw FileNotFoundException("Timed out waiting for the debug open gate for $name")
                }
            }
        }
        val descriptorMode = when (mode) {
            READ_ONLY_MODE -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("$AUTHORITY does not support mode \"$mode\" for $uri")
        }
        return ParcelFileDescriptor.open(file, descriptorMode)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = nameOf(uri)
        val file = resolveFile(name)
            ?: throw FileNotFoundException("$AUTHORITY has nothing published under $name")
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns.toTypedArray())
        val row: Array<Any?> = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> reportedDisplayNames[name] ?: name
                OpenableColumns.SIZE -> file.length()
                MediaStore.MediaColumns.DATA -> if (canonicalPathNames.contains(name)) file.absolutePath else null
                else -> null
            }
        }.toTypedArray()
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("$AUTHORITY is read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("$AUTHORITY is read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("$AUTHORITY is read only")

    private fun nameOf(uri: Uri): String =
        uri.lastPathSegment ?: throw FileNotFoundException("$uri names no published entry")

    companion object {

        const val AUTHORITY = "mjpdf.test.sharer"
        const val STAGING_DIRECTORY = "sharer"
        const val MIME_TYPE = "application/pdf"
        const val READ_ONLY_MODE = "r"

        @Volatile
        private var publishedContext: Context? = null

        private val registeredPaths = ConcurrentHashMap<String, String>()
        private val reportedDisplayNames = ConcurrentHashMap<String, String>()
        private val canonicalPathNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val writableNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val openGates = ConcurrentHashMap<String, OpenGate>()

        private class OpenGate(
            val condition: () -> Boolean,
            val entered: CountDownLatch = CountDownLatch(1),
            val release: CountDownLatch = CountDownLatch(1),
            val claimed: AtomicBoolean = AtomicBoolean(),
        )

        private fun requireContext(): Context = publishedContext
            ?: throw IllegalStateException(
                "$AUTHORITY has not been created yet. It is declared in app/src/debug/AndroidManifest.xml " +
                    "and is only available in debug builds."
            )

        val stagingRoot: File
            get() = File(requireContext().filesDir, STAGING_DIRECTORY)

        fun uriFor(name: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()

        fun publish(name: String, bytes: ByteArray): Uri {
            stagingRoot.mkdirs()
            val target = File(stagingRoot, name)
            target.writeBytes(bytes)
            return registerPath(name, target.absolutePath)
        }

        fun registerPath(name: String, absolutePath: String): Uri {
            val file = File(absolutePath)
            if (!file.isFile) {
                throw AssertionError("Cannot serve $absolutePath through $AUTHORITY, it is not a file")
            }
            registeredPaths[name] = absolutePath
            return uriFor(name)
        }

        fun registerDocumentPath(name: String, absolutePath: String): Uri {
            registerPath(name, absolutePath)
            canonicalPathNames.add(name)
            return DocumentsContract.buildDocumentUri(AUTHORITY, name)
        }

        fun registerWritableDocumentPath(name: String, absolutePath: String): Uri {
            val uri = registerDocumentPath(name, absolutePath)
            writableNames.add(name)
            return uri
        }

        fun setDisplayName(name: String, reported: String) {
            reportedDisplayNames[name] = reported
        }

        fun blockFirstOpenMatching(name: String, condition: () -> Boolean) {
            openGates.remove(name)?.release?.countDown()
            openGates[name] = OpenGate(condition)
        }

        fun awaitOpenBlocked(name: String, timeoutMs: Long): Boolean =
            openGates[name]?.entered?.await(timeoutMs, TimeUnit.MILLISECONDS) == true

        fun releaseBlockedOpen(name: String) {
            openGates.remove(name)?.release?.countDown()
        }

        fun resolveFile(name: String): File? {
            registeredPaths[name]?.let { path ->
                val registered = File(path)
                return if (registered.isFile) registered else null
            }
            val staged = File(stagingRoot, name)
            return if (staged.isFile) staged else null
        }

        fun clear() {
            openGates.values.forEach { it.release.countDown() }
            openGates.clear()
            registeredPaths.clear()
            reportedDisplayNames.clear()
            canonicalPathNames.clear()
            writableNames.clear()
            runCatching { stagingRoot.deleteRecursively() }
        }

        private const val OPEN_GATE_TIMEOUT_MS = 120_000L
    }
}
