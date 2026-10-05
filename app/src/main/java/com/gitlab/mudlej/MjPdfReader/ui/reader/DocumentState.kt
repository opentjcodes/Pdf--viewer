// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import java.time.LocalDateTime
import com.gitlab.mudlej.MjPdfReader.pdf.ReadingDirection

class DocumentState(
    var uri: Uri? = null,
    var name: String = "",
    var password: String? = null,
    var pageNumber: Int = 0,
    var pageRangeStart: Int = 0,
    var pageRangeEnd: Int = 0,
    var length: Int = 0,
    var autoScrollSpeed: Int? = null,
    var fileHash: String? = null,
    var readingDirectionResolved: Boolean = false,
    var readingDirectionOverride: ReadingDirection? = null,
    var detectedReadingDirection: ReadingDirection? = null,
    var effectiveReadingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    var lastQuery: String? = null,
) {

    fun getTitle(): String {
        val extensionIndex: Int = if (name.lastIndexOf('.') == -1) name.length else name.lastIndexOf('.')
        return name.substring(0, extensionIndex)
    }

    fun getPageCounterText(): String {
        val rangeStart = minOf(pageRangeStart, pageNumber)
        if (pageRangeEnd > rangeStart) {
            return String.format("[%s-%s/%s]", rangeStart + 1, pageRangeEnd + 1, length)
        }
        return String.format("[%s/%s]", pageNumber + 1, length)
    }

    fun hasFile() = uri != null

    fun resetLength() {
        length = PDF.RESET_NUMBER
    }

    fun toPdfRecord(fileHash: String, password: String? = null): PdfRecord {
        return PdfRecord(
            fileHash,
            pageNumber,
            uri ?: throw RuntimeException("No fileUri while create PdfRecord"),
            length,
            name.removeSuffix(".pdf"),
            password,
            LocalDateTime.now(),
            ReadingStatus.UNSET,
            false,
            autoScrollSpeed = autoScrollSpeed,
            readingDirectionOverride = readingDirectionOverride?.id,
            detectedReadingDirection = detectedReadingDirection?.id,
        )
    }

    fun initPdfLength(pageCount: Int) {
        if (length == PDF.RESET_NUMBER) {
            length = pageCount
        }
    }
}
