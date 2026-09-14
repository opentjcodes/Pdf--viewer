// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.entity

enum class ScannedPdfMetadataState {
    PENDING,
    COMPLETE,
    FAILED,
}

enum class ScannedPdfMetadataFailure {
    PASSWORD_REQUIRED,
    INVALID_PDF,
    SOURCE_MISSING,
    ACCESS_DENIED,
    IO_ERROR,
    WORKER_CRASH,
    INTERNAL_ERROR,
}

data class SourceFingerprint(
    val path: String,
    val size: Long,
    val lastModified: Long,
) {
    companion object {
        fun normalized(path: String, size: Long, lastModified: Long) = SourceFingerprint(
            path = path,
            size = size,
            lastModified = lastModified / 1_000L * 1_000L,
        )
    }
}

val ScannedPdfEntry.fingerprint: SourceFingerprint
    get() = SourceFingerprint.normalized(path, size, lastModified)
