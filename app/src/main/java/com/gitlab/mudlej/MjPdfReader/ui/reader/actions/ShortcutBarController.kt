// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.actions

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import com.gitlab.mudlej.MjPdfReader.core.ui.toast
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding

class ShortcutBarController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val preferences: Preferences,
    private val actionResolver: ConfigurableActionResolver,
    private val isFullScreen: () -> Boolean,
) {

    fun configure() {
        binding.secondBarLayout.removeAllViews()
        orderedSelectedShortcutBarActions(
            selectedIds = preferences.getShortcutBarActions(),
            actionOrder = preferences.getShortcutBarActionOrder(),
        )
            .forEach { action ->
                val configuredAction = actionResolver.action(action) ?: return@forEach
                if (configuredAction.visible) {
                    binding.secondBarLayout.addView(createButton(action, configuredAction))
                }
            }
        updateVisibility()
    }

    fun updateVisibility() {
        binding.secondBarScrollView.visibility = if (
            preferences.getSecondBarEnabled() && !isFullScreen() && binding.secondBarLayout.childCount > 0
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun createButton(
        action: ConfigurableAction,
        configuredAction: ConfiguredAction,
    ): ImageView {
        val paddingHorizontal = context.dp(16)
        val paddingVertical = context.dp(8)
        return AppCompatImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(56), context.dp(40))
            setImageResource(configuredAction.iconRes)
            contentDescription = context.getString(configuredAction.titleRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            setOnClickListener { actionResolver.perform(action.id) }
            setOnLongClickListener {
                toast(context, configuredAction.titleRes, Toast.LENGTH_SHORT)
                true
            }
        }
    }

    private fun Context.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
