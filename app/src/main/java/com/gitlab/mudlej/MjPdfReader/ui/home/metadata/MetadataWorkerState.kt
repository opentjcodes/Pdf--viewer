package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint

internal class MetadataWorkerState {
    class Attempt(val fingerprint: SourceFingerprint)

    private enum class Phase { IDLE, PROCESSING, WAITING_FOR_ACK, EXITING }

    private var phase = Phase.IDLE
    private var active: Attempt? = null

    @Synchronized
    fun isIdle(): Boolean = phase == Phase.IDLE

    @Synchronized
    fun begin(fingerprint: SourceFingerprint): Attempt {
        check(phase == Phase.IDLE) { "Metadata worker received concurrent work" }
        return Attempt(fingerprint).also {
            active = it
            phase = Phase.PROCESSING
        }
    }

    @Synchronized
    fun complete(attempt: Attempt): Boolean {
        if (phase != Phase.PROCESSING || active !== attempt) return false
        phase = Phase.WAITING_FOR_ACK
        return true
    }

    @Synchronized
    fun expire(attempt: Attempt): Boolean {
        if (phase != Phase.PROCESSING || active !== attempt) return false
        exit()
        return true
    }

    @Synchronized
    fun acknowledge(fingerprint: SourceFingerprint, continueWork: Boolean): Boolean {
        if (phase == Phase.EXITING) return false
        check(phase == Phase.WAITING_FOR_ACK)
        check(active?.fingerprint == fingerprint)
        active = null
        phase = if (continueWork) Phase.IDLE else Phase.EXITING
        return true
    }

    @Synchronized
    fun exit() {
        phase = Phase.EXITING
        active = null
    }
}
