// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.share

import android.app.Activity
import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.ShareQuoteDebug
import com.gitlab.mudlej.MjPdfReader.core.io.imageShareIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.databinding.DialogShareQuoteBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.Date

fun showShareQuoteDialog(
    activity: Activity,
    quote: String,
    bookName: String,
    author: String,
) {
    val binding = DialogShareQuoteBinding.inflate(activity.layoutInflater)
    binding.bookNameInput.setText(bookName)
    binding.authorInput.setText(author)

    val sourceQuote = (if (BuildConfig.DEBUG) ShareQuoteDebug.quoteOverride() else null) ?: quote
    val trimmedQuote = shortenQuote(sourceQuote.trim())
    binding.shortenedNotice.visibility = if (trimmedQuote != null) View.VISIBLE else View.GONE
    val cardQuote = trimmedQuote ?: sourceQuote

    val themeChips = listOf(
        binding.themeLight to QuoteCardTheme.LIGHT,
        binding.themeDark to QuoteCardTheme.DARK,
        binding.themeSepia to QuoteCardTheme.SEPIA,
        binding.themeRose to QuoteCardTheme.ROSE,
        binding.themeLavender to QuoteCardTheme.LAVENDER,
        binding.themeMint to QuoteCardTheme.MINT,
    )

    fun currentOptions(): QuoteCardOptions {
        val theme = themeChips.firstOrNull { it.first.isChecked }?.second ?: QuoteCardTheme.LIGHT
        return QuoteCardOptions(
            quote = cardQuote,
            bookName = binding.bookNameInput.text?.toString().orEmpty(),
            author = binding.authorInput.text?.toString().orEmpty(),
            showMadeBy = binding.madeBySwitch.isChecked,
            theme = theme,
            reflow = binding.reflowSwitch.isChecked,
        )
    }

    fun refreshPreview() {
        binding.sharePreview.setImageBitmap(QuoteImageRenderer.render(currentOptions()))
    }

    binding.customizeHeader.setOnClickListener {
        val expanded = binding.customizeSection.visibility == View.VISIBLE
        binding.customizeSection.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.customizeChevron.setImageResource(
            if (expanded) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_up
        )
    }
    binding.bookNameInput.onTextChanged { refreshPreview() }
    binding.authorInput.onTextChanged { refreshPreview() }
    themeChips.forEach { (chip, _) ->
        chip.setOnClickListener {
            themeChips.forEach { (other, _) -> other.isChecked = other === chip }
            refreshPreview()
        }
    }
    binding.madeBySwitch.setOnCheckedChangeListener { _, _ -> refreshPreview() }
    binding.reflowSwitch.setOnCheckedChangeListener { _, _ -> refreshPreview() }
    refreshPreview()

    MaterialAlertDialogBuilder(activity, R.style.CompactMaterialAlertDialog)
        .setTitle(R.string.share_quote)
        .setView(binding.root)
        .setPositiveButton(R.string.share) { _, _ ->
            shareQuoteBitmap(activity, QuoteImageRenderer.render(currentOptions()))
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

private fun shortenQuote(quote: String): String? {
    if (quote.length <= QuoteImageRenderer.MAX_QUOTE_CHARS) {
        return null
    }
    val cut = quote.take(QuoteImageRenderer.MAX_QUOTE_CHARS)
    val boundary = cut.indexOfLast { it.isWhitespace() }
    val kept = if (boundary > QuoteImageRenderer.MAX_QUOTE_CHARS / 2) cut.substring(0, boundary) else cut
    return kept.trimEnd() + "…"
}

private fun shareQuoteBitmap(activity: Activity, bitmap: Bitmap) {
    if (BuildConfig.DEBUG) {
        ShareQuoteDebug.onQuoteShared(bitmap)
    }
    runCatching {
        val shareDir = File(activity.cacheDir, "share").apply { mkdirs() }
        val expiredBefore = System.currentTimeMillis() - CACHE_MAX_AGE_MILLIS
        shareDir.listFiles()?.forEach { file ->
            if (file.lastModified() < expiredBefore) {
                file.delete()
            }
        }
        val fileName = "MJ_quote_${DateFormat.format("yyyy_MM_dd-HH_mm_ss", Date())}.png"
        val file = File(shareDir, fileName)
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        val shareUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(imageShareIntent(activity.getString(R.string.share_quote), fileName, shareUri))
    }.onFailure {
        toast(activity, R.string.share_quote_failed, Toast.LENGTH_SHORT)
    }
}

private fun EditText.onTextChanged(onChanged: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged()
    })
}

private const val CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
