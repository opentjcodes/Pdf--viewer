// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.util.Collections

object ToastRecorder {

    const val CAPACITY = 20

    private data class Entry(val sequence: Long, val text: String)

    private val recorded: MutableList<Entry> = Collections.synchronizedList(mutableListOf<Entry>())
    private var sequence = 0L

    fun record(text: CharSequence) {
        if (!BuildConfig.DEBUG) {
            return
        }
        synchronized(recorded) {
            recorded.add(Entry(++sequence, text.toString()))
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

    fun mark(): Long = synchronized(recorded) { sequence }

    fun since(mark: Long): List<String> = synchronized(recorded) {
        recorded.filter { it.sequence > mark }.map { it.text }
    }

    fun snapshot(): List<String> = synchronized(recorded) { recorded.map { it.text } }
}
