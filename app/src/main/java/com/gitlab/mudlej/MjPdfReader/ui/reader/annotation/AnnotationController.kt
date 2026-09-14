// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.ReplayMeasurement
import com.github.barteksc.pdfviewer.preview.TagSource
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationJournal
import com.gitlab.mudlej.MjPdfReader.data.annotation.SourceKey
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnnotationController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val historyPolicy: HistoryPolicy,
) {
    private val journal = AnnotationJournal(context)
    private val pdf get() = vm.doc

    private val pageContentTags = ConcurrentHashMap<Int, Int>()

    val previewTagSource = TagSource { page -> pageContentTags[page] ?: 0 }

    val currentSaveDestinationUri: Uri?
        get() = vm.annotationSaveDestinationUri
    val currentSaveDestinationDurable: Boolean
        get() = vm.annotationSaveDestinationDurable
    val hasUnsavedAnnotations: Boolean
        get() = vm.hasUnsavedAnnotations
    val isSaving: Boolean
        get() = vm.isSavingAnnotations

    fun setCurrentSaveDestination(uri: Uri?, durable: Boolean = true) {
        vm.annotationSaveDestinationUri = uri
        vm.annotationSaveDestinationDurable = uri != null && durable
    }

    fun markDirty() {
        vm.hasUnsavedAnnotations = true
    }

    fun clearDirty() {
        vm.hasUnsavedAnnotations = false
    }

    fun setSaving(saving: Boolean) {
        vm.isSavingAnnotations = saving
    }

    fun recordEdit(edit: AnnotationEdit) {
        val uri = pdf.uri ?: return
        applyContentTag(edit)
        if (historyPolicy.canRecord()) {
            journal.append(uri, edit)
        }
        vm.sessionOwnedAnnotationKeys.add(SourceKey.of(uri))
        vm.hasUnsavedAnnotations = true
    }

    fun resetContentTags() {
        pageContentTags.clear()
    }

    private fun applyContentTag(edit: AnnotationEdit) {
        val hash = renderAffectingHash(edit) ?: return
        val previous = pageContentTags[edit.page] ?: 0
        pageContentTags[edit.page] = 31 * previous + hash
    }

    private fun renderAffectingHash(edit: AnnotationEdit): Int? = when (edit) {
        is AnnotationEdit.AddSignature,
        is AnnotationEdit.SetFieldText,
        is AnnotationEdit.SetFieldChecked -> edit.toJsonLine().hashCode()
        else -> null
    }

    fun hasJournal(uri: Uri?): Boolean {
        return uri != null && journal.hasRecords(uri)
    }

    fun isSessionOwned(uri: Uri?): Boolean {
        return uri != null && SourceKey.of(uri) in vm.sessionOwnedAnnotationKeys
    }

    fun markSessionOwned(uri: Uri?) {
        uri?.let { vm.sessionOwnedAnnotationKeys.add(SourceKey.of(it)) }
    }

    fun clearJournal(uri: Uri? = pdf.uri, resetContentTags: Boolean = true) {
        uri?.let(journal::delete)
        if (uri != null && uri != pdf.uri) {
            return
        }
        if (resetContentTags) {
            pageContentTags.clear()
        }
        vm.hasUnsavedAnnotations = false
    }

    suspend fun replayJournal(): Boolean {
        val uri = pdf.uri ?: return false
        val edits = withContext(Dispatchers.IO) { journal.readAll(uri) }
        if (edits.isEmpty()) {
            return false
        }
        withContext(Dispatchers.Main) {
            if (BuildConfig.DEBUG && PDFView.isDebugStateEnabled()) {
                replayMeasuredEdits(edits)
            } else {
                pageContentTags.clear()
                edits.forEach(::applyEdit)
            }
        }
        markSessionOwned(uri)
        vm.hasUnsavedAnnotations = true
        return true
    }

    private fun replayMeasuredEdits(edits: List<AnnotationEdit>) {
        val measurement = ReplayMeasurement.start(SystemClock.elapsedRealtimeNanos(), edits.size)
        val replayStartNanos = SystemClock.elapsedRealtimeNanos()
        var replayReturned = false
        try {
            pageContentTags.clear()
            edits.forEachIndexed { index, edit ->
                applyMeasuredEdit(edit, index, measurement)
            }
            replayReturned = true
        } finally {
            val durationNanos = SystemClock.elapsedRealtimeNanos() - replayStartNanos
            val report = ReplayMeasurement.finish(
                measurement,
                durationNanos,
                replayReturned,
            )
            report.lines().forEach { line -> Log.i(ReplayMeasurement.LOG_TAG, line) }
        }
    }

    private fun applyMeasuredEdit(edit: AnnotationEdit, index: Int, measurement: ReplayMeasurement.Session) {
        measurement.beginEdit(index, edit.javaClass.simpleName, edit.page)
        val editStartNanos = SystemClock.elapsedRealtimeNanos()
        var editReturned = false
        try {
            applyEdit(edit)
            editReturned = true
        } finally {
            val durationNanos = SystemClock.elapsedRealtimeNanos() - editStartNanos
            measurement.endEdit(index, durationNanos, editReturned)
        }
    }

    private fun applyEdit(edit: AnnotationEdit) {
        applyContentTag(edit)
        val pdfView = binding.pdfView
        val applied = when (edit) {
            is AnnotationEdit.Add ->
                pdfView.addHighlightAnnotation(edit.page, edit.rects, edit.color, edit.contents, edit.group, edit.createdDate)
            is AnnotationEdit.Recolor ->
                pdfView.setHighlightAnnotationColor(highlightReference(edit.page, edit.group), edit.color)
            is AnnotationEdit.SetNote ->
                pdfView.setHighlightAnnotationNote(highlightReference(edit.page, edit.group), edit.note, edit.modifiedDate)
            is AnnotationEdit.Delete ->
                pdfView.removeHighlightAnnotation(highlightReference(edit.page, edit.group))
            is AnnotationEdit.SetFieldText ->
                pdfView.setFormFieldText(edit.page, edit.fieldIndex, edit.text)
            is AnnotationEdit.SetFieldChecked ->
                pdfView.setFormFieldChecked(edit.page, edit.fieldIndex, edit.checked)
            is AnnotationEdit.AddSignature ->
                pdfView.addSignature(edit.page, edit.rect, edit.strokes.toTypedArray(), edit.color, edit.strokeWidth)
        }
        if (!applied) {
            Log.w(TAG, "applyEdit: skipped ${edit.javaClass.simpleName} on page ${edit.page}")
        }
    }

    private fun highlightReference(page: Int, group: String): PDFView.HighlightAnnotation {
        return PDFView.HighlightAnnotation(page, -1, group, null, "")
    }

    companion object {
        private const val TAG = "AnnotationController"

        fun sourceKey(uri: Uri): String = SourceKey.of(uri)
    }
}
