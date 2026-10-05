// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader


import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.isDigitsOnly
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PasswordDialogBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.core.io.convertDateString
import com.gitlab.mudlej.MjPdfReader.core.io.sizeInMb
import androidx.preference.PreferenceManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.shockwave.pdfium.PdfDocument
import java.io.File
import java.util.Locale

private const val TAG = "ReaderDialogs"

fun showMetaDialog(
    context: Context,
    meta: PdfDocument.Meta?,
    fileName: String?,
    fileSizeBytes: Long?,
    pageSize: String? = null,
    fonts: String? = null,
    filePath: String? = null,
) {
    if (meta == null) {
        toast(context, "Cannot read PDF's meta data!", Toast.LENGTH_SHORT)
        return
    }
    try {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_properties)
            .setView(createMetadataView(context, meta, fileName, fileSizeBytes, pageSize, fonts, filePath))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
    catch (throwable: Throwable) {
        Log.e(TAG, "showMetaDialog: Failed to show File Properties Dialog", throwable)
        toast(context, "Failed to show file properties", Toast.LENGTH_SHORT)
    }
}

private fun createMetadataView(
    context: Context,
    meta: PdfDocument.Meta,
    fileName: String?,
    fileSizeBytes: Long?,
    pageSize: String?,
    fonts: String?,
    filePath: String?,
): View {
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
    }

    addMetadataRow(content, R.string.pdf_file_name, fileName)
    addMetadataRow(content, R.string.pdf_file_path, filePath)
    addMetadataRow(content, R.string.pdf_title, meta.title)
    addMetadataRow(content, R.string.pdf_author, meta.author)
    addMetadataRow(content, R.string.pdf_pages, String.format(Locale.getDefault(), "%d", meta.totalPages))
    addMetadataRow(content, R.string.pdf_page_size, pageSize)
    addMetadataRow(content, R.string.pdf_subject, meta.subject)
    addMetadataRow(content, R.string.pdf_keywords, meta.keywords)
    addMetadataRow(content, R.string.pdf_created, convertDateString(meta.creationDate) ?: meta.creationDate)
    addMetadataRow(content, R.string.pdf_modified, convertDateString(meta.modDate) ?: meta.modDate)
    addMetadataRow(content, R.string.pdf_created_by, meta.creator)
    addMetadataRow(content, R.string.pdf_produced_by, meta.producer)
    addMetadataRow(content, R.string.pdf_fonts, fonts)
    addMetadataRow(
        content,
        R.string.pdf_file_size,
        fileSizeBytes?.let { String.format(Locale.US, "%.2f MB", it / (1024.0 * 1024.0)) } ?: "--"
    )

    return ScrollView(context).apply { addView(content) }
}

private fun addMetadataRow(parent: LinearLayout, labelRes: Int, value: String?) {
    if (value.isNullOrBlank()) return

    val context = parent.context
    parent.addView(
        TextView(context).apply {
            text = context.getString(labelRes)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(context, 10), 0, 0)
        }
    )
    parent.addView(
        TextView(context).apply {
            text = value
            setTextIsSelectable(true)
            setPadding(0, dp(context, 2), 0, 0)
        }
    )
}

private fun dp(context: Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).toInt()
}

fun showHowToExitFullscreenDialog(context: Context, pref: Preferences) {
    MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.exit_fullscreen_title))
        .setMessage(context.getString(R.string.exit_fullscreen_message))
        .setPositiveButton(context.getString(R.string.exit_fullscreen_positive)) { _, _ ->
            pref.setShowExitFullscreenTip(false)
        }
        .setNegativeButton(context.getString(R.string.ok)) {
                dialog: DialogInterface, _ -> dialog.dismiss()
        }
        .create()
        .show()
}

