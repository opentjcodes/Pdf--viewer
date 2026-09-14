// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.pdf

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.core.debug.CacheStatsHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PageThumbnailCache(
    private val activity: Activity,
    private val documentUri: Uri,
    private val password: String?,
) {

    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + renderDispatcher)

    private var extractor: PdfExtractor? = null

    @Volatile
    private var closed = false

    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private val inFlightMutex = Mutex()

    private val memoryCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    init {
        if (BuildConfig.DEBUG) {
            CacheStatsHook.registerThumbnailCache(this)
        }
    }

    suspend fun pageCount(): Int = withContext(renderDispatcher) {
        extractorOrNull()?.getPageCount() ?: 0
    }

    fun cached(pageIndex: Int, widthPx: Int): Bitmap? {
        return memoryCache.get(key(pageIndex, bucketFor(widthPx)))
    }

    fun bind(view: ImageView, pageIndex: Int, widthPx: Int): Job? {
        val bucket = bucketFor(widthPx)
        val cacheKey = key(pageIndex, bucket)
        view.tag = cacheKey

        val cachedBitmap = memoryCache.get(cacheKey)
        if (cachedBitmap != null) {
            view.setImageBitmap(cachedBitmap)
            return null
        }

        view.setImageBitmap(null)
        return scope.launch {
            val bitmap = load(pageIndex, bucket, cacheKey) ?: return@launch
            withContext(Dispatchers.Main) {
                if (view.tag == cacheKey) {
                    view.alpha = 0f
                    view.setImageBitmap(bitmap)
                    view.animate().alpha(1f).setDuration(CROSS_FADE_MILLIS).start()
                }
            }
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

    fun debugCacheKeys(): List<String> {
        if (!BuildConfig.DEBUG) {
            return emptyList()
        }
        return memoryCache.snapshot().keys.toList()
    }

    fun close() {
        closed = true
        scope.cancel()
        CoroutineScope(renderDispatcher).launch {
            runCatching { extractor?.close() }
            extractor = null
            memoryCache.evictAll()
            if (BuildConfig.DEBUG) {
                CacheStatsHook.recordThumbnailCacheClosed(
                    this@PageThumbnailCache,
                    debugCacheStats(),
                    debugCacheKeys(),
                )
            }
        }
    }

    private suspend fun load(pageIndex: Int, bucket: Int, cacheKey: String): Bitmap? {
        memoryCache.get(cacheKey)?.let { return it }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    render(pageIndex, bucket, cacheKey)
                } finally {
                    inFlightMutex.withLock { inFlight.remove(cacheKey) }
                }
            }.also { inFlight[cacheKey] = it }
        }
        return deferred.await()
    }

    private fun render(pageIndex: Int, bucket: Int, cacheKey: String): Bitmap? {
        val bitmap = extractorOrNull()?.renderPageThumbnail(pageIndex, bucket) ?: return null
        memoryCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun extractorOrNull(): PdfExtractor? {
        if (closed) {
            return null
        }
        extractor?.let { return it }
        return try {
            createPdfExtractor(activity, documentUri, password).also { extractor = it }
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun key(pageIndex: Int, bucket: Int) = "p$pageIndex-w$bucket"

    private fun bucketFor(widthPx: Int) = WIDTH_BUCKETS.firstOrNull { it >= widthPx } ?: WIDTH_BUCKETS.last()

    companion object {
        private const val CROSS_FADE_MILLIS = 150L
        private val WIDTH_BUCKETS = listOf(120, 180, 240, 320, 440, 600)
    }
}
