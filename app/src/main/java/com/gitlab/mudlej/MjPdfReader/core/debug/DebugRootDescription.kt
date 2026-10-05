// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import android.view.View
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.WeakHashMap

object DebugRootDescription {

    private val published = WeakHashMap<View, MutableMap<String, String>>()

    fun put(view: View, group: String, value: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val groups = published.getOrPut(view) { sortedMapOf() }
        if (groups[group] == value) {
            return
        }
        groups[group] = value
        view.contentDescription = groups.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    fun attachLayoutDirection(view: View) {
        if (!BuildConfig.DEBUG) {
            return
        }
        putLayoutDirection(view)
        view.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
            putLayoutDirection(changed)
        }
    }

    private fun putLayoutDirection(view: View) {
        val direction = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        put(view, "dir", "layout=$direction")
    }
}
