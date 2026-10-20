// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.HighlightPalette
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun showHighlightColorsPreferenceDialog(
    context: Context,
    preferences: Preferences,
    onSaved: () -> Unit,
) {
    val selected = preferences.getHighlightColors()
        .mapNotNull(HighlightPalette::fromColor)
        .toSet()
    val rows = HighlightPalette.selectable
        .map { HighlightColorRow(it, enabled = it in selected) }
        .toMutableList()
    var onSelectionChanged: () -> Unit = {}
    val adapter = HighlightColorAdapter(rows) { onSelectionChanged() }
    val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        this.adapter = adapter
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.highlight_colors)
        .setView(recyclerView)
        .setPositiveButton(R.string.apply) { _, _ ->
            preferences.setHighlightColors(rows.filter { it.enabled }.map { it.color })
            onSaved()
        }
        .setNegativeButton(R.string.cancel, null)
        .setNeutralButton(R.string.reset, null)
        .create()

    dialog.setOnShowListener {
        val applyButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        fun refreshApplyEnabled() {
            applyButton.isEnabled = rows.count { it.enabled } == Preferences.highlightColorsCount
        }
        onSelectionChanged = ::refreshApplyEnabled
        refreshApplyEnabled()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            rows.forEach { it.enabled = it.color in HighlightPalette.defaultSelection }
            adapter.notifyDataSetChanged()
            refreshApplyEnabled()
        }
    }
    dialog.show()
}

private data class HighlightColorRow(
    val color: HighlightPalette,
    var enabled: Boolean,
)

private class HighlightColorAdapter(
    private val rows: MutableList<HighlightColorRow>,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<HighlightColorViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HighlightColorViewHolder {
        return HighlightColorViewHolder(parent.createColorRowView())
    }

    override fun onBindViewHolder(holder: HighlightColorViewHolder, position: Int) {
        holder.bind(rows[position], ::requestToggle)
    }

    override fun getItemCount() = rows.size

    private fun requestToggle(row: HighlightColorRow): Boolean {
        val target = !row.enabled
        val selectedCount = rows.count { it.enabled }
        if (target && selectedCount >= Preferences.highlightColorsCount) return false
        row.enabled = target
        onSelectionChanged()
        return true
    }
}

private class HighlightColorViewHolder(
    view: View,
) : RecyclerView.ViewHolder(view) {

    private val checkbox = view.findViewWithTag<MaterialCheckBox>(CHECKBOX_TAG)
    private val swatch = view.findViewWithTag<View>(SWATCH_TAG)
    private val title = view.findViewWithTag<TextView>(TITLE_TAG)

    fun bind(row: HighlightColorRow, requestToggle: (HighlightColorRow) -> Boolean) {
        checkbox.isChecked = row.enabled
        (swatch.background as GradientDrawable).setColor(row.color.colorValue)
        title.text = itemView.context.getString(row.color.nameRes)
        itemView.setOnClickListener {
            if (requestToggle(row)) {
                checkbox.isChecked = row.enabled
            } else {
                val context = itemView.context
                val message = context.getString(R.string.highlight_colors_max_toast, Preferences.highlightColorsCount)
                toast(context, message, Toast.LENGTH_SHORT)
            }
        }
    }
}

private fun ViewGroup.createColorRowView(): View {
    val context = context
    return selectionDialogRow(
        listOf(
            MaterialCheckBox(context).apply {
                tag = CHECKBOX_TAG
                isClickable = false
                isFocusable = false
            },
            View(context).apply {
                tag = SWATCH_TAG
                val outlineColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(context.dp(1), outlineColor)
                }
                layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                    marginStart = context.dp(8)
                    marginEnd = context.dp(16)
                }
            },
            TextView(context).apply {
                tag = TITLE_TAG
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
    )
}

private fun Context.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}

private const val CHECKBOX_TAG = "checkbox"
private const val SWATCH_TAG = "swatch"
private const val TITLE_TAG = "title"
