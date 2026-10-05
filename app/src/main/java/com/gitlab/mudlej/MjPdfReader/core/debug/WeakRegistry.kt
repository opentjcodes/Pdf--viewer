// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import java.lang.ref.WeakReference

internal class WeakRegistry<T : Any> {
    private val refs = mutableListOf<WeakReference<T>>()

    @Synchronized
    fun add(value: T) {
        refs.removeAll { it.get() == null }
        refs.add(WeakReference(value))
    }

    @Synchronized
    fun remove(value: T) {
        refs.removeAll { it.get() == null || it.get() === value }
    }

    @Synchronized
    fun snapshot(): List<T> {
        val values = mutableListOf<T>()
        val iterator = refs.iterator()
        while (iterator.hasNext()) {
            val value = iterator.next().get()
            if (value == null) {
                iterator.remove()
            } else {
                values.add(value)
            }
        }
        return values
    }
}