fun showAskForPasswordDialog(
    context: Context,
    pdf: DocumentState,
    dialogBinding: PasswordDialogBinding,
    displayFunc: (Uri?, Boolean) -> Unit
) {
    val alert = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.protected_pdf)
        .setView(dialogBinding.root)
        .setIcon(R.drawable.lock_icon)
        .setPositiveButton(R.string.ok) { _, _ ->
            pdf.password = dialogBinding.passwordInput.text.toString()
            displayFunc(pdf.uri, dialogBinding.savePassword.isChecked)
        }
        .create()

    alert.setCanceledOnTouchOutside(false)
    alert.show()
}

fun showCopyPageTextDialog(
    activity: MainActivity,
    binding: ActivityMainBinding,
    pageNumber: Int,
    pageText: String,
) {
    // create a custom view to make the text selectable
    val pageTextView = TextView(activity)
    pageTextView.setPadding(30, 20, 30, 0)
    pageTextView.setTextIsSelectable(true)
    pageTextView.textSize = 18f
    pageTextView.text = pageText

    val scrollView = ScrollView(activity)
    scrollView.addView(pageTextView)

    MaterialAlertDialogBuilder(activity)
        .setView(scrollView)
        .setTitle("${activity.getString(R.string.selectable_text)} #${pageNumber + 1}")
        .setNegativeButton(activity.getString(R.string.close)) { dialog, _ -> dialog.dismiss() }
        .setPositiveButton(activity.getString(R.string.copy_all)) { dialog, _ ->
            val copyLabel = "${activity.getString(R.string.page)} #${pageNumber} Text"
            copyToClipboard(activity, copyLabel, pageText)
            dialog.dismiss()
        }
        .show()
}

fun showSearchDialog(activity: Activity, pdf: DocumentState, onQueryConfirmed: (query: String, ignoreAccents: Boolean) -> Unit) {
    val pref = Preferences(PreferenceManager.getDefaultSharedPreferences(activity))
    val searchLayout = LayoutInflater.from(activity).inflate(R.layout.input_layout, null) as TextInputLayout
    val ignoreAccentsCheckBox = MaterialCheckBox(activity).apply {
        text = activity.getString(R.string.search_ignore_accents_title)
        isChecked = pref.getSearchIgnoreAccents()
        setOnCheckedChangeListener { _, isChecked -> pref.setSearchIgnoreAccents(isChecked) }
    }
    val checkBoxParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginStart = dp(activity, 24)
        marginEnd = dp(activity, 24)
        topMargin = dp(activity, 4)
    }
    val dialogContent = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(searchLayout)
        addView(ignoreAccentsCheckBox, checkBoxParams)
    }
    MaterialAlertDialogBuilder(activity)
        .setTitle(activity.getString(R.string.search))
        .setMessage(activity.getString(R.string.search_dialog_message))
        .setView(dialogContent)
        .setPositiveButton(activity.getText(R.string.search)) { searchDialog, _ ->
            val query = searchLayout.editText?.text ?: return@setPositiveButton
            val queryText = query.toString().trim()
            fun confirmSearch() {
                pdf.lastQuery = queryText
                onQueryConfirmed(queryText, ignoreAccentsCheckBox.isChecked)
            }
            if (queryText.isBlank() || queryText.length < PDF.MIN_SEARCH_QUERY) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.too_short_query))
                    .setMessage(activity.getString(R.string.too_short_query_message).format(queryText))
                    .setNeutralButton(activity.getString(R.string.proceed_anyway)) { _, _ ->
                        confirmSearch()
                    }
                    .setPositiveButton(activity.getText(R.string.ok)) { badQueryDialog, _ ->
                        searchDialog.dismiss()
                        badQueryDialog.dismiss()
                        showSearchDialog(activity, pdf, onQueryConfirmed)
                    }
                    .show()
            }
            else {
                confirmSearch()
            }
        }
        .setNegativeButton(activity.getText(R.string.cancel)) { dialog, _ ->
            dialog.dismiss()
        }
        .show()
}
