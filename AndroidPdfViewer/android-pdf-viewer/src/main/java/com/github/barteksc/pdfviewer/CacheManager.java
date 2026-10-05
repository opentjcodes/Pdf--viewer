/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import static com.github.barteksc.pdfviewer.util.Constants.Cache.CACHE_SIZE;
import static com.github.barteksc.pdfviewer.util.Constants.Cache.SNAPSHOTS_CACHE_SIZE;

import android.graphics.Bitmap;
import android.graphics.RectF;

import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.model.PagePart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

class CacheManager {

    private final PriorityQueue<PagePart> passiveCache;

    private final PriorityQueue<PagePart> activeCache;

    private final Object passiveActiveLock = new Object();

    private final PagePartComparator orderComparator = new PagePartComparator();

    private volatile boolean scaling = false;

    private boolean debugPartPeakTracking;
    private long debugPartPeakObservationId;
    private int debugPartPeakActive;
    private int debugPartPeakPassive;
    private int debugPartPeakActiveTiles;
    private int debugPartPeakPassiveTiles;
    private int debugPartPeakTotal;
    private int debugPartPeakScalingInsertions;

    public CacheManager() {
        activeCache = new PriorityQueue<>(CACHE_SIZE, orderComparator);
        passiveCache = new PriorityQueue<>(CACHE_SIZE, orderComparator);
    }

    public void cachePart(PagePart part) {
        synchronized (passiveActiveLock) {
            removeAndRecycleEqual(activeCache, part);
            removeAndRecycleEqual(passiveCache, part);

            if (part.isSnapshot()) {
                pruneSnapshots(part);
            }

            // If cache too big, remove and recycle
            makeAFreeSpace();

            // Then add part
            activeCache.offer(part);
            recordDebugPartPeak();
        }
    }

