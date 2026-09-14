// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import android.util.Log
import androidx.room.withTransaction
import com.gitlab.mudlej.MjPdfReader.core.debug.AnnotationTestHooks
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentities
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentity
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfAnnotationSaveDestination
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataFailure
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.TextModeReflowOverride
import com.gitlab.mudlej.MjPdfReader.data.entity.UserBookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class PdfRepository(private val database: AppDatabase) {

    private suspend fun safeWrite(block: () -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Database write failed", e)
            }
        }
    }

    suspend fun findAllRecords(): List<PdfRecord> {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findAll()
        }
    }

    fun observeRecords(): Flow<List<PdfRecord>> = database.pdfRecordDao().observeAll().distinctUntilChanged()

    suspend fun upsertRecords(records: List<PdfRecord>) {
        safeWrite {
            database.pdfRecordDao().insertAll(records)
        }
    }

    suspend fun findUserBookmarks(fileHash: String): List<UserBookmark> {
        return withContext(Dispatchers.IO) {
            database.userBookmarkDao().findByHash(fileHash)
        }
    }

    suspend fun findAllUserBookmarks(): List<UserBookmark> {
        return withContext(Dispatchers.IO) {
            database.userBookmarkDao().findAll()
        }
    }

    suspend fun addUserBookmark(bookmark: UserBookmark) {
        safeWrite {
            val dao = database.userBookmarkDao()
            val orderedBookmark = if (bookmark.sortOrder < 0) {
                bookmark.copy(sortOrder = dao.nextSortOrder(bookmark.fileHash))
            } else {
                bookmark
            }
            dao.insertIfAbsent(orderedBookmark)
        }
    }

    suspend fun upsertUserBookmarks(bookmarks: List<UserBookmark>) {
        safeWrite {
            database.userBookmarkDao().upsertAll(bookmarks)
        }
    }

    suspend fun removeUserBookmark(fileHash: String, pageIndex: Int) {
        safeWrite {
            database.userBookmarkDao().delete(fileHash, pageIndex)
        }
    }

    suspend fun setUserBookmarkLabel(fileHash: String, pageIndex: Int, label: String?) {
        safeWrite {
            database.userBookmarkDao().updateLabel(fileHash, pageIndex, label)
        }
    }

    suspend fun setUserBookmarkOrder(bookmarks: List<UserBookmark>) {
        safeWrite {
            val dao = database.userBookmarkDao()
            bookmarks.forEach { bookmark ->
                dao.updateSortOrder(bookmark.fileHash, bookmark.pageIndex, bookmark.sortOrder)
            }
        }
    }

    suspend fun findRecord(fileHash: String): PdfRecord? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findByHash(fileHash)
        }
    }

    suspend fun updateRecordIdentity(
        fileHash: String,
        uri: android.net.Uri,
        fileName: String,
        lastOpened: LocalDateTime,
    ) {
        safeWrite {
            database.pdfRecordDao().updateIdentity(fileHash, uri, fileName, lastOpened)
        }
    }

    suspend fun findRecordByUri(uri: String): PdfRecord? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findByUri(uri)
        }
    }

    suspend fun repairRecordIdentity(
        previous: PdfRecord,
        uri: android.net.Uri,
        fileName: String,
        canRepair: () -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!canRepair()) return@withTransaction false
            database.pdfRecordDao().repairIdentity(
                previous.hash, previous.uri, previous.sourceUri, uri, fileName,
            ) == 1
        }
    }

    suspend fun setRecordSourceUri(fileHash: String, sourceUri: String?) {
        safeWrite {
            database.pdfRecordDao().updateSourceUri(fileHash, sourceUri)
        }
    }

    suspend fun findAnnotationSaveDestinationBySourceKey(sourceKey: String): PdfAnnotationSaveDestination? {
        return withContext(Dispatchers.IO) {
            database.pdfAnnotationSaveDestinationDao().findBySourceKey(sourceKey)
        }
    }

    suspend fun findAnnotationSaveDestinationByDestinationUri(destinationUri: String): PdfAnnotationSaveDestination? {
        return withContext(Dispatchers.IO) {
            database.pdfAnnotationSaveDestinationDao().findByDestinationUri(destinationUri)
        }
    }

    suspend fun findAnnotationSaveDestinationByLastSavedHash(hash: String): PdfAnnotationSaveDestination? {
        return withContext(Dispatchers.IO) {
            database.pdfAnnotationSaveDestinationDao().findByLastSavedHash(hash)
        }
    }

    suspend fun saveAnnotationSaveDestination(destination: PdfAnnotationSaveDestination) {
        safeWrite {
            database.pdfAnnotationSaveDestinationDao().upsert(destination)
        }
    }

    suspend fun saveRecordInBackground(pdfRecord: PdfRecord) {
        safeWrite {
            database.pdfRecordDao().insert(pdfRecord)
        }
    }

    suspend fun resolveIdentity(context: android.content.Context, uri: android.net.Uri?): String? {
        val identities = DocumentIdentity.of(context, uri) ?: return null
        return resolveIdentity(identities, uri, context)
    }

    suspend fun resolveIdentity(
        identities: DocumentIdentities,
        uri: android.net.Uri? = null,
        context: android.content.Context? = null,
    ): String {
        AnnotationTestHooks.awaitIdentityRelease()
        val dao = database.pdfRecordDao()
        val shouldConvert = withContext(Dispatchers.IO) {
            if (dao.hasRecord(identities.identity)) {
                false
            } else {
                val legacyRecord = dao.findByHash(identities.legacy)
                legacyRecord != null && (uri == null || sameDocumentUri(context, legacyRecord.uri, uri))
            }
        }
        if (shouldConvert) {
            rekeyDocument(identities.legacy, identities.identity)
        }
        return identities.identity
    }

    private fun sameDocumentUri(
        context: android.content.Context?,
        recordUri: android.net.Uri,
        openedUri: android.net.Uri,
    ): Boolean {
        if (recordUri.toString() == openedUri.toString()) {
            return true
        }
        val recordPath = canonicalPathOf(context, recordUri) ?: return false
        val openedPath = canonicalPathOf(context, openedUri) ?: return false
        return recordPath == openedPath
    }

    private fun canonicalPathOf(context: android.content.Context?, uri: android.net.Uri): String? {
        val file = if (uri.scheme == "file") {
            uri.path?.let { java.io.File(it) }
        } else {
            context?.let { UriCanonicalizer.canonicalize(it, uri) }
        } ?: return null
        return runCatching { file.canonicalPath }.getOrNull()
    }

    suspend fun rekeyDocument(oldHash: String, newHash: String) {
        if (oldHash == newHash) {
            return
        }
        safeWrite {
            database.runInTransaction {
                database.pdfRecordDao().rekey(oldHash, newHash)
                database.userBookmarkDao().rekey(oldHash, newHash)
                database.pdfAnnotationSaveDestinationDao().rekeyLastSavedHash(oldHash, newHash)
            }
        }
    }

    suspend fun copyOrUpdateRecordIdentity(
        oldHash: String,
        newHash: String,
        sourceUri: android.net.Uri,
        destinationUri: android.net.Uri,
        fileName: String,
    ) {
        val replacingSourceFile = sourceUri.toString() == destinationUri.toString()
        if (oldHash == newHash) {
            safeWrite {
                database.pdfRecordDao()
                    .updateIdentity(oldHash, destinationUri, fileName, LocalDateTime.now())
            }
            return
        }
        if (replacingSourceFile) {
            rekeyDocument(oldHash, newHash)
            safeWrite {
                database.pdfRecordDao()
                    .updateIdentity(newHash, destinationUri, fileName, LocalDateTime.now())
            }
            return
        }
        safeWrite {
            val dao = database.pdfRecordDao()
            val source = dao.findByHash(oldHash) ?: return@safeWrite
            dao.insert(
                source.copy(
                    hash = newHash,
                    uri = destinationUri,
                    fileName = fileName,
                    lastOpened = LocalDateTime.now(),
                )
            )
        }
    }

    suspend fun findPageNumber(fileHash: String): Int {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findSavedPage(fileHash) ?: 0
        }
    }

    suspend fun findPdfPassword(fileHash: String): String? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findPdfPassword(fileHash)
        }
    }

    suspend fun findCropMargins(fileHash: String, version: Int): String? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findCropMargins(fileHash, version)
        }
    }

    suspend fun findAutoScrollSpeed(fileHash: String): Int? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findAutoScrollSpeed(fileHash)
        }
    }

    suspend fun findReadingDirectionOverride(fileHash: String): String? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findReadingDirectionOverride(fileHash)
        }
    }

    suspend fun findDetectedReadingDirection(fileHash: String): String? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findDetectedReadingDirection(fileHash)
        }
    }

    suspend fun setPageNumber(fileHash: String, page: Int) {
        safeWrite {
            database.pdfRecordDao().updatePageNumber(fileHash, page)
        }
    }

    suspend fun hasRecord(fileHash: String): Boolean {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().hasRecord(fileHash)
        }
    }

    suspend fun setLastOpened(fileHash: String, lastOpened: LocalDateTime) {
        safeWrite {
            database.pdfRecordDao().updateLastOpened(fileHash, lastOpened)
        }
    }

    suspend fun setLength(fileHash: String, length: Int) {
        if (length <= 0) {
            return
        }
        safeWrite {
            database.pdfRecordDao().updateLength(fileHash, length)
        }
    }

    suspend fun removeRecord(record: PdfRecord) {
        safeWrite {
            database.pdfRecordDao().delete(record)
        }
    }

    suspend fun removeRecords(fileHashes: List<String>) {
        safeWrite {
            database.pdfRecordDao().deleteByHashes(fileHashes)
        }
    }

    suspend fun removeAllRecords(): Int {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().deleteAll()
        }
    }

    suspend fun replaceAllHistory(records: List<PdfRecord>, bookmarks: List<UserBookmark>) {
        database.withTransaction {
            database.pdfRecordDao().deleteAll()
            database.userBookmarkDao().deleteAll()
            database.pdfAnnotationSaveDestinationDao().deleteAll()
            if (records.isNotEmpty()) {
                database.pdfRecordDao().insertAll(records)
            }
            if (bookmarks.isNotEmpty()) {
                database.userBookmarkDao().upsertAll(bookmarks)
            }
        }
    }

    suspend fun clearAllPasswords(): Int {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().clearPasswords()
        }
    }

    suspend fun removeUserBookmarksByFileHash(fileHash: String) {
        safeWrite {
            database.userBookmarkDao().deleteByFileHash(fileHash)
        }
    }

    suspend fun removeAllUserBookmarks(): Int {
        return withContext(Dispatchers.IO) {
            database.userBookmarkDao().deleteAll()
        }
    }

    suspend fun removeAnnotationSaveDestinationBySourceKey(sourceKey: String) {
        safeWrite {
            database.pdfAnnotationSaveDestinationDao().deleteBySourceKey(sourceKey)
        }
    }

    suspend fun removeAnnotationSaveDestinationByLastSavedHash(hash: String) {
        safeWrite {
            database.pdfAnnotationSaveDestinationDao().deleteByLastSavedHash(hash)
        }
    }

    suspend fun removeAllAnnotationSaveDestinations(): Int {
        return withContext(Dispatchers.IO) {
            database.pdfAnnotationSaveDestinationDao().deleteAll()
        }
    }

    suspend fun setFavorite(fileHash: String, favorite: Boolean) {
        safeWrite {
            database.pdfRecordDao().updateFavorite(fileHash, favorite)
        }
    }

    suspend fun setFavoriteBatch(fileHashes: List<String>, favorite: Boolean) {
        safeWrite {
            database.pdfRecordDao().updateFavoriteBatch(fileHashes, favorite)
        }
    }

    suspend fun setHidden(fileHash: String, hidden: Boolean) {
        safeWrite {
            database.pdfRecordDao().updateHidden(fileHash, hidden)
        }
    }

    suspend fun setReading(fileHash: String, readingStatus: ReadingStatus) {
        safeWrite {
            database.pdfRecordDao().updateReading(fileHash, readingStatus)
        }
    }

    suspend fun setReadingBatch(fileHashes: List<String>, readingStatus: ReadingStatus) {
        safeWrite {
            database.pdfRecordDao().updateReadingBatch(fileHashes, readingStatus)
        }
    }

    suspend fun findAllScannedPdfs(): List<ScannedPdfEntry> {
        return withContext(Dispatchers.IO) {
            database.scannedPdfEntryDao().findAll()
        }
    }

    suspend fun findScannedPdfByPath(path: String): ScannedPdfEntry? = withContext(Dispatchers.IO) {
        database.scannedPdfEntryDao().findByPath(path)
    }

    suspend fun pruneScannedPdf(fingerprint: SourceFingerprint): Boolean = scanTransaction {
        database.scannedPdfEntryDao().deleteByFingerprint(
            fingerprint.path, fingerprint.size, fingerprint.lastModified,
        ) == 1
    }

    suspend fun findPendingScannedPdfs(): List<ScannedPdfEntry> {
        return withContext(Dispatchers.IO) {
            database.scannedPdfEntryDao().findPending()
        }
    }

    suspend fun persistDiscoveredPdfs(entries: List<ScannedPdfEntry>) {
        scanTransaction {
            database.scannedPdfEntryDao().upsertAll(entries)
        }
    }

    suspend fun completeScannedPdfMetadata(
        fingerprint: SourceFingerprint,
        hash: String?,
        pageCount: Int,
    ): Boolean = scanTransaction {
        database.scannedPdfEntryDao().completeMetadata(
            path = fingerprint.path,
            size = fingerprint.size,
            lastModified = fingerprint.lastModified,
            hash = hash,
            pageCount = pageCount,
        ) == 1
    }

    suspend fun failScannedPdfMetadata(
        fingerprint: SourceFingerprint,
        hash: String?,
        failure: ScannedPdfMetadataFailure,
    ): Boolean = scanTransaction {
        database.scannedPdfEntryDao().failMetadata(
            path = fingerprint.path,
            size = fingerprint.size,
            lastModified = fingerprint.lastModified,
            hash = hash,
            failure = failure,
        ) == 1
    }

    suspend fun recordScannedPdfWorkerDeath(fingerprint: SourceFingerprint): ScannedPdfEntry? =
        scanTransaction {
            val changed = database.scannedPdfEntryDao().recordWorkerDeath(
                path = fingerprint.path,
                size = fingerprint.size,
                lastModified = fingerprint.lastModified,
            )
            if (changed == 0) return@scanTransaction null
            database.scannedPdfEntryDao().findByPath(fingerprint.path)
        }

    suspend fun updateScannedPdfHash(fingerprint: SourceFingerprint, hash: String): Boolean =
        withContext(Dispatchers.IO) {
            database.scannedPdfEntryDao().updateHash(
                path = fingerprint.path,
                size = fingerprint.size,
                lastModified = fingerprint.lastModified,
                hash = hash,
            ) == 1
        }

    suspend fun pruneScannedPdfsThrowing(paths: List<String>) {
        scanTransaction {
            database.scannedPdfEntryDao().deleteByPaths(paths)
        }
    }

    suspend fun renameScannedPdfThrowing(oldPath: String, newPath: String): Boolean =
        scanTransaction {
            database.scannedPdfEntryDao().updatePath(oldPath, newPath) == 1
        }

    suspend fun findScannedPdfsByHash(hash: String): List<ScannedPdfEntry> {
        return withContext(Dispatchers.IO) {
            database.scannedPdfEntryDao().findByHash(hash)
        }
    }

    private suspend fun <T> scanTransaction(block: () -> T): T = withContext(Dispatchers.IO) {
        database.withTransaction { block() }
    }

    suspend fun setPassword(fileHash: String, password: String) {
        safeWrite {
            database.pdfRecordDao().updatePassword(fileHash, password)
        }
    }

    suspend fun setDocumentTitle(fileHash: String, title: String?) {
        safeWrite {
            database.pdfRecordDao().updateDocumentTitle(fileHash, title)
        }
    }

    suspend fun setCropMargins(fileHash: String, cropMargins: String, version: Int) {
        safeWrite {
            database.pdfRecordDao().updateCropMargins(fileHash, cropMargins, version)
        }
    }

    suspend fun setAutoScrollSpeed(fileHash: String, speed: Int) {
        safeWrite {
            database.pdfRecordDao().updateAutoScrollSpeed(fileHash, speed)
        }
    }

    suspend fun setReadingDirectionOverride(fileHash: String, direction: String?) {
        safeWrite {
            database.pdfRecordDao().updateReadingDirectionOverride(fileHash, direction)
        }
    }

    suspend fun setDetectedReadingDirection(fileHash: String, direction: String) {
        safeWrite {
            database.pdfRecordDao().updateDetectedReadingDirection(fileHash, direction)
        }
    }

    suspend fun findTextModeReflow(fileHash: String): TextModeReflowOverride? {
        return withContext(Dispatchers.IO) {
            database.pdfRecordDao().findTextModeReflow(fileHash)
        }
    }

    suspend fun setTextModeReflow(fileHash: String, joinParagraphs: Boolean?, detectHeadings: Boolean?, codeBlocks: Boolean?) {
        safeWrite {
            database.pdfRecordDao().updateTextModeReflow(fileHash, joinParagraphs, detectHeadings, codeBlocks)
        }
    }

    private companion object {
        const val TAG = "PdfRepository"
    }
}
