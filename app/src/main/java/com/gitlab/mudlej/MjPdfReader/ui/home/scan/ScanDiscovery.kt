// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import android.content.Context
import android.database.Cursor
import android.os.CancellationSignal
import android.provider.MediaStore
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.ui.home.ScanScope
import com.gitlab.mudlej.MjPdfReader.ui.home.StorageManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ScanWalk(
    val roots: List<String>,
    val excludedRoots: List<String> = emptyList(),
    val explicitRoots: Boolean = false,
) {
    fun within(scopeRoots: List<String>?): ScanWalk {
        val retained = roots.flatMap { root ->
            if (ScanScope.contains(scopeRoots, root)) listOf(root)
            else scopeRoots.orEmpty().filter { ScanScope.contains(listOf(root), it) }
        }.filterNot { ScanScope.contains(excludedRoots, it) }
        return ScanWalk(
            ScanScope.normalize(retained.toSet()), excludedRoots.toList(),
            explicitRoots = explicitRoots || scopeRoots != null,
        )
    }

    fun contains(path: String): Boolean =
        ScanScope.contains(roots, path) && !ScanScope.contains(excludedRoots, path)
}

class ScanDiscovery(
    private val context: Context,
    private val emit: suspend (ScanEvent) -> Unit,
) {
    suspend fun run(
        generation: Long,
        roots: List<File> = emptyList(),
        excludedRoots: List<File> = emptyList(),
        knownEntries: List<ScannedPdfEntry> = emptyList(),
        explicitRoots: Boolean = false,
    ) = run(
        generation,
        listOf(ScanWalk(roots.map { it.path }, excludedRoots.map { it.path }, explicitRoots)),
        knownEntries,
    )

    internal suspend fun run(
        generation: Long,
        walks: List<ScanWalk>,
        knownEntries: List<ScannedPdfEntry>,
    ) {
        val known = knownEntries.associateBy { it.path }
        discoverMediaStore(generation, known)
        if (walks.any { it.roots.isNotEmpty() }) discoverFilesystem(generation, walks)
    }

    private suspend fun discoverMediaStore(generation: Long, known: Map<String, ScannedPdfEntry>) =
        discover(generation, DiscoverySource.MEDIA_STORE) { batch ->
            coroutineScope {
                val signal = CancellationSignal()
                val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        signal.cancel()
                    }
                }
                try {
                    ensureActive()
                    checkNotNull(queryMediaStorePdfs(signal)) { "MediaStore PDF query returned no cursor" }
                        .use { cursor -> readMediaStoreEntries(cursor, batch, known) }
                } finally {
                    cancellation.cancel()
                }
            }
        }

    private fun queryMediaStorePdfs(signal: CancellationSignal): Cursor? {
        ScanDebugSignals.noteMediaStoreQuery()
        return context.contentResolver.query(
            MediaStore.Files.getContentUri(EXTERNAL_VOLUME),
            arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            ),
            "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
            arrayOf(PDF.FILE_TYPE, "%.$PDF_SUFFIX"),
            null,
            signal,
        )
    }

    private suspend fun readMediaStoreEntries(cursor: Cursor, batch: ScanBatch, known: Map<String, ScannedPdfEntry>) {
        val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        require(pathIndex >= 0 && sizeIndex >= 0 && modifiedIndex >= 0) {
            "MediaStore PDF projection is incomplete"
        }
        val probe = ScanPathProbe(context)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!cursor.moveToNext()) break
            val path = cursor.getString(pathIndex) ?: continue
            if (!path.endsWith(".$PDF_SUFFIX", ignoreCase = true)) continue
            val old = known[path]
            val providerFingerprint = SourceFingerprint.normalized(
                path, cursor.getLong(sizeIndex), cursor.getLong(modifiedIndex) * 1_000L,
            )
            if (old != null && !cursor.isNull(sizeIndex) && !cursor.isNull(modifiedIndex) &&
                old.fingerprint == providerFingerprint
            ) {
                batch.add(old)
                continue
            }
            when (val state = probe.read(path)) {
                is ScanPathState.Present -> batch.add(
                    if (old != null && old.fingerprint == state.entry.fingerprint) old else state.entry
                )
                ScanPathState.Missing -> Unit
                ScanPathState.Unavailable -> if (old != null) batch.add(old)
            }
        }
    }

    private suspend fun discoverFilesystem(
        generation: Long,
        walks: List<ScanWalk>,
    ) =
        discover(generation, DiscoverySource.FILESYSTEM) { batch ->
            val probe = ScanPathProbe(context)
            var failure: Exception? = null
            for (walk in walks) {
                currentCoroutineContext().ensureActive()
                for (file in StorageManager().readAllFiles(
                    walk.roots.map(::File), walk.excludedRoots.map(::File), walk.explicitRoots,
                    onError = { if (failure == null) failure = it },
                )) {
                    currentCoroutineContext().ensureActive()
                    if (!file.extension.equals(PDF_SUFFIX, ignoreCase = true)) continue
                    val state = probe.read(file.absolutePath)
                    if (state is ScanPathState.Present) batch.add(state.entry)
                }
            }
            failure?.let { throw it }
        }

    private suspend fun discover(
        generation: Long,
        source: DiscoverySource,
        read: suspend (ScanBatch) -> Unit,
    ) {
        try {
            coroutineScope {
                ensureActive()
                val batch = ScanBatch(generation, source, emit, this)
                try {
                    try {
                        read(batch)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        batch.flush()
                        throw error
                    }
                    batch.flush()
                } finally {
                    batch.close()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            emit(ScanEvent.DiscoverySourceFailed(generation, source, error))
        }
        currentCoroutineContext().ensureActive()
        emit(ScanEvent.DiscoverySourceFinished(generation, source))
    }

    companion object {
        private const val EXTERNAL_VOLUME = "external"
        private const val PDF_SUFFIX = StorageManager.PDF_EXTENSION
    }
}
