// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.util.Patterns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.AnnotationTestHooks
import com.gitlab.mudlej.MjPdfReader.core.io.browserLinkIntent
import com.gitlab.mudlej.MjPdfReader.core.io.linkIntent
import com.gitlab.mudlej.MjPdfReader.core.io.pdfDateNow
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationSettings
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationUrlBuilder
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.confirmDialog
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.ui.reader.share.showShareQuoteDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.UUID

class InlineAnnotationActionController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val clearActiveSearchResultHighlight: () -> Unit,
    private val onAnnotationEdit: (AnnotationEdit) -> Unit,
    private val canEditDocument: () -> Boolean,
    private val updateSaveUiPosition: () -> Unit,
    private val isDetectExistingHighlightsEnabled: () -> Boolean,
    private val getHighlightColors: () -> List<Int>,
    private val getDocumentName: () -> String,
    private val getTranslationSettings: () -> TranslationSettings,
    private val onDefineWord: (String, () -> Unit) -> Boolean,
    private val onOpenSettings: () -> Unit,
    private val toggleReaderChrome: () -> Unit,
) {
    private var activeHighlightAnnotation: PDFView.HighlightAnnotation? = null
    private val whitespaceRegex = Regex("\\s+")

    fun configure(onSaveClicked: () -> Unit) {
        binding.textSelectionCopyButton.setOnClickListener {
            if (copySelectedText()) {
                dismissCard()
            }
        }
        binding.textSelectionSearchWebButton.setOnClickListener {
            val link = linkInSelection()
            val handled = if (link != null) openLink(link) else searchWebForSelectedText()
            if (handled) {
                dismissCard()
            }
        }
        binding.textSelectionShareButton.setOnClickListener { shareSelectedQuote() }
        binding.textSelectionNoteButton.setOnClickListener { handleNoteClicked() }
        binding.textSelectionTranslateButton.setOnClickListener {
            if (translateSelectedText()) {
                dismissCard()
            }
        }
        binding.textSelectionDiscardButton.setOnClickListener { dismissCard() }
        rebuildHighlightSwatches()
        binding.textSelectionDeleteHighlightButton.setOnClickListener { deleteActiveHighlightAnnotation() }
        binding.saveAnnotationsFab.setOnClickListener { onSaveClicked() }
    }

    private val swatchIds = mutableListOf<Int>()

    fun rebuildHighlightSwatches() {
        val container = binding.textSelectionActionContent
        val density = container.resources.displayMetrics.density
        swatchIds.forEach { id -> container.findViewById<View>(id)?.let(container::removeView) }
        swatchIds.clear()
        getHighlightColors().forEach { color ->
            val swatchButton = FrameLayout(container.context).apply {
                id = View.generateViewId()
                layoutParams = ConstraintLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                val backgroundValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
                setBackgroundResource(backgroundValue.resourceId)
                isClickable = true
                isFocusable = true
                HighlightPalette.fromColor(color)?.let { contentDescription = context.getString(it.labelRes) }
                setOnClickListener { applyHighlightColor(color) }
            }
            val swatchCard = MaterialCardView(container.context).apply {
                layoutParams = FrameLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt(), Gravity.CENTER)
                radius = 18 * density
                cardElevation = 0f
                setCardBackgroundColor(color)
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
                strokeWidth = density.toInt().coerceAtLeast(1)
            }
            swatchButton.addView(swatchCard)
            container.addView(swatchButton)
            swatchIds.add(swatchButton.id)
        }
        binding.textSelectionSwatchFlow.referencedIds = swatchIds.toIntArray()
    }

    fun handleImmediatePdfTap(event: MotionEvent): Boolean {
        val annotation = binding.pdfView.findHighlightAnnotationAt(event.x, event.y) ?: return false
        clearActiveSearchResultHighlight()
        binding.pdfView.clearTextSelection()
        showHighlightAnnotationActions(annotation)
        return true
    }

    fun handleEmptyTap() {
        if (activeHighlightAnnotation != null) {
            hideActions()
        }
        toggleReaderChrome()
    }

    fun showSelectionActions(viewBounds: RectF?) {
        val matchingAnnotation = findAnnotationMatchingSelection()
        if (matchingAnnotation != null) {
            binding.pdfView.clearTextSelection()
            showHighlightAnnotationActions(matchingAnnotation)
            return
        }

        activeHighlightAnnotation = null
        binding.pdfView.clearSelectedHighlightAnnotation()
        binding.textSelectionCopyButton.visibility = View.VISIBLE
        binding.textSelectionShareButton.visibility = View.VISIBLE
        binding.textSelectionSearchWebButton.visibility = View.VISIBLE
        binding.textSelectionNoteButton.visibility = View.VISIBLE
        binding.textSelectionTranslateButton.visibility = View.VISIBLE
        binding.textSelectionDeleteHighlightButton.visibility = View.GONE
        binding.textSelectionDiscardButton.visibility = View.VISIBLE
        applyNoteButtonState(hasNote = false)
        showCard(viewBounds)
    }

    private fun findAnnotationMatchingSelection(): PDFView.HighlightAnnotation? {
        if (!isDetectExistingHighlightsEnabled()) {
            return null
        }
        val request = binding.pdfView.getHighlightRequest() ?: return null
        return binding.pdfView.findHighlightAnnotationMatching(request)
    }

    fun hideActions() {
        activeHighlightAnnotation = null
        binding.pdfView.clearSelectedHighlightAnnotation()
        binding.textSelectionActionCard.visibility = View.GONE
        updateSaveUiPosition()
    }

    fun isCardAtBottom(): Boolean {
        val card = binding.textSelectionActionCard
        if (card.visibility != View.VISIBLE) {
            return false
        }
        val params = card.layoutParams as ConstraintLayout.LayoutParams
        return params.verticalBias >= 0.5f
    }

    private fun showHighlightAnnotationActions(annotation: PDFView.HighlightAnnotation) {
        activeHighlightAnnotation = annotation
        binding.pdfView.setSelectedHighlightAnnotation(annotation)
        val textActionVisibility = if (annotation.quote.isBlank()) View.GONE else View.VISIBLE
        binding.textSelectionCopyButton.visibility = textActionVisibility
        binding.textSelectionShareButton.visibility = textActionVisibility
        binding.textSelectionSearchWebButton.visibility = textActionVisibility
        binding.textSelectionTranslateButton.visibility = textActionVisibility
        binding.textSelectionNoteButton.visibility = View.VISIBLE
        binding.textSelectionDeleteHighlightButton.visibility = View.VISIBLE
        binding.textSelectionDiscardButton.visibility = View.GONE
        applyNoteButtonState(hasNote = annotation.note.isNotBlank())
        showCard(annotation.viewBounds)
    }

    private fun showCard(viewBounds: RectF?) {
        val card = binding.textSelectionActionCard
        val params = card.layoutParams as ConstraintLayout.LayoutParams
        if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val selectionNearBottom = viewBounds != null && viewBounds.centerY() > binding.pdfView.height * 0.65f
        params.verticalBias = if (selectionNearBottom) 0f else 1f
        card.layoutParams = params
        card.visibility = View.VISIBLE
        applySearchWebButtonState()
        refreshCardRendering()
    }

    private fun applySearchWebButtonState() {
        val hasLink = linkInSelection() != null
        val button = binding.textSelectionSearchWebButton
        button.setIconResource(if (hasLink) R.drawable.ic_link else R.drawable.ic_web)
        button.contentDescription = activity.getString(
            if (hasLink) R.string.open_link else R.string.search_web
        )
    }

    private fun linkInSelection(): String? {
        val text = selectedText()
        if (text.isBlank()) {
            return null
        }
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) {
            return null
        }
        val match = matcher.group()
        return if (match.contains("://")) match else "https://$match"
    }

    private fun openLink(url: String): Boolean {
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: ActivityNotFoundException) {
            AppSnackbar.make(binding.root, activity.getString(R.string.no_app_to_open_link), Snackbar.LENGTH_LONG).show()
            false
        }
    }

    private fun dismissCard() {
        if (activeHighlightAnnotation != null) {
            hideActions()
        } else {
            binding.pdfView.clearTextSelection()
        }
    }

    private fun applyHighlightColor(color: Int) {
        val annotation = activeHighlightAnnotation
        if (annotation != null) {
            updateActiveHighlightAnnotationColor(annotation, color)
        } else {
            addInlineHighlight(color)
        }
    }

    private fun addInlineHighlight(color: Int) {
        val request = binding.pdfView.getHighlightRequest()
        val groupKey = UUID.randomUUID().toString()
        val createdDate = pdfDateNow()
        if (!canEditDocument()) {
            return
        }
        if (request == null || AnnotationTestHooks.consumeHighlightWriteFailure() ||
            !binding.pdfView.addHighlight(request, color, groupKey, createdDate)
        ) {
            AppSnackbar.make(binding.root, R.string.highlight_failed, Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.pdfView.clearTextSelection()
        refreshCardRendering()
        onAnnotationEdit(
            AnnotationEdit.Add(request.pageIndex, groupKey, request.pdfRects, color, request.selectedText, createdDate)
        )
    }

    private fun handleNoteClicked() {
        val annotation = activeHighlightAnnotation
        if (annotation != null && annotation.note.isNotBlank()) {
            showNoteViewDialog(annotation)
        } else {
            showNoteEditorDialog(annotation)
        }
    }

    private fun applyNoteButtonState(hasNote: Boolean) {
        binding.textSelectionNoteButton.setIconResource(if (hasNote) R.drawable.ic_comment else R.drawable.ic_comment_add)
        binding.textSelectionNoteButton.contentDescription =
            activity.getString(if (hasNote) R.string.view_note else R.string.add_note)
    }

    private fun showNoteEditorDialog(annotation: PDFView.HighlightAnnotation?) {
        val density = activity.resources.displayMetrics.density
        val editText = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            hint = activity.getString(R.string.note_hint)
            setText(annotation?.note.orEmpty())
            setSelection(text.length)
        }
        val container = FrameLayout(activity).apply {
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.note)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val note = editText.text.toString().trim()
                if (annotation != null) {
                    applyNoteToAnnotation(annotation, note)
                } else if (note.isNotBlank()) {
                    addNoteToSelection(note)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNoteViewDialog(annotation: PDFView.HighlightAnnotation) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.note)
            .setMessage(annotation.note)
            .setPositiveButton(R.string.edit) { _, _ -> showNoteEditorDialog(annotation) }
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.delete) { _, _ ->
                confirmDialog(
                    activity,
                    R.string.delete_note_title,
                    activity.getString(R.string.delete_note_message),
                    R.string.delete,
                ) {
                    applyNoteToAnnotation(annotation, "")
                }
            }
            .show()
    }

    private fun applyNoteToAnnotation(annotation: PDFView.HighlightAnnotation, note: String) {
        if (note.isBlank() && annotation.note.isBlank()) {
            return
        }
        val date = pdfDateNow()
        val updated = binding.pdfView.setHighlightAnnotationNote(annotation, note, date)
        if (!updated) {
            AppSnackbar.make(binding.root, R.string.note_failed, Snackbar.LENGTH_SHORT).show()
            return
        }
        hideActions()
        onAnnotationEdit(AnnotationEdit.SetNote(annotation.pageIndex, annotation.groupKey, note, date))
    }

    private fun addNoteToSelection(note: String) {
        val request = binding.pdfView.getHighlightRequest()
        val groupKey = UUID.randomUUID().toString()
        val createdDate = pdfDateNow()
        val color = HighlightPalette.noteHighlight.colorValue
        if (!canEditDocument()) {
            return
        }
        if (request == null || AnnotationTestHooks.consumeHighlightWriteFailure() ||
            !binding.pdfView.addHighlight(request, color, groupKey, createdDate)
        ) {
            AppSnackbar.make(binding.root, R.string.highlight_failed, Snackbar.LENGTH_SHORT).show()
            return
        }
        onAnnotationEdit(
            AnnotationEdit.Add(request.pageIndex, groupKey, request.pdfRects, color, request.selectedText, createdDate)
        )

        val reference = PDFView.HighlightAnnotation(request.pageIndex, -1, groupKey, null, "")
        if (binding.pdfView.setHighlightAnnotationNote(reference, note, createdDate)) {
            onAnnotationEdit(AnnotationEdit.SetNote(request.pageIndex, groupKey, note, createdDate))
        } else {
            AppSnackbar.make(binding.root, R.string.note_failed, Snackbar.LENGTH_SHORT).show()
        }
        binding.pdfView.clearTextSelection()
        refreshCardRendering()
    }

    private fun updateActiveHighlightAnnotationColor(annotation: PDFView.HighlightAnnotation, color: Int) {
        val updated = binding.pdfView.setHighlightAnnotationColor(annotation, color)
        if (!updated) {
            AppSnackbar.make(binding.root, R.string.highlight_update_failed, Snackbar.LENGTH_SHORT).show()
            return
        }

        hideActions()
        onAnnotationEdit(AnnotationEdit.Recolor(annotation.pageIndex, annotation.groupKey, color))
    }

    private fun deleteActiveHighlightAnnotation() {
        val annotation = activeHighlightAnnotation ?: return
        if (!canEditDocument()) {
            return
        }
        val removed = binding.pdfView.removeHighlightAnnotation(annotation)
        if (!removed) {
            AppSnackbar.make(binding.root, R.string.highlight_update_failed, Snackbar.LENGTH_SHORT).show()
            return
        }

        hideActions()
        onAnnotationEdit(AnnotationEdit.Delete(annotation.pageIndex, annotation.groupKey))
    }

    private fun copySelectedText(): Boolean {
        val text = selectedText()
        if (text.isBlank()) {
            return false
        }
        copyToClipboard(activity, activity.getString(R.string.selected_text), text)
        return true
    }

    private fun searchWebForSelectedText(): Boolean {
        val text = selectedText()
        if (text.isBlank()) {
            return false
        }
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text)
        try {
            activity.startActivity(searchIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}")))
            } catch (browserError: ActivityNotFoundException) {
                AppSnackbar.make(binding.root, activity.getString(R.string.no_app_to_open_link), Snackbar.LENGTH_LONG).show()
                return false
            }
        }
        return true
    }

    private fun selectedText(): String {
        return activeHighlightAnnotation?.quote ?: binding.pdfView.getSelectedText()
    }

    private fun shareSelectedQuote() {
        val text = selectedText()
        if (text.isBlank()) {
            return
        }
        val meta = binding.pdfView.documentMeta
        val bookName = meta?.title?.takeIf { it.isNotBlank() } ?: getDocumentName()
        showShareQuoteDialog(activity, text, bookName, meta?.author.orEmpty())
    }

    private fun translateSelectedText(): Boolean {
        val text = selectedText().replace(whitespaceRegex, " ").trim()
        if (text.isBlank()) {
            return false
        }
        val settings = getTranslationSettings()
        val word = dictionaryCandidate(text)
        if (word != null && onDefineWord(word) { launchTranslator(text, settings) }) {
            return true
        }
        return launchTranslator(text, settings)
    }

    private fun dictionaryCandidate(text: String): String? {
        val word = text.trim { !it.isLetterOrDigit() }
        if (word.isBlank() || word.length > 48) {
            return null
        }
        if (word.any { it.isWhitespace() } || word.none { it.isLetter() }) {
            return null
        }
        return word
    }

    private fun launchTranslator(text: String, settings: TranslationSettings): Boolean {
        if (settings.mode == Preferences.translationModeApps) {
            return launchProcessTextTranslator(text)
        }
        val url = TranslationUrlBuilder.build(settings, text)
        if (url == null) {
            AppSnackbar.make(binding.root, R.string.translation_custom_url_invalid, Snackbar.LENGTH_LONG).show()
            return false
        }
        if (settings.engine.forceBrowser) {
            try {
                activity.startActivity(browserLinkIntent(url))
                return true
            } catch (e: ActivityNotFoundException) {
            }
        }
        return try {
            activity.startActivity(linkIntent(url))
            true
        } catch (e: ActivityNotFoundException) {
            AppSnackbar.make(binding.root, activity.getString(R.string.no_app_to_open_link), Snackbar.LENGTH_LONG).show()
            false
        }
    }

    private fun launchProcessTextTranslator(text: String): Boolean {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val handlers = activity.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != activity.packageName }
        return when {
            handlers.isEmpty() -> {
                showNoTranslationAppDialog()
                false
            }
            handlers.size == 1 -> {
                val info = handlers.first().activityInfo
                try {
                    activity.startActivity(intent.setClassName(info.packageName, info.name))
                    true
                } catch (e: ActivityNotFoundException) {
                    AppSnackbar.make(binding.root, R.string.no_translate_app_found, Snackbar.LENGTH_LONG).show()
                    false
                }
            }
            else -> {
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.translate)))
                true
            }
        }
    }

    private fun showNoTranslationAppDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.no_translate_app_found)
            .setMessage(R.string.no_translate_app_message)
            .setPositiveButton(R.string.settings) { _, _ -> onOpenSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshCardRendering() {
        val card = binding.textSelectionActionCard
        card.requestLayout()
        card.invalidate()
        binding.root.invalidate()
        card.post {
            card.requestLayout()
            card.invalidate()
            binding.root.invalidate()
            updateSaveUiPosition()
        }
    }
}
