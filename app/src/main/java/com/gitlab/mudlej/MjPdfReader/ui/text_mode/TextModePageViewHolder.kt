// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.text_mode

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.Selection
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.MainThread
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.TextModePageItemBinding
import com.gitlab.mudlej.MjPdfReader.core.io.plainTextShareIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.google.android.material.snackbar.Snackbar

class TextModePageViewHolder(
    private val binding: TextModePageItemBinding,
    private val onRetry: (Int) -> Unit,
    private val viewportHeight: () -> Int,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(state: TextModePageState, settings: TextModeSettings) {
        val context = binding.root.context
        val colors = settings.theme.colors(binding.root)
        val horizontalPadding = dp(settings.horizontalMargin)

        binding.root.setBackgroundColor(colors.background)
        applyPlaceholderHeight(state)
        binding.pageContainer.setPadding(horizontalPadding, dp(18), horizontalPadding, dp(18))
        applyReadableLineLength(settings)
        binding.pageLabel.text = context.getString(R.string.text_mode_page_label, state.pageIndex + 1)
        binding.pageLabel.setTextColor(colors.label)
        binding.pageMessage.setTextColor(colors.label)
        binding.pageText.setTextColor(colors.text)
        binding.pageText.textSize = settings.fontSize
        binding.pageText.typeface = settings.fontFamily.typeface()
        binding.pageText.setLineSpacing(0f, settings.lineSpacing)
        binding.pageText.customSelectionActionModeCallback = selectionActionModeCallback()

        binding.pageProgressBar.visibility = View.GONE
        binding.pageMessage.visibility = View.GONE
        binding.pageText.visibility = View.GONE
        binding.pageMessage.setOnClickListener(null)

        when (state) {
            is TextModePageState.NotLoaded,
            is TextModePageState.Loading -> {
                binding.pageProgressBar.visibility = View.VISIBLE
                binding.pageMessage.visibility = View.VISIBLE
                binding.pageMessage.text = context.getString(R.string.text_mode_loading_page)
            }
            is TextModePageState.Ready -> {
                binding.pageText.visibility = View.VISIBLE
                binding.pageText.text = state.text
            }
            is TextModePageState.Empty -> {
                binding.pageMessage.visibility = View.VISIBLE
                binding.pageMessage.text = context.getString(R.string.text_mode_no_text)
            }
            is TextModePageState.Error -> {
                binding.pageMessage.visibility = View.VISIBLE
                binding.pageMessage.text = context.getString(
                    R.string.text_mode_failed_page,
                ) + " " + context.getString(R.string.text_mode_retry)
                binding.pageMessage.setOnClickListener { onRetry(state.pageIndex) }
            }
            is TextModePageState.TooLarge -> {
                binding.pageMessage.visibility = View.VISIBLE
                binding.pageMessage.text = context.getString(R.string.page_text_too_large)
                binding.pageMessage.setOnClickListener(null)
                binding.pageMessage.isClickable = false
            }
        }
    }

    @MainThread
    fun debugSnapshot(position: Int): TextModePageSnapshot? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        return TextModePageSnapshot(
            position = position,
            pageText = binding.pageText.text,
            pageTextVisible = binding.pageText.visibility == View.VISIBLE,
            textSizePx = binding.pageText.textSize,
            lineSpacingMultiplier = binding.pageText.lineSpacingMultiplier,
            typeface = binding.pageText.typeface,
            containerPaddingLeft = binding.pageContainer.paddingLeft,
            containerPaddingTop = binding.pageContainer.paddingTop,
            containerPaddingRight = binding.pageContainer.paddingRight,
            containerPaddingBottom = binding.pageContainer.paddingBottom,
            containerMeasuredWidth = binding.pageContainer.measuredWidth,
            paragraphDirection = binding.pageText.layout?.getParagraphDirection(0) ?: 0,
        )
    }

    private fun selectionActionModeCallback(): ActionMode.Callback {
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, SHARE_SELECTION_ID, 10, R.string.share)
                menu.add(0, SEARCH_WEB_SELECTION_ID, 11, R.string.search_web)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val selectedText = selectedText().takeIf { it.isNotBlank() } ?: return false
                when (item.itemId) {
                    SHARE_SELECTION_ID -> binding.root.context.startActivity(
                        plainTextShareIntent(binding.root.context.getString(R.string.share), selectedText)
                    )
                    SEARCH_WEB_SELECTION_ID -> searchWeb(selectedText)
                    else -> return false
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
    }

    private fun selectedText(): String {
        val text = binding.pageText.text ?: return ""
        val start = Selection.getSelectionStart(text)
        val end = Selection.getSelectionEnd(text)
        if (start == -1 || end == -1 || start == end) return ""

        return text.substring(start.coerceAtMost(end), start.coerceAtLeast(end))
    }

    private fun searchWeb(text: String) {
        val context = binding.root.context
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text)
        try {
            context.startActivity(searchIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}")))
            } catch (browserError: ActivityNotFoundException) {
                AppSnackbar.make(binding.root, context.getString(R.string.no_app_to_open_link), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun applyPlaceholderHeight(state: TextModePageState) {
        val isPlaceholder = state is TextModePageState.NotLoaded || state is TextModePageState.Loading
        val minHeight = if (isPlaceholder) {
            val height = (viewportHeight() * PLACEHOLDER_VIEWPORT_FRACTION).toInt()
            if (height > 0) height else dp(PLACEHOLDER_FALLBACK_HEIGHT_DP)
        } else {
            0
        }
        if (binding.root.minimumHeight != minHeight) {
            binding.root.minimumHeight = minHeight
        }
    }

    private fun applyReadableLineLength(settings: TextModeSettings) {
        val params = binding.pageContainer.layoutParams as ConstraintLayout.LayoutParams
        val maxWidth = if (settings.readableLineLength) {
            binding.root.resources.getDimensionPixelSize(R.dimen.text_mode_content_max_width)
        } else {
            0
        }
        if (params.matchConstraintMaxWidth != maxWidth) {
            params.matchConstraintMaxWidth = maxWidth
            binding.pageContainer.layoutParams = params
        }
    }

    private fun dp(value: Int): Int {
        return (value * binding.root.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val SHARE_SELECTION_ID = 1001
        const val SEARCH_WEB_SELECTION_ID = 1002
        const val PLACEHOLDER_VIEWPORT_FRACTION = 0.65f
        const val PLACEHOLDER_FALLBACK_HEIGHT_DP = 420
    }
}
