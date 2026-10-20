// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.MetadataMemorySample
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryScanCoordinator
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.MetadataResult
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanEvent
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanRefreshReason
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LibraryScanner private constructor(
    private val context: Context,
    private val repository: PdfRepository,
) {
    private val coordinator = LibraryScanCoordinator(context, repository)
    val index: StateFlow<LibraryIndex> = coordinator.index
    private var observer: ContentObserver? = null
    private val observerOwners = HashSet<Any>()

    fun refresh(reason: ScanRefreshReason = ScanRefreshReason.FOREGROUND) = coordinator.refresh(reason)

    fun onStorageAccessChanged(hasFullAccess: Boolean) {
        coordinator.post(ScanEvent.StorageAccessChanged(hasFullAccess))
    }

    fun onScopeChanged(previousRoots: List<String>?) {
        coordinator.post(ScanEvent.ScopeChanged(previousRoots?.toList()))
    }

    fun resumeMetadataDispatch() = coordinator.resumeMetadataDispatch()

    fun startObserving(owner: Any) {
        if (!observerOwners.add(owner) || observer != null) return
        val newObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = coordinator.debounceObserverChange()
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(EXTERNAL_VOLUME),
            true,
            newObserver,
        )
        observer = newObserver
    }

    fun stopObserving(owner: Any) {
        if (!observerOwners.remove(owner) || observerOwners.isNotEmpty()) return
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    suspend fun findPathByHash(hash: String): String? = withTimeoutOrNull(RELINK_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            repository.findScannedPdfsByHash(hash).firstOrNull { File(it.path).canRead() }?.path
        }
    }

    fun onFileRemoved(path: String) = coordinator.post(ScanEvent.FileRemoved(path))

    fun onFileRenamed(oldPath: String, newPath: String) =
        coordinator.post(ScanEvent.FileRenamed(oldPath, newPath))

    fun onHashComputed(fingerprint: SourceFingerprint, hash: String) =
        coordinator.post(ScanEvent.HashComputed(fingerprint, hash))

    fun fingerprintFor(path: String): SourceFingerprint? =
        index.value.entries.firstOrNull { it.path == path }?.fingerprint

    fun libraryEntries(snapshot: LibraryIndex) = coordinator.libraryEntries(snapshot)

    fun injectStaleMetadataResultForTest(
        fingerprint: SourceFingerprint,
        hash: String?,
        pageCount: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        coordinator.post(
            ScanEvent.WorkerResult(
                MetadataResult(
                    generation = index.value.generation - 1L,
                    connectionToken = Long.MIN_VALUE,
                    fingerprint = fingerprint,
                    hash = hash,
                    pageCount = pageCount,
                    failure = null,
                    sourceChanged = false,
                    sample = MetadataMemorySample(0, 0, 0, false, 0, 0),
                    workerPid = 0,
                    workerInstance = 0,
                    maxActiveDocuments = 0,
                )
            )
        )
    }
    companion object {
        private const val RELINK_TIMEOUT_MS = 2_000L
        private const val EXTERNAL_VOLUME = "external"

        @Volatile
        private var INSTANCE: LibraryScanner? = null

        fun getInstance(context: Context): LibraryScanner {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val app = context.applicationContext
                return LibraryScanner(app, PdfRepository(AppDatabase.getInstance(app))).also { INSTANCE = it }
            }
        }
    }
}
