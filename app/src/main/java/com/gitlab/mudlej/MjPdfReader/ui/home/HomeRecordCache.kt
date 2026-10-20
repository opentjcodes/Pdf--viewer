// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.data.entity.fingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.LibraryIndex
import com.gitlab.mudlej.MjPdfReader.ui.home.scan.ScanPhase
import java.net.URI

internal class HomeRecordCache {
    data class Identity(val hash: String, val uri: String, val sourceUri: String?)
    data class Snapshot(val epoch: Long, val availability: Map<String, Availability>)

    private var identities: Set<Identity> = emptySet()
    private var availability: Map<String, Availability> = emptyMap()
    private var lastSources: Map<String, SourceFingerprint>? = null
    private var lastCompletedSources: Map<String, SourceFingerprint>? = null
    @Volatile var epoch: Long = 0L
        private set

    fun updateRecords(records: List<Identity>) {
        val next = records.toSet()
        if (identities == next) return

        val previousSources = identities.map { it.uri to it.sourceUri }.toSet()
        val reusableUris = next.mapTo(HashSet()) { it.uri }
        for (record in next) {
            if ((record.uri to record.sourceUri) !in previousSources) {
                reusableUris.remove(record.uri)
            }
        }

        availability = availability.filterKeys { it in reusableUris }
        identities = next
        epoch++
    }

    fun invalidate() {
        epoch++
        availability = emptyMap()
    }

    fun onLibraryIndex(index: LibraryIndex): Boolean {
        if (!index.loaded) return false

        val currentSources = index.entries.associate { it.path to it.fingerprint }
        val previousSources = lastSources ?: currentSources
        val previousCompletedSources = lastCompletedSources ?: previousSources
        val scanCompleted = !index.active && index.phase != ScanPhase.PAUSED
        val refreshContentUris = scanCompleted && currentSources != previousCompletedSources

        lastSources = currentSources
        lastCompletedSources = if (scanCompleted) currentSources else previousCompletedSources
        if (currentSources == previousSources && !refreshContentUris) return false

        val changedUris = HashSet<String>()
        for (record in identities) {
            val uri = runCatching { URI(record.uri) }.getOrNull() ?: continue
            val changed = when (uri.scheme) {
                "file" -> previousSources[uri.path] != currentSources[uri.path]
                "content" -> refreshContentUris
                else -> false
            }
            if (changed) changedUris.add(record.uri)
        }

        if (changedUris.isEmpty()) return false
        availability = availability - changedUris
        epoch++
        return true
    }

    suspend fun resolve(probe: suspend (String) -> Availability): Snapshot? {
        val requestedEpoch = epoch
        val uris = identities.map { it.uri }.distinct()
        val resolved = availability.toMutableMap()
        for (uri in uris) {
            if (uri !in resolved) resolved[uri] = probe(uri)
            if (epoch != requestedEpoch) return null
        }
        availability = resolved.toMap()
        return Snapshot(requestedEpoch, availability)
    }
}
