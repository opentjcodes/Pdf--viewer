// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import android.os.ParcelFileDescriptor
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentity
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfMetadataFailure
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfPasswordException
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal class PdfMetadataExtractor(private val pdfium: PdfiumCore) {
    data class Result(val hash: String?, val pageCount: Int, val failure: String)

    private val activeDocuments = AtomicInteger()
    private val peakDocuments = AtomicInteger()
    val maxActiveDocuments: Int get() = peakDocuments.get()

    fun extract(fingerprint: SourceFingerprint): Result {
        val file = File(fingerprint.path)
        if (!file.exists()) return failure(ScannedPdfMetadataFailure.SOURCE_MISSING)
        if (!matches(file, fingerprint)) return sourceChanged()

        val hash = try {
            DocumentIdentity.of(file)?.identity
        } catch (_: SecurityException) {
            return failure(ScannedPdfMetadataFailure.ACCESS_DENIED)
        } catch (_: IOException) {
            return failure(ScannedPdfMetadataFailure.IO_ERROR)
        }
        if (!matches(file, fingerprint)) return sourceChanged()
        return readPageCount(file, hash)
    }

    private fun readPageCount(file: File, hash: String?): Result {
        var descriptor: ParcelFileDescriptor? = null
        var document: PdfDocument? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            document = pdfium.newDocument(descriptor)
            val active = activeDocuments.incrementAndGet()
            peakDocuments.accumulateAndGet(active) { previous, next -> maxOf(previous, next) }
            Result(hash, pdfium.getPageCount(document), MetadataProtocol.FAILURE_NONE)
        } catch (error: Throwable) {
            failure(pageFailure(error), hash)
        } finally {
            if (document != null) {
                runCatching { pdfium.closeDocument(document) }
                activeDocuments.decrementAndGet()
            }
            runCatching { descriptor?.close() }
        }
    }

    private fun pageFailure(error: Throwable): ScannedPdfMetadataFailure = when (error) {
        is PdfPasswordException -> ScannedPdfMetadataFailure.PASSWORD_REQUIRED
        is FileNotFoundException -> ScannedPdfMetadataFailure.SOURCE_MISSING
        is SecurityException -> ScannedPdfMetadataFailure.ACCESS_DENIED
        is IOException, is IllegalArgumentException -> ScannedPdfMetadataFailure.INVALID_PDF
        else -> ScannedPdfMetadataFailure.INTERNAL_ERROR
    }

    private fun matches(file: File, fingerprint: SourceFingerprint): Boolean =
        file.length() == fingerprint.size &&
            file.lastModified() / 1_000L * 1_000L == fingerprint.lastModified

    private fun sourceChanged() = Result(null, 0, MetadataProtocol.FAILURE_SOURCE_CHANGED)

    private fun failure(failure: ScannedPdfMetadataFailure, hash: String? = null) =
        Result(hash, 0, failure.name)
}
