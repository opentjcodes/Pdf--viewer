// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.scan

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.entity.SourceFingerprint
import com.gitlab.mudlej.MjPdfReader.ui.home.ScanScope
import java.io.File
import kotlinx.coroutines.CancellationException

sealed interface ScanPathState {
    data class Present(val entry: ScannedPdfEntry) : ScanPathState
    data object Missing : ScanPathState
    data object Unavailable : ScanPathState
}

class ScanPathProbe(private val context: Context) {
    fun read(path: String): ScanPathState {
        ScanDebugSignals.notePathProbe()
        val file = File(path)
        if (!file.isAbsolute) return ScanPathState.Unavailable
        return try {
            try {
                val stat = Os.stat(path)
                if (!OsConstants.S_ISREG(stat.st_mode) || !Os.access(path, OsConstants.R_OK)) {
                    return ScanPathState.Unavailable
                }

                val fingerprint = SourceFingerprint.normalized(path, stat.st_size, stat.st_mtime * 1_000L)
                ScanPathState.Present(
                    ScannedPdfEntry(fingerprint.path, fingerprint.size, fingerprint.lastModified, null)
                )
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.ENOENT, OsConstants.ENOTDIR -> verifyMissing(file)
                    else -> ScanPathState.Unavailable
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ScanPathState.Unavailable
        }
    }

    private fun verifyMissing(file: File): ScanPathState {
        var child = file.canonicalFile
        val root = storageRoot(child) ?: return ScanPathState.Unavailable

        while (true) {
            val parent = child.parentFile ?: return ScanPathState.Unavailable
            if (!ScanScope.contains(listOf(root.path), parent.path)) return ScanPathState.Unavailable

            val stat = try {
                Os.stat(parent.path)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENOENT && error.errno != OsConstants.ENOTDIR) throw error
                child = parent
                continue
            }
            if (!OsConstants.S_ISDIR(stat.st_mode) || !Os.access(parent.path, OsConstants.R_OK or OsConstants.X_OK)) {
                return ScanPathState.Unavailable
            }

            try {
                Os.lstat(child.path)
                return ScanPathState.Unavailable
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENOENT) return ScanPathState.Unavailable
            }

            if (storageRoot(file.canonicalFile) != root) return ScanPathState.Unavailable
            return ScanPathState.Missing
        }
    }

    private fun storageRoot(file: File): File? {
        val privateRoot = File(context.applicationInfo.dataDir).canonicalFile
        if (ScanScope.contains(listOf(privateRoot.path), file.path)) return privateRoot

        val state = Environment.getExternalStorageState(file)
        if (!PermissionManager.hasFullAccess(context)) return null
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val volume = context.getSystemService(StorageManager::class.java)?.getStorageVolume(file) ?: return null
            val root = volume.directory?.canonicalFile ?: return null
            if (ScanScope.contains(listOf("${root.path}/Android/data", "${root.path}/Android/obb"), file.path)) return null
            return root
        }

        val primary = Environment.getExternalStorageDirectory().canonicalFile
        if (ScanScope.contains(listOf(primary.path), file.path)) return primary

        for (filesDirectory in ContextCompat.getExternalFilesDirs(context, null)) {
            val appDirectory = filesDirectory?.parentFile ?: continue
            val androidDirectory = appDirectory.parentFile?.parentFile ?: continue
            val root = androidDirectory.parentFile?.canonicalFile ?: continue
            if (ScanScope.contains(listOf(root.path), file.path)) return root
        }
        return null
    }
}
