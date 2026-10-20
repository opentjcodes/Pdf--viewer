// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderUi
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.shockwave.pdfium.PdfDocument
import org.json.JSONArray
import org.json.JSONObject

class ReaderAnnotationDebug(
    private val pdfView: PDFView,
    private val vm: ReaderViewModel,
    private val ui: ReaderUi,
    private val annotationController: AnnotationController? = null,
) {
    @WorkerThread
    fun dumpPageHighlights(pageIndex: Int): String {
        if (!BuildConfig.DEBUG) {
            return "[]"
        }
        val groups = LinkedHashMap<String, MutableList<PdfDocument.HighlightAnnotation>>()
        for (annotation in pdfView.debugPageHighlights(pageIndex)) {
            groups.getOrPut(annotation.groupKey) { mutableListOf() }.add(annotation)
        }
        val out = JSONArray()
        for ((groupKey, members) in groups) {
            val first = members.first()
            out.put(
                JSONObject()
                    .put("group", groupKey)
                    .put("color", first.color)
                    .put("quote", first.quote)
                    .put("note", first.note)
                    .put("index", first.annotationIndex)
                    .put("rects", members.size)
            )
        }
        return out.toString()
    }

    @WorkerThread
    fun dumpFormField(pageIndex: Int, annotationIndex: Int): String {
        if (!BuildConfig.DEBUG) {
            return "{}"
        }
        val field = pdfView.debugFormField(pageIndex, annotationIndex) ?: return "{}"
        return JSONObject()
            .put("page", pageIndex)
            .put("index", field.annotationIndex)
            .put("type", field.type)
            .put("name", field.name)
            .put("value", field.value)
            .put("checked", field.isChecked)
            .put("readOnly", field.isReadOnly)
            .toString()
    }

    @MainThread
    fun dumpPendingStamp(): String {
        if (!BuildConfig.DEBUG) {
            return "{}"
        }
        val pending = pdfView.pendingStampPlacement ?: return "{}"
        val rect = JSONArray()
            .put(pending.pdfRect.left.toDouble())
            .put(pending.pdfRect.top.toDouble())
            .put(pending.pdfRect.right.toDouble())
            .put(pending.pdfRect.bottom.toDouble())
        return JSONObject()
            .put("page", pending.pageIndex)
            .put("rect", rect)
            .put("color", pending.color)
            .put("strokeWidth", pending.normalizedStrokeWidth.toDouble())
            .toString()
    }

    @MainThread
    fun setSavingAnnotations(saving: Boolean) {
        if (!BuildConfig.DEBUG) {
            return
        }
        vm.isSavingAnnotations = saving
        ui.updateDirtyUi()
    }

    @MainThread
    fun clearCurrentSaveDestination() {
        if (!BuildConfig.DEBUG) {
            return
        }
        annotationController?.setCurrentSaveDestination(null, durable = false)
    }
}
