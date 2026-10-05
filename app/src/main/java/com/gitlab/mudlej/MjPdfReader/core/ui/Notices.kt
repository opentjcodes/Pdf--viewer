// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.core.debug.ToastRecorder

fun toast(context: Context, @StringRes resId: Int, duration: Int) {
    makeToast(context, context.getString(resId), duration).show()
}

fun toast(context: Context, text: CharSequence, duration: Int) {
    makeToast(context, text, duration).show()
}

fun makeToast(context: Context, @StringRes resId: Int, duration: Int): Toast =
    makeToast(context, context.getString(resId), duration)

fun makeToast(context: Context, text: CharSequence, duration: Int): Toast {
    ToastRecorder.record(text)
    if (BuildConfig.DEBUG) {
        Log.i("MjPdfToast", text.toString())
    }
    return Toast.makeText(context, text, duration)
}
