// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.gotopage

import android.content.DialogInterface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.DialogGoToPageBinding
import com.gitlab.mudlej.MjPdfReader.pdf.PageThumbnailCache
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun showGoToPageDialog(
    activity: ComponentActivity,
    pageIndex: Int,
    pdfLength: Int,
    documentUri: Uri?,
    password: String?,
    goToPageFunc: (Int) -> Unit,
    showAllPages: (() -> Unit)? = null,
) {
    val binding = DialogGoToPageBinding.inflate(LayoutInflater.from(activity))
    binding.inputLayout.hint = "Current page ${pageIndex + 1}/$pdfLength"

    val cache = if (documentUri != null && pdfLength > 0) {
        PageThumbnailCache(activity, documentUri, password)
    } else {
        null
    }

    val builder = MaterialAlertDialogBuilder(activity)
        .setTitle(activity.getString(R.string.go_to_page))
        .setView(binding.root)
        .setPositiveButton(activity.getString(R.string.go_to), null)
        .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }

    if (cache != null && showAllPages != null) {
        builder.setNeutralButton(activity.getString(R.string.all_pages)) { dialog, _ ->
            dialog.dismiss()
            showAllPages()
        }
    }

    val dialog = builder.create()
    val destroyObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            dialog.dismiss()
        }
    }
    dialog.setOnDismissListener {
        cache?.close()
        activity.lifecycle.removeObserver(destroyObserver)
    }
    activity.lifecycle.addObserver(destroyObserver)

    fun enteredPageNumber(): Int? = binding.editText.text?.toString()?.trim()?.toIntOrNull()
    dialog.setOnShowListener {
        val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positiveButton.isEnabled = enteredPageNumber() != null
        binding.editText.doAfterTextChanged {
            positiveButton.isEnabled = enteredPageNumber() != null
        }
        positiveButton.setOnClickListener {
            val pageNumber = enteredPageNumber() ?: return@setOnClickListener
            goToPageFunc(pageNumber - 1)
            dialog.dismiss()
        }
    }

    if (cache != null) {
        val strip = binding.thumbnailStrip
        val pref = Preferences(PreferenceManager.getDefaultSharedPreferences(activity))
        val pdfDarkTheme = PdfThemeController.effectivePdfDarkTheme(activity, pref)
        strip.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        strip.adapter = PageThumbnailStripAdapter(pdfLength, pageIndex, cache, pdfDarkTheme) { chosenIndex ->
            goToPageFunc(chosenIndex)
            dialog.dismiss()
        }
        strip.post { centerStripOn(strip, pageIndex, pdfLength) }
        binding.editText.doAfterTextChanged { text ->
            text?.toString()?.toIntOrNull()
                ?.takeIf { it in 1..pdfLength }
                ?.let { centerStripOn(strip, it - 1, pdfLength) }
        }
    } else {
        binding.thumbnailStrip.visibility = View.GONE
    }

    dialog.show()
}

private fun centerStripOn(strip: RecyclerView, pageIndex: Int, pdfLength: Int) {
    val layoutManager = strip.layoutManager as? LinearLayoutManager ?: return
    val cellWidthPx = (STRIP_CELL_TOTAL_WIDTH_DP * strip.resources.displayMetrics.density).toInt()
    val offset = strip.width / 2 - strip.paddingStart - cellWidthPx / 2
    layoutManager.scrollToPositionWithOffset(pageIndex.coerceIn(0, pdfLength - 1), offset)
}

private const val STRIP_CELL_TOTAL_WIDTH_DP = 98
