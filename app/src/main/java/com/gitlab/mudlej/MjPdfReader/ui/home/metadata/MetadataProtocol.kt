// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

object MetadataProtocol {
    const val FAILURE_NONE = ""
    const val FAILURE_SOURCE_CHANGED = "SOURCE_CHANGED"

    const val ACK_CONTINUE = 0
    const val ACK_RECYCLE = 1
    const val ACK_STOP = 2

    const val DEBUG_NONE = 0
    const val DEBUG_KILL = 1
}
