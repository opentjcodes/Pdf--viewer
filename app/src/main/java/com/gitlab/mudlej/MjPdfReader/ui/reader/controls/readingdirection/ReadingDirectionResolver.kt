// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection

import android.app.Activity
import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.pdf.ReadingDirection
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.resolveReadingLayout

class ReadingDirectionResolver(
    private val activity: Activity,
    private val pdf: DocumentState,
    private val pref: Preferences,
    private val pdfRepository: PdfRepository,
) {

    data class LoadState(
        val overrideDirection: ReadingDirection?,
        val detectedDirection: ReadingDirection?,
        val effectiveDirection: ReadingDirection,
    )

    suspend fun resolve(fileHash: String?, documentUri: Uri?): LoadState {
        val overrideDirection = if (pdf.readingDirectionResolved) {
            pdf.readingDirectionOverride
        } else {
            fileHash
                ?.let { pdfRepository.findReadingDirectionOverride(it) }
                ?.let { ReadingDirection.fromOverrideId(it) }
        }
        val storedDetectedDirection = if (pdf.readingDirectionResolved) {
            pdf.detectedReadingDirection
        } else {
            fileHash
                ?.let { pdfRepository.findDetectedReadingDirection(it) }
                ?.let { ReadingDirection.fromId(it) }
        }
        if (overrideDirection != null) {
            return LoadState(
                overrideDirection,
                detectedDirection = storedDetectedDirection,
                effectiveDirection = overrideDirection,
            )
        }

        val detectedDirection = storedDetectedDirection ?: detectIfNeeded(documentUri)

        return LoadState(
            overrideDirection,
            detectedDirection,
            ReadingDirection.effective(overrideDirection, detectedDirection),
        )
    }

    suspend fun detectIfNeeded(documentUri: Uri?): ReadingDirection? {
        if (!resolveReadingLayout(pref).swipeHorizontal || documentUri == null) {
            return null
        }
        val result = ReadingDirectionDetector.detect(activity, documentUri, pdf.password)
        return result.direction.takeIf { result.cacheable }
    }

    suspend fun saveState(fileHash: String) {
        pdfRepository.setReadingDirectionOverride(fileHash, pdf.readingDirectionOverride?.id)
        pdf.detectedReadingDirection?.let {
            pdfRepository.setDetectedReadingDirection(fileHash, it.id)
        }
    }
}
