// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.Collections

object ToastRecorder {

    const val CAPACITY = 20

    private val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    fun record(text: CharSequence) {
        if (!BuildConfig.DEBUG) {
            return
        }
        synchronized(recorded) {
            recorded.add(text.toString())
            while (recorded.size > CAPACITY) {
                recorded.removeAt(0)
            }
        }
    }

    fun clear() {
        if (!BuildConfig.DEBUG) {
            return
        }
        synchronized(recorded) {
            recorded.clear()
        }
    }

    fun snapshot(): List<String> = synchronized(recorded) { recorded.toList() }
}
