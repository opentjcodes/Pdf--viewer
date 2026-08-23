// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.about

internal const val VERSION_3_VERSION_CODE = 57

internal enum class WhatsNewRelease {
    VERSION_3,
    CURRENT,
}

internal fun whatsNewReleaseForUpgrade(
    lastSeenVersionCode: Int,
    currentVersionCode: Int,
): WhatsNewRelease? {
    if (lastSeenVersionCode >= currentVersionCode) {
        return null
    }
    return if (
        lastSeenVersionCode < VERSION_3_VERSION_CODE &&
        currentVersionCode >= VERSION_3_VERSION_CODE
    ) {
        WhatsNewRelease.VERSION_3
    } else {
        WhatsNewRelease.CURRENT
    }
}
