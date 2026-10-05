// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.barteksc.pdfviewer.source.FileSource
import com.github.barteksc.pdfviewer.source.UriSource
import com.gitlab.mudlej.MjPdfReader.core.io.forLog
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.IOException

object PdfExtractorFactory {

    @Throws(IOException::class)
    fun create(context: Context, uri: Uri, password: String? = null): PdfExtractor {
        val pdfium = PdfiumCore(context)
        val parcelFileDescriptor = UriSource.openReadDescriptor(context, uri)
        val pdfDocument = if (password.isNullOrEmpty()) {
            pdfium.newDocument(parcelFileDescriptor)
        } else {
            pdfium.newDocument(parcelFileDescriptor, password)
        }
        return PdfExtractor(pdfium, pdfDocument)
    }

    @Throws(IOException::class)
    fun create(context: Context, file: File, password: String? = null): PdfExtractor {
        val pdfium = PdfiumCore(context)
        val parcelFileDescriptor = FileSource.openReadDescriptor(context, file)
        val pdfDocument = try {
            if (password.isNullOrEmpty()) {
                pdfium.newDocument(parcelFileDescriptor)
            } else {
                pdfium.newDocument(parcelFileDescriptor, password)
            }
        } catch (throwable: Throwable) {
            runCatching { parcelFileDescriptor.close() }
            throw throwable
        }
        return PdfExtractor(pdfium, pdfDocument)
    }

}
fun createPdfExtractor(context: Context, uri: Uri, password: String?): PdfExtractor {
    try {
        return PdfExtractorFactory.create(context, uri, password)
    } catch (throwable: Throwable) {
        Log.w(context::class.simpleName, "createPdfExtractor: Failed to create PdfExtractor by URI=${uri.forLog()} !", throwable)
    }
    try {
        Log.d(context::class.simpleName, "createPdfExtractor: Trying to use OnlineDocumentStore file")
        val heldFile = OnlineDocumentStore.fileFor(context, uri.toString())
        if (heldFile != null) {
            return PdfExtractorFactory.create(context, heldFile, password)
        }
        else {
            Log.e(context::class.simpleName, "createPdfExtractor: OnlineDocumentStore file is null!")
            throw RuntimeException("Failed to createPdfExtractor by URI and by cached file")
        }
    } catch (throwable: Throwable) {
        Log.e(context::class.simpleName, "createPdfExtractor: Failed to create PdfExtractor by cached file!", throwable)
        throw throwable
    }
}
