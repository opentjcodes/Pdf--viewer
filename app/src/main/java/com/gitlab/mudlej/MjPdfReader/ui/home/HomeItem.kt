// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataState
import com.gitlab.mudlej.MjPdfReader.core.io.computeHash
import java.io.File
import java.time.LocalDateTime

data class HomeItem(
    val hash: String,
    val uri: Uri,
    val title: String,
    val pageNumber: Int,
    val length: Int,
    val favorite: Boolean,
    val readingStatus: ReadingStatus,
    val lastOpened: LocalDateTime,
    val isScanOnly: Boolean,
    val coverKey: String,
    val sizeBytes: Long,
    val hidden: Boolean,
    val sortKey: String,
    val availability: Availability = Availability.AVAILABLE,
) {
    val stableKey: String
        get() = uri.normalizeScheme().toString()


    val available: Boolean
        get() = availability == Availability.AVAILABLE

    val hasBeenOpened: Boolean
        get() = lastOpened != LocalDateTime.parse(PdfRecord.UNSET_DATE)

    val readingStarted: Boolean
        get() = length > 0 && (hasBeenOpened || pageNumber > 0)

    val progressPercent: Int
        get() = if (!readingStarted) {
            0
        } else {
            ((pageNumber + 1) * 100 / length).coerceIn(1, 100)
        }

    companion object {

        private const val ANNOTATED_SUFFIX = "-annotated"

        fun from(
            record: PdfRecord,
            showPdfTitle: Boolean,
            availability: Availability,
            annotatedTitleFormat: String,
        ): HomeItem {
            val documentTitle = record.documentTitle
            val title = if (showPdfTitle && !documentTitle.isNullOrBlank()) {
                if (record.fileName.endsWith(ANNOTATED_SUFFIX)) {
                    annotatedTitleFormat.format(documentTitle)
                } else {
                    documentTitle
                }
            } else {
                record.fileName
            }
            return HomeItem(
                hash = record.hash,
                uri = record.uri,
                title = title,
                pageNumber = record.pageNumber,
                length = record.length,
                favorite = record.favorite,
                readingStatus = record.reading,
                lastOpened = record.lastOpened,
                isScanOnly = false,
                coverKey = record.hash,
                sizeBytes = if (record.uri.scheme == "file") {
                    record.uri.path?.let { File(it).length() } ?: 0L
                } else {
                    0L
                },
                hidden = record.hidden,
                sortKey = sortKeyOf(record.fileName),
                availability = availability,
            )
        }

        fun fromScan(entry: ScannedPdfEntry): HomeItem {
            val file = File(entry.path)
            val syntheticKey =
                "p" + (computeHash(entry.path.toByteArray()) ?: entry.path.hashCode().toString())
            val contentKey = entry.hash ?: syntheticKey
            return HomeItem(
                hash = contentKey,
                uri = Uri.fromFile(file),
                title = file.nameWithoutExtension,
                pageNumber = 0,
                length = entry.pageCount.takeIf {
                    entry.metadataState == ScannedPdfMetadataState.COMPLETE
                } ?: 0,
                favorite = false,
                readingStatus = ReadingStatus.UNSET,
                lastOpened = LocalDateTime.parse(PdfRecord.UNSET_DATE),
                isScanOnly = true,
                coverKey = contentKey,
                sizeBytes = entry.size,
                hidden = false,
                sortKey = sortKeyOf(file.name),
            )
        }

        private fun sortKeyOf(name: String): String {
            return name.lowercase().removeSuffix(".pdf")
        }
    }
}
