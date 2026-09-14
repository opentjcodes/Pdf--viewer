// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer.preview;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PreviewMemoryCache<T> {

    interface Listener<T> {
        void onReleased(PreviewKey key, T bitmap);
    }

    private final long maxBytes;
    private final PreviewBitmapAdapter<T> adapter;
    private final Listener<T> listener;
    private final LinkedHashMap<PreviewKey, T> entries = new LinkedHashMap<>(16, 0.75f, true);

    private long sizeBytes;

    PreviewMemoryCache(long maxBytes, PreviewBitmapAdapter<T> adapter, Listener<T> listener) {
        this.maxBytes = maxBytes;
        this.adapter = adapter;
        this.listener = listener;
    }

    synchronized T get(PreviewKey key) {
        return entries.get(key);
    }

    synchronized void put(PreviewKey key, T bitmap) {
        T previous = entries.put(key, bitmap);
        if (previous != null) {
            sizeBytes -= adapter.byteCount(previous);
            listener.onReleased(key, previous);
        }
        sizeBytes += adapter.byteCount(bitmap);
        trimToBudget();
    }

    synchronized void removePage(int page) {
        Iterator<Map.Entry<PreviewKey, T>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PreviewKey, T> entry = iterator.next();
            if (entry.getKey().page == page) {
                iterator.remove();
                sizeBytes -= adapter.byteCount(entry.getValue());
                listener.onReleased(entry.getKey(), entry.getValue());
            }
        }
    }

    synchronized void clear() {
        for (Map.Entry<PreviewKey, T> entry : entries.entrySet()) {
            listener.onReleased(entry.getKey(), entry.getValue());
        }
        entries.clear();
        sizeBytes = 0;
    }

    synchronized List<PreviewKey> keysSnapshot() {
        return new ArrayList<>(entries.keySet());
    }

    synchronized long sizeBytes() {
        return sizeBytes;
    }

    synchronized int count() {
        return entries.size();
    }

    long debugMaxBytes() {
        return maxBytes;
    }

    private void trimToBudget() {
        Iterator<Map.Entry<PreviewKey, T>> iterator = entries.entrySet().iterator();
        while (sizeBytes > maxBytes && iterator.hasNext()) {
            Map.Entry<PreviewKey, T> eldest = iterator.next();
            iterator.remove();
            sizeBytes -= adapter.byteCount(eldest.getValue());
            listener.onReleased(eldest.getKey(), eldest.getValue());
        }
    }
}
