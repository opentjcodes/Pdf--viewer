// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.pdf

import android.graphics.Bitmap
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.core.debug.PdfExtractorDebug
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.IOException

private const val TAG = "PdfExtractor"
private const val MAX_METRICS_CHARS = 20_000

class PdfExtractor(
    private val pdfiumCore: PdfiumCore,
    private val pdfDocument: PdfDocument
) {

    private var debugCounted = false

    init {
        if (BuildConfig.DEBUG) {
            debugCounted = true
            PdfExtractorDebug.onExtractorCreated()
        }
    }

    fun getPageText(pageNumber: Int): String {
        return try {
            getPageTextOrThrow(pageNumber)
        }
        catch (throwable: Throwable) {
            Log.e(TAG, "getPageText: failed for page $pageNumber", throwable)
            ""
        }
    }

    fun getPageTextOrThrow(pageNumber: Int): String {
        if (BuildConfig.DEBUG && PdfExtractorDebug.consumePageTextFault(pageNumber)) {
            throw IOException("debug page text fault on page $pageNumber")
        }
        val index = getIndex(pageNumber) ?: return ""
        if (BuildConfig.DEBUG) {
            PdfExtractorDebug.onPageExtraction()
        }
        var opened = false

        pdfiumCore.openPage(pdfDocument, index)
        opened = true
        return try {
            pdfiumCore.getPageText(pdfDocument, index)
        }
        finally {
            if (opened) {
                pdfiumCore.closePage(pdfDocument, index)
            }
        }
    }

    fun getPageCharMetrics(pageNumber: Int): PageCharMetrics? {
        val index = getIndex(pageNumber) ?: return null
        if (BuildConfig.DEBUG) {
            PdfExtractorDebug.onPageExtraction()
        }
        return try {
            var opened = false
            try {
                pdfiumCore.openPage(pdfDocument, index)
                opened = true
                val textPagePtr = pdfiumCore.openTextPage(pdfDocument, index)
                if (textPagePtr == 0L) {
                    return null
                }
                val charCount = pdfiumCore.textCountChars(pdfDocument, index)
                if (charCount <= 0 || charCount > MAX_METRICS_CHARS) {
                    return null
                }
                val raw = pdfiumCore.textCharMetrics(pdfDocument, index, 0, charCount)
                val metrics = PageCharMetrics.fromRaw(raw)
                if (metrics == null && raw.isNotEmpty()) {
                    Log.e(TAG, "getPageCharMetrics: unexpected metrics size ${raw.size} for $charCount chars on page $pageNumber")
                }
                metrics
            }
            finally {
                if (opened) {
                    pdfiumCore.closePage(pdfDocument, index)
                }
            }
        }
        catch (throwable: Throwable) {
            Log.e(TAG, "getPageCharMetrics: failed for page $pageNumber", throwable)
            null
        }
    }

    fun getPageCount() = pdfiumCore.getPageCount(pdfDocument)

    fun renderPageThumbnail(pageIndex: Int, widthPx: Int): Bitmap? {
        return try {
            var opened = false
            try {
                pdfiumCore.openPage(pdfDocument, pageIndex)
                opened = true
                val pageWidth = pdfiumCore.getPageWidthPoint(pdfDocument, pageIndex)
                val pageHeight = pdfiumCore.getPageHeightPoint(pdfDocument, pageIndex)
                if (pageWidth <= 0 || pageHeight <= 0 || widthPx <= 0) {
                    return null
                }
                val heightPx = (widthPx.toFloat() * pageHeight / pageWidth).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
                pdfiumCore.renderPageBitmap(pdfDocument, bitmap, pageIndex, 0, 0, widthPx, heightPx)
                bitmap
            }
            finally {
                if (opened) {
                    pdfiumCore.closePage(pdfDocument, pageIndex)
                }
            }
        }
        catch (throwable: Throwable) {
            Log.e(TAG, "renderPageThumbnail: failed for page $pageIndex", throwable)
            null
        }
    }

    fun getPageLinks(pageNumber: Int): List<PdfDocument.Link> {
        var opened = false
        try {
            pdfiumCore.openPage(pdfDocument, pageNumber)
            opened = true
        }
        catch (throwable: Throwable) {
            Log.e(TAG, "getPageLinks: failed to open page $pageNumber", throwable)
            return listOf()
        }
        return try {
            pdfiumCore.getPageLinks(pdfDocument, pageNumber).filter { it.uri != null }
        }
        finally {
            if (opened) {
                pdfiumCore.closePage(pdfDocument, pageNumber)
            }
        }
    }

    fun getPageHighlights(pageIndex: Int): List<PdfDocument.HighlightAnnotation> {
        var opened = false
        try {
            pdfiumCore.openPage(pdfDocument, pageIndex)
            opened = true
        }
        catch (throwable: Throwable) {
            Log.e(TAG, "getPageHighlights: failed to open page $pageIndex", throwable)
            return listOf()
        }
        return try {
            pdfiumCore.getHighlightAnnotations(pdfDocument, pageIndex)
        }
        finally {
            if (opened) {
                pdfiumCore.closePage(pdfDocument, pageIndex)
            }
        }
    }

    fun getMeta(): PdfDocument.Meta? {
        return try {
            pdfiumCore.getDocumentMeta(pdfDocument)
        }
        catch (throwable: Throwable) {
            Log.e(TAG, "getMeta: failed to read document meta", throwable)
            null
        }
    }

    fun getTableOfContents(): List<TableOfContentsEntry> {
        val tableOfContents = pdfiumCore.getTableOfContents(pdfDocument)
        return tableOfContents.mapIndexed { index, bookmark -> TableOfContentsEntry(bookmark, level = 0, path = index.toString()) }
    }

    fun getAllLinks(): List<Link> {
        val links = mutableListOf<Link>()
        for (i in 0 until getPageCount()) {
            val pageLinks = getPageLinks(i)
            for (link in pageLinks) {
                if (link.uri.isNullOrEmpty() || link.uri.isBlank()) {
                    continue
                }
                links.add(Link(
                    text = "",      // couldn't be extracted yet
                    url = link.uri,
                    pageNumber = i + 1
                ))
            }
        }
        return links
    }

    fun close() {
        try {
            pdfiumCore.closeDocument(pdfDocument)
        } catch (throwable: Throwable) {
            Log.e(TAG, "close: failed to close document", throwable)
        }
        if (BuildConfig.DEBUG && debugCounted) {
            debugCounted = false
            PdfExtractorDebug.onExtractorClosed()
        }
    }

    private fun getIndex(pageNumber: Int): Int? {
        return if (pageNumber < 1) null else pageNumber - 1
    }
}
