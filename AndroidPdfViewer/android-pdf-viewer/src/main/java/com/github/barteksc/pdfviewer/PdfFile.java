/**
 * Copyright 2017 Bartosz Schiller
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

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.model.CropBounds;
import com.github.barteksc.pdfviewer.model.CropMargins;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.github.barteksc.pdfviewer.util.PageSizeCalculator;
import com.shockwave.pdfium.PageTextTooLargeException;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import com.shockwave.pdfium.util.Size;
import com.shockwave.pdfium.util.SizeF;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

class PdfFile {

    private static final String TAG = PdfFile.class.getSimpleName();
    private static final Object lock = new Object();
    private static volatile boolean debugChecksEnabled = false;
    private static volatile boolean mainThreadChecksEnabled = false;
    private static volatile PDFView.MainThreadViolationReporter mainThreadViolationReporter = null;
    private static final int MAIN_THREAD_VIOLATION_REPORT_LIMIT = 3;
    private static final ConcurrentHashMap<String, AtomicInteger> mainThreadViolationCounts = new ConcurrentHashMap<>();
    private final ReentrantLock renderGate = new ReentrantLock(true);
    private volatile AtomicBoolean activeRenderCancel = null;
    private volatile boolean fenceRequested = false;
    private PdfDocument pdfDocument;
    private PdfiumCore pdfiumCore;
    private volatile boolean disposed = false;
    private int pagesCount = 0;
    /** Original page sizes */
    private List<Size> originalPageSizes = new ArrayList<>();
    /** Full page sizes before optional margin cropping */
    private List<Size> originalFullPageSizes = new ArrayList<>();
    /** Full page sizes in PDF points before optional margin cropping */
    private List<SizeF> originalFullPagePointSizes = new ArrayList<>();
    private AtomicReferenceArray<PageGeometry> pageGeometries = new AtomicReferenceArray<>(0);
    /** Scaled page sizes */
    private List<SizeF> pageSizes = new ArrayList<>();
    /** Opened pages with indicator whether opening was successful */
    private SparseBooleanArray openedPages = new SparseBooleanArray();
    /** Pages opened only to back a pinned text page. */
    private SparseBooleanArray textOpenedPages = new SparseBooleanArray();
    private final LinkedHashSet<Integer> renderPageOrder = new LinkedHashSet<>();
    private static final int MAX_OPEN_RENDER_PAGES = 12;
    private volatile int pinnedRenderPage = -1;
    private final Map<Integer, List<PdfDocument.HighlightAnnotation>> highlightAnnotationCache = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> formFieldRectCache = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, List<PdfDocument.HighlightAnnotation>>> highlightGroupCache =
            new ConcurrentHashMap<>();
    int debugHighlightGroupCacheMisses;
    /** Page with maximum width */
    private Size originalMaxWidthPageSize = new Size(0, 0);
    /** Page with maximum height */
    private Size originalMaxHeightPageSize = new Size(0, 0);
    /** Scaled page with maximum height */
    private SizeF maxHeightPageSize = new SizeF(0, 0);
    /** Scaled page with maximum width */
    private SizeF maxWidthPageSize = new SizeF(0, 0);
    /** True if scrolling is vertical, else it's horizontal */
    private boolean isVertical;
    /** True if horizontal page layout should be right-to-left. */
    private boolean horizontalRtl;
    /** Fixed spacing between pages in pixels */
    private int spacingPx;
    /** Calculate spacing automatically so each page fits on it's own in the center of the view */
    private boolean autoSpacing;
    /** How many pages share a layout row (1 or 2) */
    private int pagesPerRow;
    /** True if the first page forms a row of its own in two-up mode. */
    private boolean firstPageAlone;
    /** Row index for every page */
    private int[] pageRowIndexes = new int[0];
    /** First page index of every row */
    private List<Integer> rowFirstPages = new ArrayList<>();
    /** Calculated offsets for rows */
    private List<Float> rowOffsets = new ArrayList<>();
    /** Primary-axis length of every row */
    private List<Float> rowLengths = new ArrayList<>();
    /** Row indexes in their physical layout order. */
    private List<Integer> rowIndexesByOffset = new ArrayList<>();
    /** Calculated auto spacing for pages */
    private List<Float> pageSpacing = new ArrayList<>();
    /** Calculated document length (width or height, depending on swipe mode) */
    private float documentLength = 0;
    private final FitPolicy pageFitPolicy;
    /**
     * True if every page should fit separately according to the FitPolicy,
     * else the largest page fits and other pages scale relatively
    */
    private final boolean fitEachPage;
    private final boolean cropMarginsEnabled;
    private CropMargins cropMargins;
    /**
     * The pages the user want to display in order
     * (ex: 0, 2, 2, 8, 8, 1, 1, 1)
     */
    private int[] originalUserPages;

    PdfFile(PdfiumCore pdfiumCore, PdfDocument pdfDocument, FitPolicy pageFitPolicy, Size viewSize, int[] originalUserPages,
            boolean isVertical, int spacing, boolean autoSpacing, boolean fitEachPage,
            boolean cropMarginsEnabled, CropMargins cachedCropMargins, boolean horizontalRtl,
            int pagesPerRow, boolean firstPageAlone) {
        this.pdfiumCore = pdfiumCore;
        this.pdfDocument = pdfDocument;
        this.pageFitPolicy = pageFitPolicy;
        this.originalUserPages = originalUserPages;
        this.isVertical = isVertical;
        this.horizontalRtl = horizontalRtl && !isVertical;
        this.spacingPx = spacing;
        this.autoSpacing = autoSpacing;
        this.fitEachPage = fitEachPage;
        this.cropMarginsEnabled = cropMarginsEnabled;
        this.cropMargins = cachedCropMargins == null ? CropMargins.fullPage() : cachedCropMargins;
        this.pagesPerRow = isVertical && !autoSpacing && pagesPerRow == 2 ? 2 : 1;
        this.firstPageAlone = firstPageAlone && this.pagesPerRow == 2;
        setup(viewSize);
    }

    private void setup(Size viewSize) {
        if (originalUserPages != null) {
            pagesCount = originalUserPages.length;
        } else {
            pagesCount = pdfiumCore.getPageCount(pdfDocument);
        }

        for (int i = 0; i < pagesCount; i++) {
            int docPage = documentPage(i);
            originalFullPageSizes.add(pdfiumCore.getPageSize(pdfDocument, docPage));
            originalFullPagePointSizes.add(pdfiumCore.getPageSizePoint(pdfDocument, docPage));
        }

        pageGeometries = new AtomicReferenceArray<>(pagesCount);

        for (int i = 0; i < pagesCount; i++) {
            Size pageSize = calculateOriginalPageSize(i);
            if (pageSize.getWidth() > originalMaxWidthPageSize.getWidth()) {
                originalMaxWidthPageSize = pageSize;
            }
            if (pageSize.getHeight() > originalMaxHeightPageSize.getHeight()) {
                originalMaxHeightPageSize = pageSize;
            }
            originalPageSizes.add(pageSize);
        }

        recalculatePageSizes(viewSize);
    }

    private Size calculateOriginalPageSize(int pageIndex) {
        Size fullSize = originalFullPageSizes.get(pageIndex);
        if (!cropMarginsEnabled) {
            return fullSize;
        }

        int docPage = documentPage(pageIndex);
        CropBounds crop = cropMargins.forDocumentPage(docPage);
        int width = Math.max(1, Math.round(fullSize.getWidth() * crop.getWidth()));
        int height = Math.max(1, Math.round(fullSize.getHeight() * crop.getHeight()));
        return new Size(width, height);
    }

    public Size getOriginalPageSize(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= originalPageSizes.size()) {
            return new Size(0, 0);
        }
        return originalPageSizes.get(pageIndex);
    }

    private Size getOriginalFullPageSize(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= originalFullPageSizes.size()) {
            return new Size(0, 0);
        }
        return originalFullPageSizes.get(pageIndex);
    }

    private SizeF getOriginalFullPagePointSize(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= originalFullPagePointSizes.size()) {
            return new SizeF(0, 0);
        }
        return originalFullPagePointSizes.get(pageIndex);
    }

    private PageGeometry getPageGeometry(int pageIndex) {
        if (pageIndex >= 0 && pageIndex < pageGeometries.length()) {
            PageGeometry geometry = pageGeometries.get(pageIndex);
            if (geometry != null) {
                return geometry;
            }
        }
        SizeF fullSize = getOriginalFullPagePointSize(pageIndex);
        return new PageGeometry(0, 0, fullSize.getWidth(), fullSize.getHeight(), 0);
    }

    private void userToFrameTopDown(PageGeometry g, float ux, float uy, float[] out) {
        float frameX;
        float frameY;
        switch (g.rotation) {
            case 1:
                frameX = uy - g.bottom;
                frameY = g.right - ux;
                break;
            case 2:
                frameX = g.right - ux;
                frameY = g.top - uy;
                break;
            case 3:
                frameX = g.top - uy;
                frameY = ux - g.left;
                break;
            default:
                frameX = ux - g.left;
                frameY = uy - g.bottom;
                break;
        }
        out[0] = frameX;
        out[1] = g.frameHeight() - frameY;
    }

    private void frameTopDownToUser(PageGeometry g, float frameX, float frameYFromTop, float[] out) {
        float frameY = g.frameHeight() - frameYFromTop;
        switch (g.rotation) {
            case 1:
                out[0] = g.right - frameY;
                out[1] = g.bottom + frameX;
                break;
            case 2:
                out[0] = g.right - frameX;
                out[1] = g.top - frameY;
                break;
            case 3:
                out[0] = g.left + frameY;
                out[1] = g.top - frameX;
                break;
            default:
                out[0] = g.left + frameX;
                out[1] = g.bottom + frameY;
                break;
        }
    }

    public float fullPageAspectRatio(int pageIndex) {
        Size full = getOriginalFullPageSize(pageIndex);
        if (full.getWidth() <= 0) {
            return 0f;
        }
        return (float) full.getHeight() / (float) full.getWidth();
    }

    public SizeF getPagePointSize(int pageIndex) {
        return getOriginalFullPagePointSize(pageIndex);
    }

    public List<PdfDocument.FontInfo> getAllFonts(int maxPages) {
        List<PdfDocument.FontInfo> fonts = new ArrayList<>();
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return fonts;
        }
        int pagesToScan = Math.min(pagesCount, maxPages);
        for (int pageIndex = 0; pageIndex < pagesToScan; pageIndex++) {
            int docPage = documentPage(pageIndex);
            if (docPage < 0) {
                continue;
            }
            for (PdfDocument.FontInfo font : pdfiumCore.getPageFonts(pdfDocument, docPage)) {
                if (!fonts.contains(font)) {
                    fonts.add(font);
                }
            }
        }
        return fonts;
    }

    /**
     * Call after view size change to recalculate page sizes, offsets and document length
     *
     * @param viewSize new size of changed view
     */
    public void recalculatePageSizes(Size viewSize) {
        pageSizes.clear();
        Size layoutSize = pagesPerRow == 2
                ? new Size(Math.max(1, (viewSize.getWidth() - spacingPx) / 2), viewSize.getHeight())
                : viewSize;
        boolean effectiveFitEachPage = fitEachPage || cropMarginsEnabled;
        PageSizeCalculator calculator = new PageSizeCalculator(pageFitPolicy, originalMaxWidthPageSize,
                originalMaxHeightPageSize, layoutSize, effectiveFitEachPage);
        maxWidthPageSize = calculator.getOptimalMaxWidthPageSize();
        maxHeightPageSize = calculator.getOptimalMaxHeightPageSize();

        for (Size size : originalPageSizes) {
            pageSizes.add(calculator.calculate(size));
        }
        if (autoSpacing) {
            prepareAutoSpacing(viewSize);
        }
        prepareRows();
        prepareDocLen();
        prepareRowOffsets();
    }

    public int getPagesCount() {
        return pagesCount;
    }

    public SizeF getPageSize(int pageIndex) {
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return new SizeF(0, 0);
        }
        return pageSizes.get(pageIndex);
    }

    public SizeF getScaledPageSize(int pageIndex, float zoom) {
        SizeF size = getPageSize(pageIndex);
        return new SizeF(size.getWidth() * zoom, size.getHeight() * zoom);
    }

    /**
     * get page size with biggest dimension (width in vertical mode and height in horizontal mode)
     *
     * @return size of page
     */
    public SizeF getMaxPageSize() {
        return isVertical ? maxWidthPageSize : maxHeightPageSize;
    }

    public float getMaxPageWidth() {
        if (pagesPerRow == 2) {
            return maxWidthPageSize.getWidth() * 2 + spacingPx;
        }
        return getMaxPageSize().getWidth();
    }

    public float getMaxPageHeight() {
        return getMaxPageSize().getHeight();
    }

    private void prepareAutoSpacing(Size viewSize) {
        pageSpacing.clear();
        for (int i = 0; i < getPagesCount(); i++) {
            pageSpacing.add(0f);
        }
        for (int position = 0; position < getPagesCount(); position++) {
            int pageIndex = horizontalRtl ? getPagesCount() - 1 - position : position;
            SizeF pageSize = pageSizes.get(pageIndex);
            float spacing = Math.max(0, isVertical ? viewSize.getHeight() - pageSize.getHeight() :
                    viewSize.getWidth() - pageSize.getWidth());
            if (position < getPagesCount() - 1) {
                spacing += spacingPx;
            }
            pageSpacing.set(pageIndex, spacing);
        }
    }

    private void prepareRows() {
        rowFirstPages.clear();
        rowLengths.clear();
        rowIndexesByOffset.clear();
        pageRowIndexes = new int[getPagesCount()];
        int page = 0;
        if (firstPageAlone && getPagesCount() > 0) {
            pageRowIndexes[0] = 0;
            rowFirstPages.add(0);
            page = 1;
        }
        while (page < getPagesCount()) {
            int row = rowFirstPages.size();
            rowFirstPages.add(page);
            for (int member = 0; member < pagesPerRow && page < getPagesCount(); member++, page++) {
                pageRowIndexes[page] = row;
            }
        }
        for (int row = 0; row < rowFirstPages.size(); row++) {
            float length = 0;
            for (int member = 0; member < getPagesInRow(row); member++) {
                SizeF pageSize = pageSizes.get(rowFirstPages.get(row) + member);
                length = Math.max(length, isVertical ? pageSize.getHeight() : pageSize.getWidth());
            }
            rowLengths.add(length);
        }
        for (int position = 0; position < rowFirstPages.size(); position++) {
            rowIndexesByOffset.add(horizontalRtl ? rowFirstPages.size() - 1 - position : position);
        }
    }

    private void prepareDocLen() {
        float length = 0;
        if (autoSpacing) {
            for (int i = 0; i < getPagesCount(); i++) {
                SizeF pageSize = pageSizes.get(i);
                length += isVertical ? pageSize.getHeight() : pageSize.getWidth();
                length += pageSpacing.get(i);
            }
        } else {
            for (int row = 0; row < rowFirstPages.size(); row++) {
                length += rowLengths.get(row);
                if (row < rowFirstPages.size() - 1) {
                    length += spacingPx;
                }
            }
        }
        documentLength = length;
    }

    private void prepareRowOffsets() {
        rowOffsets.clear();
        for (int row = 0; row < rowFirstPages.size(); row++) {
            rowOffsets.add(0f);
        }
        float offset = 0;
        for (int position = 0; position < rowFirstPages.size(); position++) {
            int row = rowIndexesByOffset.get(position);
            float size = rowLengths.get(row);
            if (autoSpacing) {
                int pageIndex = rowFirstPages.get(row);
                offset += pageSpacing.get(pageIndex) / 2f;
                if (position == 0) {
                    offset -= spacingPx / 2f;
                } else if (position == rowFirstPages.size() - 1) {
                    offset += spacingPx / 2f;
                }
                rowOffsets.set(row, offset);
                offset += size + pageSpacing.get(pageIndex) / 2f;
            } else {
                rowOffsets.set(row, offset);
                offset += size + spacingPx;
            }
        }
    }

    public float getDocLen(float zoom) {
        return documentLength * zoom;
    }

    /**
     * Get the page's height if swiping vertical, or width if swiping horizontal.
     */
    public float getPageLength(int pageIndex, float zoom) {
        SizeF size = getPageSize(pageIndex);
        return (isVertical ? size.getHeight() : size.getWidth()) * zoom;
    }

    public float getPageSpacing(int pageIndex, float zoom) {
        float spacing = autoSpacing ? pageSpacing.get(pageIndex) : spacingPx;
        return spacing * zoom;
    }

    /** Get primary page offset, that is Y for vertical scroll and X for horizontal scroll */
    public float getPageOffset(int pageIndex, float zoom) {
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return 0;
        }
        return rowOffsets.get(pageRowIndexes[pageIndex]) * zoom;
    }

    /** Get secondary page offset, that is X for vertical scroll and Y for horizontal scroll */
    public float getSecondaryPageOffset(int pageIndex, float zoom) {
        SizeF pageSize = getPageSize(pageIndex);
        if (isVertical) {
            if (pagesPerRow == 2) {
                float columnWidth = maxWidthPageSize.getWidth();
                int column = (firstPageAlone ? pageIndex + 1 : pageIndex) % 2;
                return zoom * (column * (columnWidth + spacingPx) + (columnWidth - pageSize.getWidth()) / 2); //x
            }
            float maxWidth = getMaxPageWidth();
            return zoom * (maxWidth - pageSize.getWidth()) / 2; //x
        } else {
            float maxHeight = getMaxPageHeight();
            return zoom * (maxHeight - pageSize.getHeight()) / 2; //y
        }
    }

    public int getRowCount() {
        return rowFirstPages.size();
    }

    public int getRowOfPage(int pageIndex) {
        if (pageRowIndexes.length == 0) {
            return 0;
        }
        int limitedIndex = Math.max(0, Math.min(pageIndex, pageRowIndexes.length - 1));
        return pageRowIndexes[limitedIndex];
    }

    public int getRowFirstPage(int rowIndex) {
        if (rowFirstPages.isEmpty()) {
            return 0;
        }
        int limitedIndex = Math.max(0, Math.min(rowIndex, rowFirstPages.size() - 1));
        return rowFirstPages.get(limitedIndex);
    }

    public int getPagesInRow(int rowIndex) {
        if (rowFirstPages.isEmpty()) {
            return 0;
        }
        int limitedIndex = Math.max(0, Math.min(rowIndex, rowFirstPages.size() - 1));
        int nextFirstPage = limitedIndex + 1 < rowFirstPages.size()
                ? rowFirstPages.get(limitedIndex + 1)
                : getPagesCount();
        return nextFirstPage - rowFirstPages.get(limitedIndex);
    }

    public float getRowOffset(int rowIndex, float zoom) {
        if (rowOffsets.isEmpty()) {
            return 0;
        }
        int limitedIndex = Math.max(0, Math.min(rowIndex, rowOffsets.size() - 1));
        return rowOffsets.get(limitedIndex) * zoom;
    }

    public float getRowLength(int rowIndex, float zoom) {
        if (rowLengths.isEmpty()) {
            return 0;
        }
        int limitedIndex = Math.max(0, Math.min(rowIndex, rowLengths.size() - 1));
        return rowLengths.get(limitedIndex) * zoom;
    }

    public float getRowSpacing(int rowIndex, float zoom) {
        float spacing = autoSpacing ? pageSpacing.get(getRowFirstPage(rowIndex)) : spacingPx;
        return spacing * zoom;
    }

    public int getPagesPerRow() {
        return pagesPerRow;
    }

    public boolean isFirstPageAlone() {
        return firstPageAlone;
    }

    public int getPageAtOffset(float offset, float zoom) {
        return getRowFirstPage(getRowAtLayoutIndex(getRowLayoutIndexAtOffset(offset, zoom)));
    }

    public int getPageAtOffset(float offset, float secondaryOffset, float zoom) {
        int row = getRowAtLayoutIndex(getRowLayoutIndexAtOffset(offset, zoom));
        int firstPage = getRowFirstPage(row);
        if (getPagesInRow(row) < 2) {
            return firstPage;
        }
        float columnBoundary = zoom * (maxWidthPageSize.getWidth() + spacingPx / 2f);
        return secondaryOffset >= columnBoundary ? firstPage + 1 : firstPage;
    }

    public int getRowLayoutIndexAtOffset(float offset, float zoom) {
        if (rowIndexesByOffset.isEmpty()) {
            return 0;
        }
        int low = 0;
        int high = rowIndexesByOffset.size() - 1;
        int currentLayoutIndex = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int row = rowIndexesByOffset.get(mid);
            float off = rowOffsets.get(row) * zoom - getRowSpacing(row, zoom) / 2f;
            if (off < offset) {
                currentLayoutIndex = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return currentLayoutIndex;
    }

    public int getRowAtLayoutIndex(int layoutIndex) {
        if (rowIndexesByOffset.isEmpty()) {
            return 0;
        }
        int limitedIndex = Math.max(0, Math.min(layoutIndex, rowIndexesByOffset.size() - 1));
        return rowIndexesByOffset.get(limitedIndex);
    }

    private void capturePageGeometry(int pageIndex, int docPage) {
        if (pageIndex < 0 || pageIndex >= pageGeometries.length() || pageGeometries.get(pageIndex) != null) {
            return;
        }
        float[] values = pdfiumCore.getPageGeometry(pdfDocument, docPage);
        if (values.length < 5) {
            return;
        }
        float left = values[0];
        float bottom = values[1];
        float right = values[2];
        float top = values[3];
        int rotation = (int) values[4];
        if (right - left > 0 && top - bottom > 0 && rotation >= 0 && rotation <= 3) {
            pageGeometries.set(pageIndex, new PageGeometry(left, bottom, right, top, rotation));
        }
    }

    public boolean openPage(int pageIndex) throws PageRenderingException {
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }

        synchronized (lock) {
            if (disposed || pdfDocument == null) {
                return false;
            }
            if (openedPages.indexOfKey(docPage) >= 0) {
                if (openedPages.get(docPage, false)) {
                    textOpenedPages.delete(docPage);
                    renderPageOrder.remove(docPage);
                    renderPageOrder.add(docPage);
                    capturePageGeometry(pageIndex, docPage);
                }
                return false;
            }
            try {
                warnIfMainThreadFill("openPage");
                openNativePage(pageIndex, docPage);
                openedPages.put(docPage, true);
                renderPageOrder.add(docPage);
                capturePageGeometry(pageIndex, docPage);
                evictOverCap();
                return true;
            } catch (Exception e) {
                openedPages.put(docPage, false);
                throw new PageRenderingException(pageIndex, e);
            }
        }
    }

    private void openNativePage(int pageIndex, int docPage) {
        ReplayMeasurement.Session replayMeasurement = null;
        if (PDFView.isDebugStateEnabled() && Looper.getMainLooper().isCurrentThread()) {
            replayMeasurement = ReplayMeasurement.activeSession();
        }
        if (replayMeasurement == null) {
            pdfiumCore.openPage(pdfDocument, docPage);
            return;
        }
        long nativeOpenStartNanos = SystemClock.elapsedRealtimeNanos();
        try {
            pdfiumCore.openPage(pdfDocument, docPage);
            replayMeasurement.recordNativeOpen(
                    pageIndex, docPage, SystemClock.elapsedRealtimeNanos() - nativeOpenStartNanos, true);
        } catch (RuntimeException | Error throwable) {
            replayMeasurement.recordNativeOpen(
                    pageIndex, docPage, SystemClock.elapsedRealtimeNanos() - nativeOpenStartNanos, false);
            throw throwable;
        }
    }

    public boolean pageHasError(int pageIndex) {
        int docPage = documentPage(pageIndex);
        return !openedPages.get(docPage, false);
    }

    public void ensureTextPage(int pageIndex) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return;
        }
        warnIfMainThreadFill("ensureTextPage");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return;
        }
        synchronized (lock) {
            if (openedPages.indexOfKey(docPage) < 0) {
                try {
                    pdfiumCore.openPage(pdfDocument, docPage);
                    openedPages.put(docPage, true);
                    textOpenedPages.put(docPage, true);
                } catch (Exception e) {
                    openedPages.put(docPage, false);
                    return;
                }
            } else if (!openedPages.get(docPage, false)) {
                return;
            }
            capturePageGeometry(pageIndex, docPage);
            pdfiumCore.openTextPage(pdfDocument, docPage);
        }
    }

    public void ensurePageGeometry(int pageIndex) {
        if (pageIndex >= 0 && pageIndex < pageGeometries.length()
                && pageGeometries.get(pageIndex) != null) {
            return;
        }
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return;
        }
        synchronized (lock) {
            if (disposed || pdfDocument == null) {
                return;
            }
            if (openedPages.indexOfKey(docPage) < 0) {
                try {
                    pdfiumCore.openPage(pdfDocument, docPage);
                    openedPages.put(docPage, true);
                } catch (Exception e) {
                    openedPages.put(docPage, false);
                    return;
                }
            } else if (!openedPages.get(docPage, false)) {
                return;
            }
            capturePageGeometry(pageIndex, docPage);
        }
    }

    public void closeTextPage(int pageIndex) {
        if (pdfiumCore == null || pdfDocument == null) {
            return;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return;
        }
        synchronized (lock) {
            pdfiumCore.closeTextPage(pdfDocument, docPage);
            closeTextOwnedPage(docPage);
        }
    }

    public void closeAllTextPages() {
        if (pdfiumCore == null || pdfDocument == null) {
            return;
        }
        synchronized (lock) {
            pdfiumCore.closeTextPages(pdfDocument);
            closeTextOwnedPages();
        }
    }

    private void closeTextOwnedPages() {
        for (int i = textOpenedPages.size() - 1; i >= 0; i--) {
            closeTextOwnedPage(textOpenedPages.keyAt(i));
        }
    }

    private void closeTextOwnedPage(int docPage) {
        if (!textOpenedPages.get(docPage, false)) {
            return;
        }
        pdfiumCore.closePage(pdfDocument, docPage);
        openedPages.delete(docPage);
        renderPageOrder.remove(docPage);
        textOpenedPages.delete(docPage);
    }

    private void evictOverCap() {
        if (renderPageOrder.size() <= MAX_OPEN_RENDER_PAGES) {
            return;
        }
        Iterator<Integer> order = renderPageOrder.iterator();
        while (order.hasNext() && renderPageOrder.size() > MAX_OPEN_RENDER_PAGES) {
            int docPage = order.next();
            if (docPage == pinnedRenderPage || textOpenedPages.get(docPage, false) || hasOpenTextPage(docPage)) {
                continue;
            }
            pdfiumCore.closePage(pdfDocument, docPage);
            openedPages.delete(docPage);
            order.remove();
        }
    }

    private boolean hasOpenTextPage(int docPage) {
        return pdfDocument != null && pdfiumCore.hasTextPage(pdfDocument, docPage);
    }

    void closeRenderOwnedPage(int pageIndex) {
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return;
        }
        synchronized (lock) {
            if (disposed || pdfDocument == null) {
                return;
            }
            if (!openedPages.get(docPage, false) || docPage == pinnedRenderPage
                    || textOpenedPages.get(docPage, false) || hasOpenTextPage(docPage)) {
                return;
            }
            pdfiumCore.closePage(pdfDocument, docPage);
            openedPages.delete(docPage);
            renderPageOrder.remove(docPage);
        }
    }

    int[] debugPageStats() {
        synchronized (lock) {
            int open = 0;
            for (int i = 0; i < openedPages.size(); i++) {
                if (openedPages.valueAt(i)) {
                    open++;
                }
            }
            return new int[]{
                    open,
                    openedPages.size(),
                    renderPageOrder.size(),
                    textOpenedPages.size(),
                    pinnedRenderPage,
                    MAX_OPEN_RENDER_PAGES
            };
        }
    }

    void pinPage(int pageIndex) {
        int docPage = documentPage(pageIndex);
        pinnedRenderPage = docPage >= 0 ? docPage : -1;
    }

    void unpinPage() {
        pinnedRenderPage = -1;
    }

    public int pageCharCount(int pageIndex) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return 0;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return 0;
        }
        return pdfiumCore.textCountChars(pdfDocument, docPage);
    }

    public int charIndexAtPagePoint(int pageIndex, float pdfX, float pdfY, float tolerance) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return -1;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return -1;
        }
        return pdfiumCore.charIndexAtPos(pdfDocument, docPage, pdfX, pdfY, tolerance);
    }

    public boolean looseCharBox(int pageIndex, int charIndex, float[] out4) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        return pdfiumCore.looseCharBox(pdfDocument, docPage, charIndex, out4);
    }

    public boolean tightCharBox(int pageIndex, int charIndex, float[] out4) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        return pdfiumCore.tightCharBox(pdfDocument, docPage, charIndex, out4);
    }

    public int charUnicode(int pageIndex, int charIndex) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return 0;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return 0;
        }
        return pdfiumCore.charUnicode(pdfDocument, docPage, charIndex);
    }

    public String textRange(int pageIndex, int start, int count) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return "";
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return "";
        }
        return pdfiumCore.textRange(pdfDocument, docPage, start, count);
    }

    public float[] textRects(int pageIndex, int start, int count) {
        if (disposed || pdfiumCore == null || pdfDocument == null) {
            return new float[0];
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return new float[0];
        }
        return pdfiumCore.textRects(pdfDocument, docPage, start, count);
    }

    public PointF documentToPdf(int pageIndex, float zoom, float docX, float docY) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        SizeF renderedSize = getScaledPageSize(pageIndex, zoom);
        float frameWidth = geometry.frameWidth();
        float frameHeight = geometry.frameHeight();
        if (frameWidth <= 0 || frameHeight <= 0
                || renderedSize.getWidth() <= 0 || renderedSize.getHeight() <= 0) {
            return new PointF(0, 0);
        }

        float pageOriginX = isVertical ? getSecondaryPageOffset(pageIndex, zoom) : getPageOffset(pageIndex, zoom);
        float pageOriginY = isVertical ? getPageOffset(pageIndex, zoom) : getSecondaryPageOffset(pageIndex, zoom);
        float localX = docX - pageOriginX;
        float localY = docY - pageOriginY;

        CropBounds crop = getCropBounds(pageIndex);
        float cropLeft = crop.getLeft() * frameWidth;
        float cropTop = crop.getTop() * frameHeight;
        float cropWidth = Math.max(1f, crop.getWidth() * frameWidth);
        float cropHeight = Math.max(1f, crop.getHeight() * frameHeight);

        float frameX = cropLeft + localX * cropWidth / renderedSize.getWidth();
        float frameYFromTop = cropTop + localY * cropHeight / renderedSize.getHeight();
        float[] user = new float[2];
        frameTopDownToUser(geometry, frameX, frameYFromTop, user);
        return new PointF(user[0], user[1]);
    }

    public RectF pdfRectToDocument(int pageIndex, float zoom, float left, float bottom, float right, float top) {
        return pdfRectToDocument(pageIndex, zoom, left, bottom, right, top, true);
    }

    public RectF pdfRectToDocument(int pageIndex, float zoom, float left, float bottom, float right, float top,
                                   boolean clipToPage) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        float[] cornerA = new float[2];
        float[] cornerB = new float[2];
        userToFrameTopDown(geometry, left, bottom, cornerA);
        userToFrameTopDown(geometry, right, top, cornerB);
        return frameRectToDocument(pageIndex, zoom, cornerA[0], cornerA[1], cornerB[0], cornerB[1], clipToPage);
    }

    public RectF frameRectToDocument(int pageIndex, float zoom, float frameLeft, float frameTop,
                                     float frameRight, float frameBottom, boolean clipToPage) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        SizeF renderedSize = getScaledPageSize(pageIndex, zoom);
        float frameWidth = geometry.frameWidth();
        float frameHeight = geometry.frameHeight();
        if (frameWidth <= 0 || frameHeight <= 0
                || renderedSize.getWidth() <= 0 || renderedSize.getHeight() <= 0) {
            return null;
        }

        CropBounds crop = getCropBounds(pageIndex);
        float cropLeft = crop.getLeft() * frameWidth;
        float cropTop = crop.getTop() * frameHeight;
        float cropWidth = Math.max(1f, crop.getWidth() * frameWidth);
        float cropHeight = Math.max(1f, crop.getHeight() * frameHeight);

        float pageOriginX = isVertical ? getSecondaryPageOffset(pageIndex, zoom) : getPageOffset(pageIndex, zoom);
        float pageOriginY = isVertical ? getPageOffset(pageIndex, zoom) : getSecondaryPageOffset(pageIndex, zoom);

        float scaleX = renderedSize.getWidth() / cropWidth;
        float scaleY = renderedSize.getHeight() / cropHeight;
        RectF rect = new RectF(
                pageOriginX + (frameLeft - cropLeft) * scaleX,
                pageOriginY + (frameTop - cropTop) * scaleY,
                pageOriginX + (frameRight - cropLeft) * scaleX,
                pageOriginY + (frameBottom - cropTop) * scaleY
        );
        rect.sort();
        if (!clipToPage) {
            return rect;
        }
        return clipToPageBounds(rect, pageOriginX, pageOriginY, renderedSize.getWidth(), renderedSize.getHeight());
    }

    public SizeF getPageFrameSize(int pageIndex) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        return new SizeF(geometry.frameWidth(), geometry.frameHeight());
    }

    public PointF userToFrame(int pageIndex, float userX, float userY) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        float[] out = new float[2];
        userToFrameTopDown(geometry, userX, userY, out);
        return new PointF(out[0], out[1]);
    }

    public RectF userRectToFrame(int pageIndex, float left, float bottom, float right, float top) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        float[] cornerA = new float[2];
        float[] cornerB = new float[2];
        userToFrameTopDown(geometry, left, bottom, cornerA);
        userToFrameTopDown(geometry, right, top, cornerB);
        return new RectF(
                Math.min(cornerA[0], cornerB[0]),
                Math.min(cornerA[1], cornerB[1]),
                Math.max(cornerA[0], cornerB[0]),
                Math.max(cornerA[1], cornerB[1])
        );
    }

    public RectF frameRectToUser(int pageIndex, float frameLeft, float frameTop,
                                 float frameRight, float frameBottom) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        float[] cornerA = new float[2];
        float[] cornerB = new float[2];
        frameTopDownToUser(geometry, frameLeft, frameTop, cornerA);
        frameTopDownToUser(geometry, frameRight, frameBottom, cornerB);
        return new RectF(
                Math.min(cornerA[0], cornerB[0]),
                Math.max(cornerA[1], cornerB[1]),
                Math.max(cornerA[0], cornerB[0]),
                Math.min(cornerA[1], cornerB[1])
        );
    }

    private CropBounds getCropBounds(int pageIndex) {
        if (!cropMarginsEnabled) {
            return CropBounds.fullPage();
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return CropBounds.fullPage();
        }
        return cropMargins.forDocumentPage(docPage);
    }

    public CropBounds getPageCropBounds(int pageIndex) {
        return getCropBounds(pageIndex);
    }

    @Deprecated
    public RectF getPageUserBounds(int pageIndex) {
        PageGeometry g = getPageGeometry(pageIndex);
        return new RectF(g.left, g.top, g.right, g.bottom);
    }

    private RectF clipToPageBounds(RectF rect, float pageOriginX, float pageOriginY, float width, float height) {
        float right = pageOriginX + width;
        float bottom = pageOriginY + height;
        if (rect.right <= pageOriginX
                || rect.left >= right
                || rect.bottom <= pageOriginY
                || rect.top >= bottom) {
            return null;
        }

        rect.left = Math.max(rect.left, pageOriginX);
        rect.top = Math.max(rect.top, pageOriginY);
        rect.right = Math.min(rect.right, right);
        rect.bottom = Math.min(rect.bottom, bottom);
        return rect;
    }

    public void renderPageBitmap(Bitmap bitmap, int pageIndex, Rect bounds, boolean annotationRendering) {
        throwIfMainThreadFill("renderPageBitmap");
        int docPage = documentPage(pageIndex);
        Rect renderBounds = mapCropRenderBoundsToFullPage(pageIndex, bounds.left, bounds.top, bounds.width(), bounds.height());
        renderGate.lock();
        try {
            synchronized (lock) {
                if (disposed || pdfDocument == null) {
                    return;
                }
                pdfiumCore.renderPageBitmap(pdfDocument, bitmap, docPage,
                        renderBounds.left, renderBounds.top, renderBounds.width(), renderBounds.height(), annotationRendering);
            }
        } finally {
            renderGate.unlock();
        }
    }

    public boolean renderFullPageBitmapCancellable(Bitmap bitmap, int pageIndex,
                                                   boolean annotationRendering, int extraFlags, AtomicBoolean cancel) {
        throwIfMainThreadFill("renderFullPageBitmapCancellable");
        int docPage = documentPage(pageIndex);
        Rect renderBounds = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        return renderChunked(bitmap, docPage, renderBounds, annotationRendering, extraFlags, cancel);
    }

    private boolean renderChunked(Bitmap bitmap, int docPage, Rect renderBounds,
                                  boolean annotationRendering, int extraFlags, AtomicBoolean cancel) {
        renderGate.lock();
        activeRenderCancel = cancel;
        long ctx = 0L;
        try {
            if (disposed || pdfDocument == null) {
                return false;
            }
            ctx = pdfiumCore.renderPageBitmapChunkedStart(pdfDocument, bitmap, docPage,
                    renderBounds.left, renderBounds.top, renderBounds.width(), renderBounds.height(),
                    annotationRendering, extraFlags);
            if (ctx == 0L) {
                return false;
            }
            int status = pdfiumCore.renderPageBitmapChunkedStatus(ctx);
            while (status == PdfiumCore.RENDER_STATUS_TO_BE_CONTINUED) {
                if (cancel.get() || disposed || fenceRequested) {
                    pdfiumCore.renderPageBitmapChunkedClose(pdfDocument, ctx, docPage, bitmap, true, true, false);
                    ctx = 0L;
                    return false;
                }
                status = pdfiumCore.renderPageBitmapChunkedContinue(pdfDocument, ctx, docPage);
            }
            boolean completed = status == PdfiumCore.RENDER_STATUS_DONE;
            pdfiumCore.renderPageBitmapChunkedClose(pdfDocument, ctx, docPage, bitmap, true, true, completed);
            ctx = 0L;
            return completed;
        } finally {
            if (ctx != 0L) {
                pdfiumCore.renderPageBitmapChunkedClose(pdfDocument, ctx, docPage, bitmap, true, true, false);
            }
            activeRenderCancel = null;
            renderGate.unlock();
        }
    }

    private void fenceRenders() {
        fenceRequested = true;
        AtomicBoolean cancel = activeRenderCancel;
        if (cancel != null) {
            cancel.set(true);
        }
        renderGate.lock();
        fenceRequested = false;
    }

    private Rect mapCropRenderBoundsToFullPage(int pageIndex, int startX, int startY, int width, int height) {
        Rect original = new Rect(startX, startY, startX + width, startY + height);
        if (!cropMarginsEnabled || width <= 0 || height <= 0) {
            return original;
        }

        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return original;
        }

        CropBounds crop = cropMargins.forDocumentPage(docPage);
        if (crop.isFullPage()) {
            return original;
        }

        float fullWidth = width / crop.getWidth();
        float fullHeight = height / crop.getHeight();
        int fullStartX = Math.round(startX - crop.getLeft() * fullWidth);
        int fullStartY = Math.round(startY - crop.getTop() * fullHeight);
        int fullDrawWidth = Math.round(fullWidth);
        int fullDrawHeight = Math.round(fullHeight);
        return new Rect(fullStartX, fullStartY, fullStartX + fullDrawWidth, fullStartY + fullDrawHeight);
    }

    public PdfDocument.Meta getMetaData() {
        if (disposed || pdfDocument == null) {
            return null;
        }
        return pdfiumCore.getDocumentMeta(pdfDocument);
    }

    public List<PdfDocument.Bookmark> getBookmarks() {
        if (disposed || pdfDocument == null) {
            return new ArrayList<>();
        }
        return pdfiumCore.getTableOfContents(pdfDocument);
    }

    public List<PdfDocument.Link> getPageLinks(int pageIndex) {
        if (disposed || pdfDocument == null) {
            return new ArrayList<>();
        }
        warnIfMainThreadFill("getPageLinks");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return new ArrayList<>();
        }
        return pdfiumCore.getPageLinks(pdfDocument, docPage);
    }

    public String getPageText(int pageIndex) {
        if (disposed || pdfDocument == null) {
            return "";
        }
        warnIfMainThreadFill("getPageText");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return "";
        }
        try {
            openPage(pageIndex);
        } catch (PageRenderingException e) {
            return "";
        }
        return pdfiumCore.getPageText(pdfDocument, docPage);
    }

    public String getPageRawText(int pageIndex) {
        if (disposed || pdfDocument == null) {
            return "";
        }
        warnIfMainThreadFill("getPageRawText");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return "";
        }
        try {
            openPage(pageIndex);
        } catch (PageRenderingException e) {
            return "";
        }
        try {
            return pdfiumCore.getPageRawText(pdfDocument, docPage);
        } catch (PageTextTooLargeException e) {
            return "";
        }
    }

    public Map<Integer, String> getPagesText(int start, int end) {
        Map<Integer, String> pagesText = new HashMap<>();
        for (int pageIndex = start; pageIndex <= end; pageIndex++) {
            pagesText.put(pageIndex, getPageText(pageIndex));
        }
        return pagesText;
    }

    public Rect[] createHighlightText(int pageIndex, int start, int end, boolean padding) {
        if (disposed || pdfDocument == null) {
            return new Rect[0];
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return new Rect[0];
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return new Rect[0];
            }
            Rect[] result = pdfiumCore.createHighlightText(pdfDocument, docPage, start, end, padding);
            invalidateHighlightAnnotationCache(pageIndex);
            return result;
        } finally {
            renderGate.unlock();
        }
    }

    public boolean createHighlightAnnotation(int pageIndex, List<RectF> pdfRects,
                                              int color, String contents, String groupKey,
                                              String creationDate) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0 || pdfRects == null || pdfRects.isEmpty()) {
            return false;
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return false;
            }
            return pdfiumCore.createHighlightAnnotation(pdfDocument, docPage, pdfRects, color,
                    contents, groupKey, creationDate);
        } finally {
            renderGate.unlock();
        }
    }

    public boolean addSignature(int pageIndex, RectF pdfRect, float[][] normalizedStrokes,
                                int color, float normalizedStrokeWidth) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0 || pdfRect == null || pdfRect.width() <= 0
                || normalizedStrokes == null || normalizedStrokes.length == 0) {
            return false;
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return false;
            }
            PageGeometry geometry = getPageGeometry(pageIndex);
            float[] cornerA = new float[2];
            float[] cornerB = new float[2];
            userToFrameTopDown(geometry, pdfRect.left, pdfRect.top, cornerA);
            userToFrameTopDown(geometry, pdfRect.right, pdfRect.bottom, cornerB);
            float frameLeft = Math.min(cornerA[0], cornerB[0]);
            float frameTop = Math.min(cornerA[1], cornerB[1]);
            float frameRectWidth = Math.abs(cornerA[0] - cornerB[0]);
            if (frameRectWidth <= 0) {
                return false;
            }
            float[] user = new float[2];
            float[][] pdfStrokes = new float[normalizedStrokes.length][];
            for (int i = 0; i < normalizedStrokes.length; i++) {
                float[] stroke = normalizedStrokes[i];
                float[] mapped = new float[stroke.length];
                for (int k = 0; k + 1 < stroke.length; k += 2) {
                    frameTopDownToUser(geometry,
                            frameLeft + stroke[k] * frameRectWidth,
                            frameTop + stroke[k + 1] * frameRectWidth,
                            user);
                    mapped[k] = user[0];
                    mapped[k + 1] = user[1];
                }
                pdfStrokes[i] = mapped;
            }
            float strokeWidthPts = normalizedStrokeWidth * frameRectWidth;
            return pdfiumCore.addSignatureContent(pdfDocument, docPage, pdfStrokes, color, strokeWidthPts);
        } finally {
            renderGate.unlock();
        }
    }

    public List<PdfDocument.HighlightAnnotation> getHighlightAnnotations(int pageIndex) {
        List<PdfDocument.HighlightAnnotation> cached = highlightAnnotationCache.get(pageIndex);
        if (cached != null) {
            return cached;
        }
        warnIfMainThreadFill("getHighlightAnnotations");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return new ArrayList<>();
        }
        try {
            openPage(pageIndex);
        } catch (PageRenderingException e) {
            return new ArrayList<>();
        }
        List<PdfDocument.HighlightAnnotation> annotations;
        synchronized (lock) {
            if (disposed || pdfDocument == null) {
                return new ArrayList<>();
            }
            annotations = pdfiumCore.getHighlightAnnotations(pdfDocument, docPage);
        }
        List<PdfDocument.HighlightAnnotation> snapshot = annotations == null
                ? Collections.<PdfDocument.HighlightAnnotation>emptyList()
                : Collections.unmodifiableList(annotations);
        highlightGroupCache.remove(pageIndex);
        highlightAnnotationCache.put(pageIndex, snapshot);
        return snapshot;
    }

    void prewarmPageCaches(int pageIndex) {
        try {
            getFormFieldRects(pageIndex);
            getHighlightAnnotations(pageIndex);
        } catch (Throwable ignored) {
        }
    }

    boolean refillPageCaches(int pageIndex) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        try {
            openPage(pageIndex);
        } catch (PageRenderingException e) {
            return false;
        }
        List<PdfDocument.HighlightAnnotation> annotations;
        float[] rects;
        synchronized (lock) {
            if (disposed || pdfDocument == null) {
                return false;
            }
            annotations = pdfiumCore.getHighlightAnnotations(pdfDocument, docPage);
            rects = pdfiumCore.getFormFieldRects(pdfDocument, docPage);
        }
        List<PdfDocument.HighlightAnnotation> snapshot = annotations == null
                ? Collections.<PdfDocument.HighlightAnnotation>emptyList()
                : Collections.unmodifiableList(annotations);
        highlightGroupCache.remove(pageIndex);
        highlightAnnotationCache.put(pageIndex, snapshot);
        formFieldRectCache.put(pageIndex, rects);
        return true;
    }

    public List<PdfDocument.HighlightAnnotation> peekHighlightAnnotations(int pageIndex) {
        return highlightAnnotationCache.get(pageIndex);
    }

    Map<String, List<PdfDocument.HighlightAnnotation>> peekHighlightAnnotationGroups(
            int pageIndex, List<PdfDocument.HighlightAnnotation> annotations) {
        Map<String, List<PdfDocument.HighlightAnnotation>> cached = highlightGroupCache.get(pageIndex);
        if (cached != null) {
            return cached;
        }
        if (PDFView.isDebugStateEnabled()) {
            debugHighlightGroupCacheMisses++;
        }
        Map<String, List<PdfDocument.HighlightAnnotation>> groups = new LinkedHashMap<>();
        for (PdfDocument.HighlightAnnotation annotation : annotations) {
            if (annotation.getBounds() == null || annotation.isSearchResult()) {
                continue;
            }
            String groupKey = annotation.getGroupKey();
            String key = groupKey == null || groupKey.isEmpty()
                    ? "i" + annotation.getAnnotationIndex()
                    : "g" + groupKey + "#" + annotation.getColor() + "#" + annotation.isAppOwned();
            List<PdfDocument.HighlightAnnotation> members = groups.get(key);
            if (members == null) {
                members = new ArrayList<>();
                groups.put(key, members);
            }
            members.add(annotation);
        }
        highlightGroupCache.put(pageIndex, groups);
        return groups;
    }

    public float[] peekFormFieldRects(int pageIndex) {
        return formFieldRectCache.get(pageIndex);
    }

    static void setDebugChecksEnabled(boolean enabled) {
        debugChecksEnabled = enabled;
    }

    static boolean isDebugChecksEnabled() {
        return debugChecksEnabled;
    }

    static void setMainThreadChecksEnabled(boolean enabled) {
        mainThreadChecksEnabled = enabled;
    }

    static void setMainThreadViolationReporter(PDFView.MainThreadViolationReporter reporter) {
        mainThreadViolationReporter = reporter;
    }

    private static void throwIfMainThreadFill(String path) {
        if (debugChecksEnabled && Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException("pdfium fill on main thread: " + path);
        }
    }

    private static void warnIfMainThreadFill(String path) {
        if (!mainThreadChecksEnabled || !Looper.getMainLooper().isCurrentThread()) {
            return;
        }
        AtomicInteger counter = mainThreadViolationCounts.get(path);
        if (counter == null) {
            counter = new AtomicInteger();
            AtomicInteger existing = mainThreadViolationCounts.putIfAbsent(path, counter);
            if (existing != null) {
                counter = existing;
            }
        }
        int count = counter.incrementAndGet();
        if (count > MAIN_THREAD_VIOLATION_REPORT_LIMIT) {
            if (debugChecksEnabled) {
                Log.w(TAG, "pdfium work on main thread: " + path + " x" + count);
            }
            return;
        }
        Throwable stack = new Throwable("pdfium work on main thread: " + path + " x" + count);
        Log.w(TAG, "pdfium work on main thread: " + path + " x" + count, stack);
        PDFView.MainThreadViolationReporter reporter = mainThreadViolationReporter;
        if (reporter != null) {
            reporter.onMainThreadPdfiumWork(path, stack);
        }
    }

    public void invalidateHighlightAnnotationCache(int pageIndex) {
        highlightGroupCache.remove(pageIndex);
        highlightAnnotationCache.remove(pageIndex);
    }

    public void invalidateHighlightAnnotationCache() {
        highlightGroupCache.clear();
        highlightAnnotationCache.clear();
    }

    public void invalidateFormFieldRectCache(int pageIndex) {
        formFieldRectCache.remove(pageIndex);
    }

    public boolean setHighlightAnnotationColor(int pageIndex, int annotationIndex,
                                               String groupKey, int color) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return false;
            }
            return pdfiumCore.setHighlightAnnotationColor(pdfDocument, docPage, annotationIndex, groupKey, color);
        } finally {
            renderGate.unlock();
        }
    }

    public boolean setHighlightAnnotationNote(int pageIndex, int annotationIndex,
                                              String groupKey, String note, String modifiedDate) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return false;
            }
            return pdfiumCore.setHighlightAnnotationNote(pdfDocument, docPage, annotationIndex,
                    groupKey, note, modifiedDate);
        } finally {
            renderGate.unlock();
        }
    }

    public boolean removeHighlightAnnotation(int pageIndex, int annotationIndex, String groupKey) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return false;
            }
            return pdfiumCore.removeHighlightAnnotation(pdfDocument, docPage, annotationIndex, groupKey);
        } finally {
            renderGate.unlock();
        }
    }

    public float[] getFormFieldRects(int pageIndex) {
        float[] cached = formFieldRectCache.get(pageIndex);
        if (cached != null) {
            return cached;
        }
        warnIfMainThreadFill("getFormFieldRects");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return new float[0];
        }
        try {
            openPage(pageIndex);
        } catch (PageRenderingException e) {
            return new float[0];
        }
        float[] rects;
        synchronized (lock) {
            if (disposed || pdfDocument == null) {
                return new float[0];
            }
            rects = pdfiumCore.getFormFieldRects(pdfDocument, docPage);
        }
        formFieldRectCache.put(pageIndex, rects);
        return rects;
    }

    public PdfDocument.FormField getFormFieldAtPoint(int pageIndex, float pdfX, float pdfY, float tolerance) {
        if (disposed || pdfDocument == null) {
            return null;
        }
        warnIfMainThreadFill("getFormFieldAtPoint");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return null;
        }
        try {
            openPage(pageIndex);
        } catch (PageRenderingException e) {
            return null;
        }
        return pdfiumCore.getFormFieldAtPoint(pdfDocument, docPage, pdfX, pdfY, tolerance);
    }

    public PdfDocument.FormField getFormFieldByIndex(int pageIndex, int annotationIndex) {
        if (disposed || pdfDocument == null) {
            return null;
        }
        warnIfMainThreadFill("getFormFieldByIndex");
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return null;
        }
        try {
            openPage(pageIndex);
        } catch (PageRenderingException e) {
            return null;
        }
        return pdfiumCore.getFormFieldByIndex(pdfDocument, docPage, annotationIndex);
    }

    public boolean setFormFieldText(int pageIndex, int annotationIndex, String text) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return false;
            }
            return pdfiumCore.setFormFieldText(pdfDocument, docPage, annotationIndex, text);
        } finally {
            renderGate.unlock();
        }
    }

    public boolean setFormFieldChecked(int pageIndex, int annotationIndex, boolean checked) {
        if (disposed || pdfDocument == null) {
            return false;
        }
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }
        fenceRenders();
        try {
            try {
                openPage(pageIndex);
            } catch (PageRenderingException e) {
                return false;
            }
            return pdfiumCore.setFormFieldChecked(pdfDocument, docPage, annotationIndex, checked);
        } finally {
            renderGate.unlock();
        }
    }

    public boolean saveAsCopy(File outputFile) throws IOException {
        if (disposed || pdfDocument == null || outputFile == null) {
            return false;
        }
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(outputFile,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY);
        fenceRenders();
        try {
            return pdfiumCore.saveAsCopy(pdfDocument, fd);
        } finally {
            try {
                fd.close();
            } finally {
                renderGate.unlock();
            }
        }
    }

    public boolean saveDecryptedCopy(File outputFile) throws IOException {
        if (disposed || pdfDocument == null || outputFile == null) {
            return false;
        }
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(outputFile,
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY);
        fenceRenders();
        try {
            return pdfiumCore.saveDecryptedCopy(pdfDocument, fd);
        } finally {
            try {
                fd.close();
            } finally {
                renderGate.unlock();
            }
        }
    }

    public void clearSearchResultsAnnot(int pageIndex) {
        if (disposed || pdfDocument == null) {
            return;
        }
        int docPage = documentPage(pageIndex);
        if (docPage >= 0) {
            fenceRenders();
            try {
                pdfiumCore.clearSearchResultsAnnot(pdfDocument, docPage);
                invalidateHighlightAnnotationCache(pageIndex);
            } finally {
                renderGate.unlock();
            }
        }
    }

    public RectF mapRectToDevice(int pageIndex, int startX, int startY, int sizeX, int sizeY,
                                  RectF rect) {
        if (disposed || pdfDocument == null) {
            return null;
        }
        int docPage = documentPage(pageIndex);
        Rect renderBounds = mapCropRenderBoundsToFullPage(pageIndex, startX, startY, sizeX, sizeY);
        RectF mapped = pdfiumCore.mapRectToDevice(pdfDocument, docPage,
                renderBounds.left, renderBounds.top, renderBounds.width(), renderBounds.height(), 0, rect);
        if (mapped == null) {
            return null;
        }
        if (cropMarginsEnabled) {
            return clipToViewport(mapped, startX, startY, sizeX, sizeY);
        }
        return mapped;
    }

    private RectF clipToViewport(RectF rect, int startX, int startY, int width, int height) {
        RectF sorted = new RectF(rect);
        sorted.sort();
        float viewportRight = startX + width;
        float viewportBottom = startY + height;
        if (sorted.right <= startX
                || sorted.left >= viewportRight
                || sorted.bottom <= startY
                || sorted.top >= viewportBottom) {
            return null;
        }

        sorted.left = Math.max(sorted.left, startX);
        sorted.top = Math.max(sorted.top, startY);
        sorted.right = Math.min(sorted.right, viewportRight);
        sorted.bottom = Math.min(sorted.bottom, viewportBottom);
        return sorted;
    }

    public void dispose() {
        disposed = true;
        fenceRenders();
        try {
            synchronized (lock) {
                if (pdfiumCore != null && pdfDocument != null) {
                    closeAllTextPages();
                    pdfiumCore.closeDocument(pdfDocument);
                }

                pdfDocument = null;
                originalUserPages = null;
                originalFullPageSizes.clear();
                originalFullPagePointSizes.clear();
                for (int i = 0; i < pageGeometries.length(); i++) {
                    pageGeometries.set(i, null);
                }
                openedPages.clear();
                textOpenedPages.clear();
                renderPageOrder.clear();
                pinnedRenderPage = -1;
                highlightGroupCache.clear();
                highlightAnnotationCache.clear();
                formFieldRectCache.clear();
                pageRowIndexes = new int[0];
                rowFirstPages.clear();
                rowOffsets.clear();
                rowLengths.clear();
                rowIndexesByOffset.clear();
            }
        } finally {
            renderGate.unlock();
        }
    }

    /**
     * Given the UserPage number, this method restrict it
     * to be sure it's an existing page. It takes care of
     * using the user defined pages if any.
     *
     * @param userPage A page number.
     * @return A restricted valid page number (example : -2 => 0)
     */
    public int determineValidPageNumberFrom(int userPage) {
        if (userPage <= 0) {
            return 0;
        }
        if (originalUserPages != null) {
            if (userPage >= originalUserPages.length) {
                return originalUserPages.length - 1;
            }
        } else {
            if (userPage >= getPagesCount()) {
                return getPagesCount() - 1;
            }
        }
        return userPage;
    }

    public int documentPage(int userPage) {
        int documentPage = userPage;
        int[] userPages = originalUserPages;
        if (userPages != null) {
            if (userPage < 0 || userPage >= userPages.length) {
                return -1;
            } else {
                documentPage = userPages[userPage];
            }
        }

        if (documentPage < 0 || userPage >= getPagesCount()) {
            return -1;
        }

        return documentPage;
    }

    static final class PageGeometry {
        final float left;
        final float bottom;
        final float right;
        final float top;
        final int rotation;

        PageGeometry(float left, float bottom, float right, float top, int rotation) {
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            this.top = top;
            this.rotation = rotation;
        }

        float frameWidth() {
            return rotation % 2 == 1 ? top - bottom : right - left;
        }

        float frameHeight() {
            return rotation % 2 == 1 ? right - left : top - bottom;
        }
    }
}