    /**
     * Keeps at most one previous snapshot for the incoming part's page and
     * {@link com.github.barteksc.pdfviewer.util.Constants.Cache#SNAPSHOTS_CACHE_SIZE}
     * snapshots overall (leaving room for the incoming one), newest first.
     */
    private void pruneSnapshots(PagePart incoming) {
        List<PagePart> snapshots = new ArrayList<>();
        collectSnapshots(activeCache, snapshots);
        collectSnapshots(passiveCache, snapshots);
        Collections.sort(snapshots, orderComparator);

        int samePageKept = 0;
        int totalKept = 0;
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            PagePart snapshot = snapshots.get(i);
            boolean samePage = snapshot.getPage() == incoming.getPage();
            boolean keep = totalKept < SNAPSHOTS_CACHE_SIZE - 1 && (!samePage || samePageKept < 1);
            if (!keep) {
                removeSnapshot(snapshot);
                continue;
            }
            totalKept++;
            if (samePage) {
                samePageKept++;
            }
        }
    }

    private void removeSnapshot(PagePart snapshot) {
        if (removeByIdentity(activeCache, snapshot) || removeByIdentity(passiveCache, snapshot)) {
            recycleBitmap(snapshot);
        }
    }

    private static boolean removeByIdentity(PriorityQueue<PagePart> queue, PagePart part) {
        Iterator<PagePart> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() == part) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static void collectSnapshots(Collection<PagePart> parts, List<PagePart> into) {
        for (PagePart part : parts) {
            if (part.isSnapshot()) {
                into.add(part);
            }
        }
    }

    public void invalidatePageParts(int page) {
        synchronized (passiveActiveLock) {
            markPageStale(passiveCache, page);
            markPageStale(activeCache, page);
        }
    }

    private static void markPageStale(Collection<PagePart> parts, int page) {
        for (PagePart part : parts) {
            if (part.getPage() == page) {
                part.markStale();
            }
        }
    }

    private static void removeAndRecycleEqual(PriorityQueue<PagePart> queue, PagePart newPart) {
        PagePart existing = find(queue, newPart);
        if (existing != null) {
            queue.remove(existing);
            recycleBitmap(existing);
        }
    }

    private static void recycleBitmap(@Nullable PagePart part) {
        if (part == null) {
            return;
        }
        Bitmap bitmap = part.getRenderedBitmap();
        if (bitmap == null) {
            return;
        }
        synchronized (bitmap) {
            bitmap.recycle();
        }
    }

    public void makeANewSet() {
        synchronized (passiveActiveLock) {
            passiveCache.addAll(activeCache);
            activeCache.clear();
        }
    }

    public void setScaling(boolean scaling) {
        this.scaling = scaling;
    }

    long[] debugPartStats() {
        synchronized (passiveActiveLock) {
            return new long[]{
                    activeCache.size(),
                    passiveCache.size(),
                    debugPartBytes(activeCache),
                    debugPartBytes(passiveCache),
                    scaling ? 1 : 0
            };
        }
    }

    long debugResetPartPeak() {
        synchronized (passiveActiveLock) {
            debugPartPeakObservationId++;
            debugPartPeakActive = 0;
            debugPartPeakPassive = 0;
            debugPartPeakActiveTiles = 0;
            debugPartPeakPassiveTiles = 0;
            debugPartPeakTotal = 0;
            debugPartPeakScalingInsertions = 0;
            debugPartPeakTracking = true;
            return debugPartPeakObservationId;
        }
    }

    long[] debugPartPeakStats() {
        synchronized (passiveActiveLock) {
            return new long[]{
                    debugPartPeakObservationId,
                    debugPartPeakActive,
                    debugPartPeakPassive,
                    debugPartPeakActiveTiles,
                    debugPartPeakPassiveTiles,
                    debugPartPeakTotal,
                    debugPartPeakScalingInsertions
            };
        }
    }

    private void recordDebugPartPeak() {
        if (!debugPartPeakTracking || !scaling) {
            return;
        }
        debugPartPeakScalingInsertions++;
        int total = activeCache.size() + passiveCache.size();
        if (total <= debugPartPeakTotal) {
            return;
        }
        debugPartPeakActive = activeCache.size();
        debugPartPeakPassive = passiveCache.size();
        debugPartPeakActiveTiles = debugTileCount(activeCache);
        debugPartPeakPassiveTiles = debugTileCount(passiveCache);
        debugPartPeakTotal = total;
    }

    private static int debugTileCount(Collection<PagePart> parts) {
        int count = 0;
        for (PagePart part : parts) {
            if (!part.isSnapshot()) {
                count++;
            }
        }
        return count;
    }

    private static long debugPartBytes(Collection<PagePart> parts) {
        long bytes = 0;
        for (PagePart part : parts) {
            Bitmap bitmap = part.getRenderedBitmap();
            if (bitmap != null) {
                bytes += bitmap.getByteCount();
            }
        }
        return bytes;
    }

    private void makeAFreeSpace() {
        synchronized (passiveActiveLock) {
            int limit = scaling ? CACHE_SIZE * 4 : CACHE_SIZE;

            while ((activeCache.size() + passiveCache.size()) >= limit &&
                    !passiveCache.isEmpty()) {
                recycleBitmap(passiveCache.poll());
            }

            if (scaling) {
                return;
            }

            while ((activeCache.size() + passiveCache.size()) >= limit &&
                    !activeCache.isEmpty()) {
                recycleBitmap(activeCache.poll());
            }
        }
    }

    public boolean upPartIfContained(int page, RectF pageRelativeBounds, int toOrder) {
        PagePart fakePart = new PagePart(page, null, pageRelativeBounds, 0);

        PagePart found;
        synchronized (passiveActiveLock) {
            if ((found = find(passiveCache, fakePart)) != null) {
                if (found.isStale()) {
                    return false;
                }
                passiveCache.remove(found);
                found.setCacheOrder(toOrder);
                activeCache.offer(found);
                return true;
            }

            found = find(activeCache, fakePart);
            return found != null && !found.isStale();
        }
    }

    @Nullable
    private static PagePart find(PriorityQueue<PagePart> vector, PagePart fakePart) {
        for (PagePart part : vector) {
            if (part.equals(fakePart)) {
                return part;
            }
        }
        return null;
    }

    @Nullable
    PagePart debugActivePart(int page, RectF pageRelativeBounds) {
        PagePart probe = new PagePart(page, null, pageRelativeBounds, 0);
        synchronized (passiveActiveLock) {
            return find(activeCache, probe);
        }
    }

    public List<PagePart> getPageParts() {
        synchronized (passiveActiveLock) {
            List<PagePart> parts = new ArrayList<>(passiveCache);
            parts.addAll(activeCache);
            Collections.sort(parts, orderComparator);
            return parts;
        }
    }

    public void recycle() {
        scaling = false;
        synchronized (passiveActiveLock) {
            for (PagePart part : passiveCache) {
                recycleBitmap(part);
            }
            passiveCache.clear();
            for (PagePart part : activeCache) {
                recycleBitmap(part);
            }
            activeCache.clear();
        }
    }

    class PagePartComparator implements Comparator<PagePart> {
        @Override
        public int compare(PagePart part1, PagePart part2) {
            if (part1.getCacheOrder() == part2.getCacheOrder()) {
                return 0;
            }
            return part1.getCacheOrder() > part2.getCacheOrder() ? 1 : -1;
        }
    }

}
