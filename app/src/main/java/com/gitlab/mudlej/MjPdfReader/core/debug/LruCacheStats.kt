// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.debug

data class LruCacheStats(
    val entryCount: Int,
    val sizeKb: Int,
    val maxSizeKb: Int,
)
