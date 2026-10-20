// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.snackbar.Snackbar

fun openDocumentLink(anchor: View, url: String): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme != null && !uri.scheme.equals("file", ignoreCase = true) &&
        !url.any { it.isISOControl() } && !Uri.decode(url).any { it.isISOControl() }
    ) {
        try {
            anchor.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            return true
        } catch (_: Exception) {
        }
    }
    AppSnackbar.make(anchor, R.string.no_app_to_open_link, Snackbar.LENGTH_LONG).show()
    return false
}
