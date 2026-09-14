// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.core.debug.CacheStatsHook
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CoverCache private constructor(private val context: Context) {

    private val pdfium = PdfiumCore(context)
    private val coverDir = File(context.cacheDir, COVER_DIR_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private val inFlightMutex = Mutex()

    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    init {
        scope.launch { trimDisk() }
    }

    fun cached(hash: String, widthPx: Int): Bitmap? {
        return memoryCache.get(key(hash, bucketFor(widthPx)))
    }

    suspend fun load(hash: String, uri: Uri, widthPx: Int): Bitmap? {
        val bucket = bucketFor(widthPx)
        val cacheKey = key(hash, bucket)
        memoryCache.get(cacheKey)?.let { return it }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    loadFromDiskOrRender(hash, uri, bucket, cacheKey)
                } finally {
                    inFlightMutex.withLock { inFlight.remove(cacheKey) }
                }
            }.also { inFlight[cacheKey] = it }
        }
        return deferred.await()
    }

    fun bind(view: ImageView, hash: String, uri: Uri, widthPx: Int, scope: CoroutineScope) {
        val cacheKey = key(hash, bucketFor(widthPx))
        view.tag = cacheKey

        val cachedBitmap = cached(hash, widthPx)
        if (cachedBitmap != null) {
            view.setImageBitmap(cachedBitmap)
            return
        }

        view.setImageBitmap(null)
        scope.launch {
            val bitmap = load(hash, uri, widthPx) ?: return@launch
            if (view.tag == cacheKey) {
                view.alpha = 0f
                view.setImageBitmap(bitmap)
                view.animate().alpha(1f).setDuration(CROSS_FADE_MILLIS).start()
            }
        }
    }

    fun invalidate(hash: String) {
        for (bucket in BUCKETS) {
            memoryCache.remove(key(hash, bucket))
        }
        scope.launch {
            coverDir.listFiles { file -> file.name.startsWith("$hash-w") }?.forEach { it.delete() }
        }
    }

    fun clearAll() {
        memoryCache.evictAll()
        scope.launch {
            coverDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun debugCacheStats(): CacheStatsHook.LruStats? {
        if (!BuildConfig.DEBUG) {
            return null
        }
        return CacheStatsHook.LruStats(
            entryCount = memoryCache.snapshot().size,
            sizeKb = memoryCache.size(),
            maxSizeKb = memoryCache.maxSize(),
        )
    }

    fun clearAllBlocking() {
        memoryCache.evictAll()
        coverDir.listFiles()?.forEach { it.delete() }
    }

    private fun loadFromDiskOrRender(hash: String, uri: Uri, bucket: Int, cacheKey: String): Bitmap? {
        val file = coverFile(hash, bucket)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { decoded ->
                file.setLastModified(System.currentTimeMillis())
                memoryCache.put(cacheKey, decoded)
                return decoded
            }
        }

        val rendered = renderCover(uri, bucket) ?: return null
        memoryCache.put(cacheKey, rendered)
        runCatching {
            coverDir.mkdirs()
            val tmp = File(coverDir, file.name + ".tmp")
            FileOutputStream(tmp).use { stream ->
                rendered.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            tmp.renameTo(file)
        }
        return rendered
    }

    private fun renderCover(uri: Uri, bucket: Int): Bitmap? {
        return try {
            val resolvedUri = if (uri.scheme == "http" || uri.scheme == "https") {
                OnlineDocumentStore.fileFor(context, uri.toString())?.let { Uri.fromFile(it) } ?: return null
            } else {
                uri
            }
            val fd = context.contentResolver.openFileDescriptor(resolvedUri, "r") ?: return null
            val document = try {
                pdfium.newDocument(fd)
            } catch (throwable: Throwable) {
                runCatching { fd.close() }
                return null
            }
            try {
                pdfium.openPage(document, 0)
                val pageWidth = pdfium.getPageWidthPoint(document, 0)
                val pageHeight = pdfium.getPageHeightPoint(document, 0)
                if (pageWidth <= 0 || pageHeight <= 0) {
                    return null
                }
                val height = (bucket.toFloat() * pageHeight / pageWidth).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(bucket, height, Bitmap.Config.RGB_565)
                pdfium.renderPageBitmap(document, bitmap, 0, 0, 0, bucket, height)
                bitmap
            } finally {
                pdfium.closeDocument(document)
            }
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun trimDisk() {
        val files = coverDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_DISK_BYTES) {
                break
            }
            total -= file.length()
            file.delete()
        }
    }

    private fun coverFile(hash: String, bucket: Int) = File(coverDir, "${key(hash, bucket)}.jpg")

    private fun key(hash: String, bucket: Int) = "$hash-w$bucket"

    private fun bucketFor(widthPx: Int) = BUCKETS.firstOrNull { it >= widthPx } ?: BUCKETS.last()

    companion object {
        @Volatile
        private var INSTANCE: CoverCache? = null

        private const val COVER_DIR_NAME = "covers"
        private const val JPEG_QUALITY = 80
        private const val MAX_DISK_BYTES = 64L * 1024 * 1024
        private const val CROSS_FADE_MILLIS = 150L
        private val BUCKETS = listOf(160, 320, 640)

        fun getInstance(context: Context): CoverCache {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val created = CoverCache(context.applicationContext)
                INSTANCE = created
                return created
            }
        }
    }
}
