// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.share

import android.app.Activity
import android.graphics.Bitmap
import com.gitlab.mudlej.MjPdfReader.BuildConfig

object ShareQuoteDebug {

    @Volatile
    private var quoteOverride: String? = null

    @Volatile
    private var sharedBitmap: Bitmap? = null

    fun setQuoteOverride(quote: String?) {
        if (!BuildConfig.DEBUG) {
            return
        }
        quoteOverride = quote
    }

    fun quoteOverride(): String? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        return quoteOverride
    }

    fun show(activity: Activity, quote: String, bookName: String, author: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        showShareQuoteDialog(activity, quote, bookName, author)
    }

    fun sharedBitmap(): Bitmap? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        return sharedBitmap
    }

    fun onQuoteShared(bitmap: Bitmap) {
        if (!BuildConfig.DEBUG) {
            return
        }
        sharedBitmap = bitmap
    }

    fun clear() {
        if (!BuildConfig.DEBUG) {
            return
        }
        quoteOverride = null
        sharedBitmap = null
    }
}
