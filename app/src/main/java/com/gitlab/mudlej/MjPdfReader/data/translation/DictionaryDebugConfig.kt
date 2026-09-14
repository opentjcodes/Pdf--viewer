// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.translation

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import kotlinx.coroutines.delay

object DictionaryDebugConfig {

    @Volatile
    var urlOverride: String? = null

    @Volatile
    var sha256Override: String? = null

    @Volatile
    var chunkDelayMillis = 0L

    fun clear() {
        urlOverride = null
        sha256Override = null
        chunkDelayMillis = 0L
    }

    fun resolveUrl(configured: String): String {
        if (!BuildConfig.DEBUG) {
            return configured
        }
        return urlOverride ?: configured
    }

    fun resolveSha256(configured: String): String {
        if (!BuildConfig.DEBUG) {
            return configured
        }
        return sha256Override ?: configured
    }

    suspend fun throttleChunk() {
        if (!BuildConfig.DEBUG) {
            return
        }
        val millis = chunkDelayMillis
        if (millis > 0) {
            delay(millis)
        }
    }
}
