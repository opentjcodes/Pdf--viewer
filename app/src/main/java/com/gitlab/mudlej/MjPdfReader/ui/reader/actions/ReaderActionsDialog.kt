// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.actions

import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.content.res.AppCompatResources
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider

private const val COLOR_FALLBACK = 0
private const val GRID_COLUMNS = 4
private const val TILE_ICON_SIZE_DP = 26
private const val TILE_VERTICAL_PADDING_DP = 14
private const val TILE_LABEL_TOP_PADDING_DP = 6
private const val TILE_LABEL_HORIZONTAL_PADDING_DP = 2
private const val TILE_LABEL_TEXT_SP = 12f
private const val GRID_HORIZONTAL_PADDING_DP = 10
private const val CONTENT_TOP_PADDING_DP = 12
private const val CONTENT_BOTTOM_PADDING_DP = 18
private const val DIVIDER_HORIZONTAL_MARGIN_DP = 24
private const val DIVIDER_VERTICAL_MARGIN_DP = 10
private const val DISABLED_TILE_ALPHA = 0.38f

data class ReaderAction(
    @StringRes val titleRes: Int,
    val iconRes: Int,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

data class ReaderMenuSection(
    val actions: List<ReaderAction>,
)

data class ReaderMenuContent(
    val sections: List<ReaderMenuSection>,
)

private class TileStyle(
    val textColor: Int,
    val iconTint: ColorStateList,
    val rippleRes: Int,
    val density: Float,
)

fun showReaderActionsDialog(activity: MainActivity, content: ReaderMenuContent): AppCompatDialog {
    val density = activity.resources.displayMetrics.density
    val textColor = MaterialColors.getColor(activity, R.attr.colorOnSurface, COLOR_FALLBACK)
    val rippleValue = TypedValue().also {
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }
    val style = TileStyle(textColor, ColorStateList.valueOf(textColor), rippleValue.resourceId, density)

    var dismiss: () -> Unit = {}

    val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dpToPx(CONTENT_TOP_PADDING_DP, density), 0, dpToPx(CONTENT_BOTTOM_PADDING_DP, density))
    }

    content.sections.forEach { section ->
        val actions = section.actions.filter { it.visible }
        if (actions.isEmpty()) {
            return@forEach
        }
        if (column.childCount > 0) {
            column.addView(createSectionDivider(activity, density))
        }
        column.addView(buildTileGrid(activity, actions, GRID_COLUMNS, style) { dismiss() })
    }

    val scroll = ScrollView(activity).apply { addView(column) }
    val dialog = AppCompatDialog(activity, R.style.ChromeMaterialAlertDialog).apply {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(scroll)
    }
    dismiss = { dialog.dismiss() }
    dialog.show()
    return dialog
}

private fun createSectionDivider(activity: MainActivity, density: Float): View {
    return MaterialDivider(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dpToPx(DIVIDER_HORIZONTAL_MARGIN_DP, density)
            marginEnd = dpToPx(DIVIDER_HORIZONTAL_MARGIN_DP, density)
            topMargin = dpToPx(DIVIDER_VERTICAL_MARGIN_DP, density)
            bottomMargin = dpToPx(DIVIDER_VERTICAL_MARGIN_DP, density)
        }
    }
}

private fun buildTileGrid(
    activity: MainActivity,
    actions: List<ReaderAction>,
    columns: Int,
    style: TileStyle,
    dismiss: () -> Unit,
): LinearLayout {
    val grid = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = dpToPx(GRID_HORIZONTAL_PADDING_DP, style.density)
        setPadding(padding, 0, padding, 0)
    }
    actions.chunked(columns).forEach { rowActions ->
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        rowActions.forEach { action ->
            row.addView(buildActionTile(activity, action, style, dismiss))
        }
        repeat(columns - rowActions.size) {
            row.addView(Space(activity), LinearLayout.LayoutParams(0, 0, 1f))
        }
        grid.addView(row)
    }
    return grid
}

private fun buildActionTile(
    activity: MainActivity,
    action: ReaderAction,
    style: TileStyle,
    dismiss: () -> Unit,
): View {
    val iconView = ImageView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            dpToPx(TILE_ICON_SIZE_DP, style.density),
            dpToPx(TILE_ICON_SIZE_DP, style.density),
        )
        setImageDrawable(AppCompatResources.getDrawable(activity, action.iconRes))
        imageTintList = style.iconTint
    }
    val label = activity.getString(action.titleRes)
    val labelView = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(style.textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TILE_LABEL_TEXT_SP)
        val horizontalPadding = dpToPx(TILE_LABEL_HORIZONTAL_PADDING_DP, style.density)
        setPadding(horizontalPadding, dpToPx(TILE_LABEL_TOP_PADDING_DP, style.density), horizontalPadding, 0)
    }
    return LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val verticalPadding = dpToPx(TILE_VERTICAL_PADDING_DP, style.density)
        setPadding(0, verticalPadding, 0, verticalPadding)
        contentDescription = label
        addView(iconView)
        addView(labelView)
        if (action.enabled) {
            setBackgroundResource(style.rippleRes)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                action.onClick()
            }
        } else {
            alpha = DISABLED_TILE_ALPHA
            isClickable = false
            isFocusable = false
        }
    }
}

private fun dpToPx(dp: Int, density: Float): Int {
    return (dp * density + 0.5f).toInt()
}
