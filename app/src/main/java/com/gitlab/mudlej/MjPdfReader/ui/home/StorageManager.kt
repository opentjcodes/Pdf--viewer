// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager as AndroidStorageManager
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.core.debug.ScanDebugSignals
import java.io.File
import java.io.IOException

class StorageManager {

    fun readAllFiles(): Sequence<File> = readAllFiles(listOf(File(ROOT_DIR)))

    fun readAllFiles(
        roots: List<File>,
        excludedRoots: List<File> = emptyList(),
        onError: (Exception) -> Unit = { throw it },
    ): Sequence<File> = sequence {
        val pending = ArrayDeque<File>()
        val excluded = excludedRoots.map { it.absoluteFile.normalize().path }
        roots.asReversed().forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val file = pending.removeLast()
            val path = file.absoluteFile.normalize().path
            if (ScanScope.contains(excluded, path)) continue
            yield(file)
            val children = try {
                if (!mayEnter(file)) continue
                ScanDebugSignals.noteFilesystemDirectory()
                file.listFiles() ?: throw IOException("Cannot list scan directory: $path")
            } catch (error: IOException) {
                onError(error)
                continue
            } catch (error: SecurityException) {
                onError(error)
                continue
            }
            for (index in children.indices.reversed()) pending.addLast(children[index])
        }
    }

    private fun mayEnter(file: File): Boolean =
        file.isDirectory &&
            !file.isHidden &&
            file != ANDROID_DIR &&
            file != DATA_DIR

    fun volumeRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()
        val primary = Environment.getExternalStorageDirectory()
        roots[primary.absolutePath] = primary
        volumeDirectories(context).forEach { directory ->
            roots.putIfAbsent(directory.absolutePath, directory)
        }
        return roots.values.filter { it.isDirectory && it.canRead() }
    }

    private fun volumeDirectories(context: Context): List<File> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ContextCompat.getExternalFilesDirs(context, null).mapNotNull { filesDir ->
                filesDir?.parentFile?.parentFile?.parentFile?.parentFile
            }
        }
        return context.getSystemService(AndroidStorageManager::class.java)
            ?.storageVolumes
            ?.mapNotNull { volume ->
                val directory = volume.directory ?: return@mapNotNull null
                if (volume.state == Environment.MEDIA_MOUNTED
                    || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
                ) directory else null
            }.orEmpty()
    }

    companion object {

        val ROOT_DIR = Environment.getExternalStorageDirectory().absolutePath
        private val ANDROID_DIR = File("$ROOT_DIR/Android")
        private val DATA_DIR = File("$ROOT_DIR/data")

        const val PDF_EXTENSION = "pdf"
    }
}
