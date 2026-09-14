// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer.preview;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public final class PreviewStore<T> {

    private final PreviewBitmapAdapter<T> adapter;
    private final PreviewCodec<T> codec;
    private final GenerationSource generationSource;
    private final TagSource tagSource;
    private final TransientPageFilter transientFilter;
    private final Executor encoderExecutor;
    private final Executor decoderExecutor;
    private final long diskBudgetBytes;

    private final PreviewMemoryCache<T> memory;
    private final PreviewBitmapPool<T> pool;

    private final Object lock = new Object();
    private final Map<PreviewKey, List<DecodeWaiter>> inFlightDecodes = new HashMap<>();
    private final Map<PreviewKey, T> encodingBitmaps = new HashMap<>();
    private final Map<PreviewKey, T> deferredRelease = new HashMap<>();

    private volatile int activeBucket;

    private PreviewDiskCache diskCache;
    private File diskDir;
    private String docKey;
    private int attachToken;

    public PreviewStore(PreviewBitmapAdapter<T> adapter, PreviewCodec<T> codec,
                        GenerationSource generationSource, TagSource tagSource,
                        TransientPageFilter transientFilter,
                        Executor encoderExecutor, Executor decoderExecutor,
                        long memoryBudgetBytes, long diskBudgetBytes, int poolCapPerBucket) {
        this.adapter = adapter;
        this.codec = codec;
        this.generationSource = generationSource;
        this.tagSource = tagSource;
        this.transientFilter = transientFilter;
        this.encoderExecutor = encoderExecutor;
        this.decoderExecutor = decoderExecutor;
        this.diskBudgetBytes = diskBudgetBytes;
        this.pool = new PreviewBitmapPool<>(adapter, poolCapPerBucket);
        this.memory = new PreviewMemoryCache<>(memoryBudgetBytes, adapter, new PreviewMemoryCache.Listener<T>() {
            @Override
            public void onReleased(PreviewKey key, T bitmap) {
                synchronized (lock) {
                    if (bitmap == encodingBitmaps.get(key)) {
                        deferredRelease.put(key, bitmap);
                        return;
                    }
                }
                pool.release(key.bucketWidthPx, bitmap);
            }
        });
    }

    public void setBucket(int bucketWidthPx) {
        activeBucket = bucketWidthPx;
    }

    public PreviewBitmapPool<T> getPool() {
        return pool;
    }

    public long[] debugMemoryStats() {
        return new long[]{memory.count(), memory.sizeBytes(), memory.debugMaxBytes(), activeBucket};
    }

    public int[] debugPoolStats() {
        return pool.debugBucketStats();
    }

    public int debugPoolCap() {
        return pool.debugPerBucketCap();
    }

    public T peek(int page) {
        return memory.get(keyFor(page));
    }

    public void put(int page, T bitmap, int generation) {
        if (bitmap == null) {
            return;
        }
        PreviewKey key = keyFor(page);
        memory.put(key, bitmap);

        PreviewDiskCache dc;
        String dk;
        int token;
        synchronized (lock) {
            dc = diskCache;
            dk = docKey;
            token = attachToken;
        }
        if (dc == null) {
            return;
        }
        if (transientFilter.isTransient(page)) {
            return;
        }
        scheduleEncode(key, generation, dc, dk, token);
    }

    public void requestDecode(int page, Runnable onPublished) {
        requestDecode(page, onPublished, null);
    }

    public void requestDecode(int page, Runnable onPublished, Runnable onMiss) {
        final PreviewKey key = keyFor(page);
        if (memory.get(key) != null) {
            return;
        }

        final PreviewDiskCache dc;
        final String dk;
        final int token;
        synchronized (lock) {
            dc = diskCache;
            dk = docKey;
            token = attachToken;
            if (dc != null) {
                List<DecodeWaiter> waiters = inFlightDecodes.get(key);
                if (waiters != null) {
                    waiters.add(new DecodeWaiter(onPublished, onMiss));
                    return;
                }
                waiters = new ArrayList<>();
                waiters.add(new DecodeWaiter(onPublished, onMiss));
                inFlightDecodes.put(key, waiters);
            }
        }
        if (dc == null) {
            if (onMiss != null) {
                onMiss.run();
            }
            return;
        }
        decoderExecutor.execute(new Runnable() {
            @Override
            public void run() {
                decode(key, dc, dk, token);
            }
        });
    }

    public void invalidatePage(int page) {
        memory.removePage(page);
    }

    public boolean hasOnDisk(int page) {
        PreviewDiskCache dc;
        String dk;
        synchronized (lock) {
            dc = diskCache;
            dk = docKey;
        }
        return dc != null && dc.exists(dk, keyFor(page));
    }

    public void attachDisk(File dir, String newDocKey) {
        if (dir == null || newDocKey == null) {
            return;
        }
        final PreviewDiskCache dc;
        String dk;
        int token;
        String safeDocKey = newDocKey.replaceAll("[^A-Za-z0-9._-]", "_");
        synchronized (lock) {
            if (diskCache != null && dir.equals(diskDir) && safeDocKey.equals(docKey)) {
                return;
            }
            attachToken++;
            diskDir = dir;
            docKey = safeDocKey;
            diskCache = new PreviewDiskCache(dir);
            dc = diskCache;
            dk = docKey;
            token = attachToken;
        }
        List<PreviewKey> keys = memory.keysSnapshot();
        final long budget = diskBudgetBytes;
        encoderExecutor.execute(new Runnable() {
            @Override
            public void run() {
                dc.trim(budget);
            }
        });
        for (PreviewKey key : keys) {
            scheduleEncode(key, generationSource.generationOf(key.page), dc, dk, token);
        }
    }

    public void detachDisk() {
        synchronized (lock) {
            diskCache = null;
            diskDir = null;
            docKey = null;
            attachToken++;
        }
    }

    public void dropMemory() {
        memory.clear();
        pool.clear();
    }

    public void close() {
        synchronized (lock) {
            diskCache = null;
            diskDir = null;
            docKey = null;
            attachToken++;
        }
        memory.clear();
        pool.clear();
    }

    private PreviewKey keyFor(int page) {
        return new PreviewKey(page, activeBucket, tagSource.tagOf(page));
    }

    private boolean isKeyCurrent(PreviewKey key) {
        return key.bucketWidthPx == activeBucket && key.tag == tagSource.tagOf(key.page);
    }

    private void scheduleEncode(final PreviewKey key, final int baselineGeneration,
                                final PreviewDiskCache dc, final String dk, final int token) {
        encoderExecutor.execute(new Runnable() {
            @Override
            public void run() {
                encode(key, baselineGeneration, dc, dk, token);
            }
        });
    }

    private void encode(PreviewKey key, int baselineGeneration, PreviewDiskCache dc, String dk, int token) {
        synchronized (lock) {
            if (token != attachToken || diskCache != dc) {
                return;
            }
        }
        if (generationSource.generationOf(key.page) != baselineGeneration) {
            return;
        }
        if (transientFilter.isTransient(key.page)) {
            return;
        }
        T bitmap = memory.get(key);
        if (bitmap == null) {
            return;
        }
        synchronized (lock) {
            if (token != attachToken || diskCache != dc) {
                return;
            }
            encodingBitmaps.put(key, bitmap);
        }
        if (memory.get(key) != bitmap) {
            releaseEncodingPin(key);
            return;
        }
        byte[] data;
        try {
            data = codec.encode(bitmap);
        } catch (RuntimeException e) {
            data = null;
        } finally {
            releaseEncodingPin(key);
        }
        if (data == null) {
            return;
        }
        synchronized (lock) {
            if (token != attachToken || diskCache != dc) {
                return;
            }
        }
        dc.write(dk, key, data);
    }

    private void releaseEncodingPin(PreviewKey key) {
        T deferred;
        synchronized (lock) {
            encodingBitmaps.remove(key);
            deferred = deferredRelease.remove(key);
        }
        if (deferred != null) {
            pool.release(key.bucketWidthPx, deferred);
        }
    }

    private void decode(PreviewKey key, PreviewDiskCache dc, String dk, int token) {
        byte[] data = dc.read(dk, key);
        T bitmap = null;
        if (data != null) {
            try {
                bitmap = codec.decode(data);
            } catch (RuntimeException e) {
                bitmap = null;
            }
            if (bitmap == null) {
                dc.delete(dk, key);
            }
        }

        List<DecodeWaiter> waiters;
        boolean publish;
        synchronized (lock) {
            waiters = inFlightDecodes.remove(key);
            publish = bitmap != null && token == attachToken && diskCache == dc && isKeyCurrent(key);
        }
        if (publish) {
            memory.put(key, bitmap);
            runPublished(waiters);
        } else {
            if (bitmap != null) {
                adapter.recycle(bitmap);
            }
            runMissed(waiters);
        }
    }

    private static void runPublished(List<DecodeWaiter> waiters) {
        if (waiters == null) {
            return;
        }
        for (DecodeWaiter waiter : waiters) {
            if (waiter.onPublished != null) {
                waiter.onPublished.run();
            }
        }
    }

    private static void runMissed(List<DecodeWaiter> waiters) {
        if (waiters == null) {
            return;
        }
        for (DecodeWaiter waiter : waiters) {
            if (waiter.onMiss != null) {
                waiter.onMiss.run();
            }
        }
    }

    private static final class DecodeWaiter {

        final Runnable onPublished;
        final Runnable onMiss;

        DecodeWaiter(Runnable onPublished, Runnable onMiss) {
            this.onPublished = onPublished;
            this.onMiss = onMiss;
        }
    }
}
