    // Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

import com.gitlab.mudlej.MjPdfReader.BuildConfig

object StorageAccessOverride {

    @Volatile
    private var forced: Boolean? = null

    fun force(granted: Boolean) {
        if (!BuildConfig.DEBUG) {
            return
        }
        forced = granted
    }

    fun clear() {
        if (!BuildConfig.DEBUG) {
            return
        }
        forced = null
    }

    fun isForced(): Boolean = BuildConfig.DEBUG && forced != null

    fun apply(granted: Boolean): Boolean = forced ?: granted
}
