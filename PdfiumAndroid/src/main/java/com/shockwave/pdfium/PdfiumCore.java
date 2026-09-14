package com.shockwave.pdfium;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import com.shockwave.pdfium.util.Size;
import com.shockwave.pdfium.util.SizeF;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PdfiumCore {
    private static final String TAG = PdfiumCore.class.getName();

    public static final int RENDER_STATUS_READY = 0;
    public static final int RENDER_STATUS_TO_BE_CONTINUED = 1;
    public static final int RENDER_STATUS_DONE = 2;
    public static final int RENDER_STATUS_FAILED = 3;

    public static final int RENDER_FLAG_NO_SMOOTHIMAGE = 0x2000;

    private static final int MAX_BOOKMARK_DEPTH = 64;

    public static final int MAX_PAGE_TEXT_CHARS = 1_000_000;

    private static final int FPDF_SAVE_NO_INCREMENTAL = 2;
    private static final int FPDF_SAVE_REMOVE_SECURITY = 3;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("modpng");
            System.loadLibrary("modft2");
            System.loadLibrary("modpdfium");
            System.loadLibrary("jniPdfium");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native libraries failed to load - " + e);
        }
    }

    private native long nativeOpenDocument(int fd, String password);

    private native long nativeOpenMemDocument(byte[] data, String password);

    private native void nativeCloseDocument(long docPtr);

    private native boolean nativeSaveAsCopyWithFlags(long docPtr, int fd, int flags);

    private native int nativeGetPageCount(long docPtr);

    private native long nativeLoadPage(long docPtr, int pageIndex);

    private native long[] nativeLoadPages(long docPtr, int fromIndex, int toIndex);

    private native void nativeClosePage(long docPtr, long pagePtr);

    private native int nativeGetPageWidthPixel(long pagePtr, int dpi);

    private native int nativeGetPageHeightPixel(long pagePtr, int dpi);

    private native int nativeGetPageWidthPoint(long pagePtr);

    private native int nativeGetPageHeightPoint(long pagePtr);

    //private native long nativeGetNativeWindow(Surface surface);
    //private native void nativeRenderPage(long pagePtr, long nativeWindowPtr);
    private native void nativeRenderPage(long pagePtr, Surface surface, int dpi,
                                         int startX, int startY,
                                         int drawSizeHor, int drawSizeVer,
                                         boolean renderAnnot);

    private native void nativeRenderPageBitmap(long docPtr, long pagePtr, Bitmap bitmap, int dpi,
                                               int startX, int startY,
                                               int drawSizeHor, int drawSizeVer,
                                               boolean renderAnnot);

    private native long nativeRenderChunkedStart(long docPtr, long pagePtr, Bitmap bitmap,
                                                 int startX, int startY,
                                                 int drawSizeHor, int drawSizeVer,
                                                 boolean renderAnnot, int extraFlags);

    private native int nativeRenderChunkedStatus(long ctxPtr);

    private native int nativeRenderChunkedContinue(long ctxPtr, long pagePtr);

    private native void nativeRenderChunkedClose(long ctxPtr, long docPtr, long pagePtr, Bitmap bitmap,
                                                 boolean drawForms, boolean pageAlive, boolean completed);

    private static native void nativeSetTimingLogsEnabled(boolean enabled);

    private native String nativeGetDocumentMetaText(long docPtr, String tag);

    private native Long nativeGetFirstChildBookmark(long docPtr, Long bookmarkPtr);

    private native Long nativeGetSiblingBookmark(long docPtr, long bookmarkPtr);

    private native String nativeGetBookmarkTitle(long bookmarkPtr);

    private native long nativeGetBookmarkDestIndex(long docPtr, long bookmarkPtr);

    private native Size nativeGetPageSizeByIndex(long docPtr, int pageIndex, int dpi);

    private native SizeF nativeGetPageSizePointByIndex(long docPtr, int pageIndex);

    private native float[] nativeGetPageGeometry(long pagePtr);

    private native long[] nativeGetPageLinks(long pagePtr);

    private native String nativeGetPageText(long pagePtr);

    private native Rect[] nativeGetPageTextBounds(long pagePtr, int start, int count);

    private native long nativeLoadTextPage(long pagePtr);

    private native void nativeCloseTextPage(long textPagePtr);

    private native int nativeTextCountChars(long textPagePtr);

    private native int nativeCharIndexAtPos(long textPagePtr, double x, double y,
                                            double xTolerance, double yTolerance);

    private native boolean nativeLooseCharBox(long textPagePtr, int index, float[] out4);

    private native boolean nativeTightCharBox(long textPagePtr, int index, float[] out4);

    private native int nativeCharUnicode(long textPagePtr, int index);

    private native String nativeTextRange(long textPagePtr, int start, int count);

    private native float[] nativeTextGetRects(long textPagePtr, int start, int count);

    private native double[] nativeTextCharMetrics(long textPagePtr, int start, int count);


    private native boolean nativeCreateAnnotInPage(long pagePtr, int l, int r, int t, int b, int dpi, boolean padding);

    private native boolean nativeCreateHighlightAnnotation(long docPtr, long pagePtr, int pageIndex,
                                                           float[][] rects,
                                                           int r, int g, int b, int a,
                                                           String contents, String groupKey,
                                                           String creationDate);

    private native PdfDocument.HighlightAnnotation[] nativeGetHighlightAnnotations(long pagePtr);

    private native String[] nativeGetPageFonts(long pagePtr);

    private native boolean nativeAddSignatureContent(long docPtr, long pagePtr,
                                                     float[][] strokes,
                                                     int r, int g, int b,
                                                     float strokeWidth);

    private native PdfDocument.FormField nativeGetFormFieldAtPoint(long docPtr, long pagePtr,
                                                                   float x, float y, float tolerance);

    private native PdfDocument.FormField nativeGetFormFieldByIndex(long docPtr, long pagePtr,
                                                                    int annotIndex);

    private native float[] nativeGetFormFieldRects(long docPtr, long pagePtr);

    private native boolean nativeSetFormFieldText(long docPtr, long pagePtr, int annotIndex,
                                                  String text);

    private native boolean nativeSetFormFieldChecked(long docPtr, long pagePtr, int annotIndex,
                                                     boolean checked);

    private native boolean nativeSetHighlightAnnotationColor(long pagePtr, int annotationIndex,
                                                             String groupKey, int r, int g, int b);

    private native boolean nativeSetHighlightAnnotationNote(long pagePtr, int annotationIndex,
                                                            String groupKey, String note,
                                                            String modifiedDate);

    private native boolean nativeRemoveHighlightAnnotation(long pagePtr, int annotationIndex,
                                                           String groupKey);

    private native int nativeClearSearchResultAnnot(long pagePtr, int pageIndex);

    private native Integer nativeGetDestPageIndex(long docPtr, long linkPtr);

    private native String nativeGetLinkURI(long docPtr, long linkPtr);

    private native RectF nativeGetLinkRect(long linkPtr);

    private native Point nativePageCoordsToDevice(long pagePtr, int startX, int startY, int sizeX,
                                                  int sizeY, int rotate, double pageX, double pageY);


    /* synchronize native methods */
    private static final Object lock = new Object();
    private int mCurrentDpi;

    public static int getNumFd(ParcelFileDescriptor fdObj) {
        try {
            return fdObj.getFd();
        } catch (IllegalStateException e) {
            Log.e(TAG, "getNumFd: file descriptor already closed", e);
            return -1;
        }
    }


    /** Context needed to get screen density */
    public PdfiumCore(Context ctx) {
        mCurrentDpi = ctx.getResources().getDisplayMetrics().densityDpi;
//        Log.d(TAG, "Starting PdfiumAndroid " + BuildConfig.VERSION_NAME);
    }

    /** Create new document from file */
    public PdfDocument newDocument(ParcelFileDescriptor fd) throws IOException {
        return newDocument(fd, null);
    }

    /** Create new document from file with password */
    public PdfDocument newDocument(ParcelFileDescriptor fd, String password) throws IOException {
        PdfDocument document = new PdfDocument();
        document.parcelFileDescriptor = fd;
        synchronized (lock) {
            document.mNativeDocPtr = nativeOpenDocument(getNumFd(fd), password);
        }
        return document;
    }

    /** Create new document from bytearray */
    public PdfDocument newDocument(byte[] data) throws IOException {
        return newDocument(data, null);
    }

    /** Create new document from bytearray with password */
    public PdfDocument newDocument(byte[] data, String password) throws IOException {
        PdfDocument document = new PdfDocument();
        synchronized (lock) {
            document.mNativeDocPtr = nativeOpenMemDocument(data, password);
        }
        return document;
    }

    /** Get total numer of pages in document */
    public int getPageCount(PdfDocument doc) {
        synchronized (lock) {
            if (doc.closed) {
                return 0;
            }
            return nativeGetPageCount(doc.mNativeDocPtr);
        }
    }

    public boolean saveAsCopy(PdfDocument doc, ParcelFileDescriptor fd) {
        synchronized (lock) {
            if (doc == null || fd == null || doc.closed) {
                return false;
            }
            return nativeSaveAsCopyWithFlags(doc.mNativeDocPtr, getNumFd(fd), FPDF_SAVE_NO_INCREMENTAL);
        }
    }

    public boolean saveDecryptedCopy(PdfDocument doc, ParcelFileDescriptor fd) {
        synchronized (lock) {
            if (doc == null || fd == null || doc.closed) {
                return false;
            }
            return nativeSaveAsCopyWithFlags(doc.mNativeDocPtr, getNumFd(fd), FPDF_SAVE_REMOVE_SECURITY);
        }
    }

    /** Open page and store native pointer in {@link PdfDocument} */
    public long openPage(PdfDocument doc, int pageIndex) {
        long pagePtr;
        synchronized (lock) {
            if (doc.closed) {
                return 0L;
            }
            Long existing = doc.mNativePagesPtr.get(pageIndex);
            if (existing != null) {
                return existing;
            }
            pagePtr = nativeLoadPage(doc.mNativeDocPtr, pageIndex);
            doc.mNativePagesPtr.put(pageIndex, pagePtr);
            return pagePtr;
        }

    }

    public void closePage(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.remove(pageIndex);
            if (textPagePtr != null && textPagePtr != 0L) {
                nativeCloseTextPage(textPagePtr);
            }
            Long pagePtr = doc.mNativePagesPtr.remove(pageIndex);
            if (pagePtr != null && pagePtr != 0L) {
                nativeClosePage(doc.mNativeDocPtr, pagePtr);
            }
        }
    }

    public long openTextPage(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            if (doc.closed) {
                return 0L;
            }
            Long existing = doc.mNativeTextPagesPtr.get(pageIndex);
            if (existing != null && existing != 0L) {
                return existing;
            }

            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                pagePtr = nativeLoadPage(doc.mNativeDocPtr, pageIndex);
                doc.mNativePagesPtr.put(pageIndex, pagePtr);
            }
            if (pagePtr == null || pagePtr == 0L) {
                return 0L;
            }

            long textPagePtr = nativeLoadTextPage(pagePtr);
            if (textPagePtr != 0L) {
                doc.mNativeTextPagesPtr.put(pageIndex, textPagePtr);
            }
            return textPagePtr;
        }
    }

    public void closeTextPage(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.remove(pageIndex);
            if (textPagePtr != null && textPagePtr != 0L) {
                nativeCloseTextPage(textPagePtr);
            }
        }
    }

    public void closeTextPages(PdfDocument doc) {
        synchronized (lock) {
            for (Long textPagePtr : doc.mNativeTextPagesPtr.values()) {
                if (textPagePtr != null && textPagePtr != 0L) {
                    nativeCloseTextPage(textPagePtr);
                }
            }
            doc.mNativeTextPagesPtr.clear();
        }
    }

    public boolean hasTextPage(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            return textPagePtr != null && textPagePtr != 0L;
        }
    }

    public int textCountChars(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L) {
                return 0;
            }
            return Math.max(0, nativeTextCountChars(textPagePtr));
        }
    }

    public int charIndexAtPos(PdfDocument doc, int pageIndex, double x, double y, double tolerance) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L) {
                return -1;
            }
            return nativeCharIndexAtPos(textPagePtr, x, y, tolerance, tolerance);
        }
    }

    public boolean looseCharBox(PdfDocument doc, int pageIndex, int charIndex, float[] out4) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || out4 == null || out4.length < 4) {
                return false;
            }
            return nativeLooseCharBox(textPagePtr, charIndex, out4);
        }
    }

    public boolean tightCharBox(PdfDocument doc, int pageIndex, int charIndex, float[] out4) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || out4 == null || out4.length < 4) {
                return false;
            }
            return nativeTightCharBox(textPagePtr, charIndex, out4);
        }
    }

    public int charUnicode(PdfDocument doc, int pageIndex, int charIndex) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L) {
                return 0;
            }
            return nativeCharUnicode(textPagePtr, charIndex);
        }
    }

    public String textRange(PdfDocument doc, int pageIndex, int start, int count) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || count <= 0) {
                return "";
            }
            String text = nativeTextRange(textPagePtr, start, count);
            return text == null ? "" : text;
        }
    }

    public float[] textRects(PdfDocument doc, int pageIndex, int start, int count) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || count <= 0) {
                return new float[0];
            }
            float[] rects = nativeTextGetRects(textPagePtr, start, count);
            return rects == null ? new float[0] : rects;
        }
    }

    public double[] textCharMetrics(PdfDocument doc, int pageIndex, int start, int count) {
        synchronized (lock) {
            Long textPagePtr = doc.mNativeTextPagesPtr.get(pageIndex);
            if (textPagePtr == null || textPagePtr == 0L || count <= 0) {
                return new double[0];
            }
            double[] values = nativeTextCharMetrics(textPagePtr, start, count);
            return values == null ? new double[0] : values;
        }
    }

    /** Open range of pages and store native pointers in {@link PdfDocument} */
    public long[] openPages(PdfDocument doc, int fromIndex, int toIndex) {
        long[] pagesPtr;
        synchronized (lock) {
            if (doc.closed) {
                return new long[0];
            }
            pagesPtr = nativeLoadPages(doc.mNativeDocPtr, fromIndex, toIndex);
            int pageIndex = fromIndex;
            for (long page : pagesPtr) {
                if (pageIndex > toIndex) break;
                doc.mNativePagesPtr.put(pageIndex, page);
                pageIndex++;
            }

            return pagesPtr;
        }
    }

    /**
     * Get page width in pixels. <br>
     * This method requires page to be opened.
     */
    public int getPageWidth(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageWidthPixel(pagePtr, mCurrentDpi);
            }
            return 0;
        }
    }

    /**
     * Get page height in pixels. <br>
     * This method requires page to be opened.
     */
    public int getPageHeight(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageHeightPixel(pagePtr, mCurrentDpi);
            }
            return 0;
        }
    }

    /**
     * Get page width in PostScript points (1/72th of an inch).<br>
     * This method requires page to be opened.
     */
    public int getPageWidthPoint(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageWidthPoint(pagePtr);
            }
            return 0;
        }
    }

    /**
     * Get page height in PostScript points (1/72th of an inch).<br>
     * This method requires page to be opened.
     */
    public int getPageHeightPoint(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageHeightPoint(pagePtr);
            }
            return 0;
        }
    }

    /**
     * Get size of page in pixels.<br>
     * This method does not require given page to be opened.
     */
    public Size getPageSize(PdfDocument doc, int index) {
        synchronized (lock) {
            if (doc.closed) {
                return new Size(0, 0);
            }
            return nativeGetPageSizeByIndex(doc.mNativeDocPtr, index, mCurrentDpi);
        }
    }

    /**
     * Get size of page in PDF points. This method does not require given page to be opened.
     */
    public SizeF getPageSizePoint(PdfDocument doc, int index) {
        synchronized (lock) {
            if (doc.closed) {
                return new SizeF(0, 0);
            }
            return nativeGetPageSizePointByIndex(doc.mNativeDocPtr, index);
        }
    }

    public float[] getPageGeometry(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            if (doc.closed) {
                return new float[0];
            }
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null) {
                return new float[0];
            }
            float[] geometry = nativeGetPageGeometry(pagePtr);
            return geometry == null ? new float[0] : geometry;
        }
    }

    /**
     * Render page fragment on {@link Surface}.<br>
     * Page must be opened before rendering.
     */
    public void renderPage(PdfDocument doc, Surface surface, int pageIndex,
                           int startX, int startY, int drawSizeX, int drawSizeY) {
        renderPage(doc, surface, pageIndex, startX, startY, drawSizeX, drawSizeY, false);
    }

    /**
     * Render page fragment on {@link Surface}. This method allows to render annotations.<br>
     * Page must be opened before rendering.
     */
    public void renderPage(PdfDocument doc, Surface surface, int pageIndex,
                           int startX, int startY, int drawSizeX, int drawSizeY,
                           boolean renderAnnot) {
        synchronized (lock) {
            try {
                Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
                if (pagePtr == null || pagePtr == 0L) {
                    Log.e(TAG, "renderPage: page " + pageIndex + " is not open");
                    return;
                }
                nativeRenderPage(pagePtr, surface, mCurrentDpi,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot);
            } catch (Exception e) {
                Log.e(TAG, "renderPage: exception thrown from native", e);
            }
        }
    }

    /**
     * Render page fragment on {@link Bitmap}.<br>
     * Page must be opened before rendering.
     * <p>
     * Supported bitmap configurations:
     * <ul>
     * <li>ARGB_8888 - best quality, high memory usage, higher possibility of OutOfMemoryError
     * <li>RGB_565 - little worse quality, twice less memory usage
     * </ul>
     */
    public void renderPageBitmap(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                 int startX, int startY, int drawSizeX, int drawSizeY) {
        renderPageBitmap(doc, bitmap, pageIndex, startX, startY, drawSizeX, drawSizeY, false);
    }

    /**
     * Render page fragment on {@link Bitmap}. This method allows to render annotations.<br>
     * Page must be opened before rendering.
     * <p>
     * For more info see {@link PdfiumCore#renderPageBitmap(PdfDocument, Bitmap, int, int, int, int, int)}
     */
    public void renderPageBitmap(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                 int startX, int startY, int drawSizeX, int drawSizeY,
                                 boolean renderAnnot) {
        synchronized (lock) {
            try {
                Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
                if (pagePtr == null || pagePtr == 0L) {
                    Log.e(TAG, "renderPageBitmap: page " + pageIndex + " is not open");
                    return;
                }
                nativeRenderPageBitmap(doc.mNativeDocPtr, pagePtr, bitmap, mCurrentDpi,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmap: exception thrown from native", e);
            }
        }
    }

    public long renderPageBitmapChunkedStart(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                             int startX, int startY, int drawSizeX, int drawSizeY,
                                             boolean renderAnnot, int extraFlags) {
        synchronized (lock) {
            if (doc == null || doc.closed) {
                return 0L;
            }
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                Log.e(TAG, "renderPageBitmapChunkedStart: page " + pageIndex + " is not open");
                return 0L;
            }
            try {
                return nativeRenderChunkedStart(doc.mNativeDocPtr, pagePtr, bitmap,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot, extraFlags);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmapChunkedStart: exception thrown from native", e);
                return 0L;
            }
        }
    }

    public int renderPageBitmapChunkedStatus(long ctxPtr) {
        synchronized (lock) {
            return nativeRenderChunkedStatus(ctxPtr);
        }
    }

    public int renderPageBitmapChunkedContinue(PdfDocument doc, long ctxPtr, int pageIndex) {
        synchronized (lock) {
            if (doc == null || doc.closed) {
                return RENDER_STATUS_FAILED;
            }
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                return RENDER_STATUS_FAILED;
            }
            try {
                return nativeRenderChunkedContinue(ctxPtr, pagePtr);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmapChunkedContinue: exception thrown from native", e);
                return RENDER_STATUS_FAILED;
            }
        }
    }

    public void renderPageBitmapChunkedClose(PdfDocument doc, long ctxPtr, int pageIndex, Bitmap bitmap,
                                             boolean drawForms, boolean pageAlive, boolean completed) {
        synchronized (lock) {
            long docPtr = doc == null ? 0L : doc.mNativeDocPtr;
            Long pagePtr = doc == null ? null : doc.mNativePagesPtr.get(pageIndex);
            long page = pagePtr == null ? 0L : pagePtr;
            try {
                nativeRenderChunkedClose(ctxPtr, docPtr, page, bitmap,
                        drawForms, pageAlive && page != 0L, completed);
            } catch (Exception e) {
                Log.e(TAG, "renderPageBitmapChunkedClose: exception thrown from native", e);
            }
        }
    }

    public static void setTimingLogsEnabled(boolean enabled) {
        nativeSetTimingLogsEnabled(enabled);
    }

    /** Release native resources and opened file */
    public void closeDocument(PdfDocument doc) {
        synchronized (lock) {
            if (doc.closed) {
                return;
            }
            doc.closed = true;
            closeTextPages(doc);
            for (Integer index : doc.mNativePagesPtr.keySet()) {
                Long pagePtr = doc.mNativePagesPtr.get(index);
                if (pagePtr != null && pagePtr != 0L) {
                    nativeClosePage(doc.mNativeDocPtr, pagePtr);
                }
            }
            doc.mNativePagesPtr.clear();

            nativeCloseDocument(doc.mNativeDocPtr);

            if (doc.parcelFileDescriptor != null) { //if document was loaded from file
                try {
                    doc.parcelFileDescriptor.close();
                } catch (IOException e) {
                /* ignore */
                }
                doc.parcelFileDescriptor = null;
            }
        }
    }

    /** Get metadata for given document */
    public PdfDocument.Meta getDocumentMeta(PdfDocument doc) {
        synchronized (lock) {
            PdfDocument.Meta meta = new PdfDocument.Meta();
            if (doc.closed) {
                return meta;
            }
            meta.title = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Title");
            meta.author = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Author");
            meta.subject = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Subject");
            meta.keywords = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Keywords");
            meta.creator = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Creator");
            meta.producer = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Producer");
            meta.creationDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "CreationDate");
            meta.modDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "ModDate");
            meta.totalPages = getPageCount(doc);
            return meta;
        }
    }

    /** Get table of contents (bookmarks) for given document */
    public List<PdfDocument.Bookmark> getTableOfContents(PdfDocument doc) {
        synchronized (lock) {
            List<PdfDocument.Bookmark> topLevel = new ArrayList<>();
            if (doc.closed) {
                return topLevel;
            }
            Long first = nativeGetFirstChildBookmark(doc.mNativeDocPtr, null);
            if (first != null) {
                recursiveGetBookmark(topLevel, doc, first, new HashSet<Long>(), 0);
            }
            return topLevel;
        }
    }

    private void recursiveGetBookmark(List<PdfDocument.Bookmark> tree, PdfDocument doc, long bookmarkPtr,
                                      Set<Long> visited, int depth) {
        if (depth > MAX_BOOKMARK_DEPTH) {
            return;
        }
        Long currentPtr = bookmarkPtr;
        while (currentPtr != null && visited.add(currentPtr)) {
            PdfDocument.Bookmark bookmark = new PdfDocument.Bookmark();
            bookmark.mNativePtr = currentPtr;
            bookmark.title = nativeGetBookmarkTitle(currentPtr);
            bookmark.pageIdx = nativeGetBookmarkDestIndex(doc.mNativeDocPtr, currentPtr);
            tree.add(bookmark);

            Long child = nativeGetFirstChildBookmark(doc.mNativeDocPtr, currentPtr);
            if (child != null) {
                recursiveGetBookmark(bookmark.getChildren(), doc, child, visited, depth + 1);
            }

            currentPtr = nativeGetSiblingBookmark(doc.mNativeDocPtr, currentPtr);
        }
    }

    public String getPageText(PdfDocument doc, int pageIndex) {
        String text = extractPageText(doc, pageIndex);
        return text.isEmpty() ? text : normalizeExtractedText(text);
    }

    public String getPageRawText(PdfDocument doc, int pageIndex) {
        return extractPageText(doc, pageIndex);
    }

    private String extractPageText(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return "";
            }
            boolean openedHere = !hasTextPage(doc, pageIndex);
            long textPagePtr = openTextPage(doc, pageIndex);
            if (textPagePtr == 0L) {
                return "";
            }
            try {
                int charCount = textCountChars(doc, pageIndex);
                if (charCount > MAX_PAGE_TEXT_CHARS) {
                    throw new PageTextTooLargeException(charCount, MAX_PAGE_TEXT_CHARS);
                }
                if (charCount <= 0) {
                    return "";
                }
                String text = nativeTextRange(textPagePtr, 0, charCount);
                return text == null ? "" : text;
            }
            finally {
                if (openedHere) {
                    closeTextPage(doc, pageIndex);
                }
            }
        }
    }

    public String normalizeExtractedText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(mapPresentationFormMarks(text), Normalizer.Form.NFKC)
                .replace("\uFFFE\r\n", "")
                .replace("\uFFFE\n", "")
                .replace("\uFFFE\r", "")
                .replace("\uFFFE", "")
                .replace("\u200B", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    public static String mapPresentationFormMarks(String text) {
        char[] chars = null;
        for (int i = 0; i < text.length(); i++) {
            char mapped = mapPresentationFormMark(text.charAt(i));
            if (mapped != text.charAt(i)) {
                if (chars == null) {
                    chars = text.toCharArray();
                }
                chars[i] = mapped;
            }
        }
        return chars == null ? text : new String(chars);
    }

    private static char mapPresentationFormMark(char c) {
        switch (c) {
            case '\uFE70':  // fathatan isolated form
            case '\uFE71':  // tatweel with fathatan above
                return '\u064B';  // fathatan
            case '\uFE72':  // dammatan isolated form
                return '\u064C';  // dammatan
            case '\uFE74':  // kasratan isolated form
                return '\u064D';  // kasratan
            case '\uFE76':  // fatha isolated form
            case '\uFE77':  // fatha medial form
                return '\u064E';  // fatha
            case '\uFE78':  // damma isolated form
            case '\uFE79':  // damma medial form
                return '\u064F';  // damma
            case '\uFE7A':  // kasra isolated form
            case '\uFE7B':  // kasra medial form
                return '\u0650';  // kasra
            case '\uFE7C':  // shadda isolated form
            case '\uFE7D':  // shadda medial form
                return '\u0651';  // shadda
            case '\uFE7E':  // sukun isolated form
            case '\uFE7F':  // sukun medial form
                return '\u0652';  // sukun
            case '\uFE73':  // tail fragment, a rendering artifact
                return '\u200B';  // zero width space, stripped by the normalizers
            default:
                return c;
        }
    }

    public Map<Integer, String> getPagesText(PdfDocument doc, int start, int end) {
        synchronized (lock) {
            Map<Integer, String> pagesText = new HashMap<>();
            for (int i = start; i <= end; ++i) {
                Long pagePtr = doc.mNativePagesPtr.get(i);
                if (pagePtr == null || pagePtr == 0L) {
                    pagesText.put(i, "");
                    continue;
                }
                pagesText.put(i, normalizeExtractedText(nativeGetPageText(pagePtr)));
            }
            return pagesText;
        }
    }

    public Rect[] createHighlightText(PdfDocument doc, int pageIndex, int start, int end, boolean padding) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || nativePagePtr == 0L) {
                return new Rect[0];
            }
            Rect[] rects = nativeGetPageTextBounds(nativePagePtr, start, end);
            Log.d(TAG, "createHighlightText: rects.length: " + rects.length);
            for (Rect rect : rects) {
                nativeCreateAnnotInPage(nativePagePtr, rect.left, rect.right, rect.top, rect.bottom, mCurrentDpi, padding);
            }
            return rects;
        }
    }

    public boolean createHighlightAnnotation(PdfDocument doc, int pageIndex, List<RectF> rects,
                                              int color, String contents, String groupKey,
                                              String creationDate) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || rects == null || rects.isEmpty()) {
                return false;
            }

            float[][] nativeRects = new float[rects.size()][4];
            for (int i = 0; i < rects.size(); i++) {
                RectF rect = rects.get(i);
                nativeRects[i][0] = rect.left;
                nativeRects[i][1] = rect.top;
                nativeRects[i][2] = rect.right;
                nativeRects[i][3] = rect.bottom;
            }
            return nativeCreateHighlightAnnotation(doc.mNativeDocPtr, nativePagePtr, pageIndex, nativeRects,
                    Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color),
                    contents == null ? "" : contents, groupKey == null ? "" : groupKey, creationDate);
        }
    }

    public boolean addSignatureContent(PdfDocument doc, int pageIndex,
                                       float[][] strokes, int color, float strokeWidthPts) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || strokes == null || strokes.length == 0) {
                return false;
            }
            return nativeAddSignatureContent(doc.mNativeDocPtr, nativePagePtr, strokes,
                    Color.red(color), Color.green(color), Color.blue(color), strokeWidthPts);
        }
    }

    public List<PdfDocument.HighlightAnnotation> getHighlightAnnotations(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return new ArrayList<>();
            }
            PdfDocument.HighlightAnnotation[] annotations = nativeGetHighlightAnnotations(nativePagePtr);
            List<PdfDocument.HighlightAnnotation> result = new ArrayList<>();
            if (annotations != null) {
                for (PdfDocument.HighlightAnnotation annotation : annotations) {
                    if (annotation != null) {
                        result.add(annotation);
                    }
                }
            }
            return result;
        }
    }

    public List<PdfDocument.FontInfo> getPageFonts(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            boolean wasOpen = doc.mNativePagesPtr.get(pageIndex) != null;
            long pagePtr = openPage(doc, pageIndex);
            List<PdfDocument.FontInfo> result = new ArrayList<>();
            if (pagePtr == 0L) {
                return result;
            }
            try {
                String[] entries = nativeGetPageFonts(pagePtr);
                if (entries != null) {
                    for (String entry : entries) {
                        if (entry == null || entry.isEmpty()) {
                            continue;
                        }
                        int separator = entry.lastIndexOf('\t');
                        if (separator <= 0) {
                            continue;
                        }
                        String name = entry.substring(0, separator);
                        boolean embedded = "1".equals(entry.substring(separator + 1));
                        result.add(new PdfDocument.FontInfo(name, embedded));
                    }
                }
            } finally {
                if (!wasOpen) {
                    closePage(doc, pageIndex);
                }
            }
            return result;
        }
    }

    public boolean setHighlightAnnotationColor(PdfDocument doc, int pageIndex, int annotationIndex,
                                               String groupKey, int color) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetHighlightAnnotationColor(nativePagePtr, annotationIndex, groupKey,
                    Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    public boolean setHighlightAnnotationNote(PdfDocument doc, int pageIndex, int annotationIndex,
                                              String groupKey, String note, String modifiedDate) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetHighlightAnnotationNote(nativePagePtr, annotationIndex, groupKey,
                    note == null ? "" : note, modifiedDate);
        }
    }

    public boolean removeHighlightAnnotation(PdfDocument doc, int pageIndex, int annotationIndex,
                                             String groupKey) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeRemoveHighlightAnnotation(nativePagePtr, annotationIndex, groupKey);
        }
    }

    public void clearSearchResultsAnnot(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null || nativePagePtr == 0L) {
                return;
            }
            nativeClearSearchResultAnnot(nativePagePtr, pageIndex);
        }
    }

    public PdfDocument.FormField getFormFieldAtPoint(PdfDocument doc, int pageIndex,
                                                     float x, float y, float tolerance) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return null;
            }
            return nativeGetFormFieldAtPoint(doc.mNativeDocPtr, nativePagePtr, x, y, tolerance);
        }
    }

    public PdfDocument.FormField getFormFieldByIndex(PdfDocument doc, int pageIndex,
                                                     int annotationIndex) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return null;
            }
            return nativeGetFormFieldByIndex(doc.mNativeDocPtr, nativePagePtr, annotationIndex);
        }
    }

    public float[] getFormFieldRects(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return new float[0];
            }
            float[] rects = nativeGetFormFieldRects(doc.mNativeDocPtr, nativePagePtr);
            return rects == null ? new float[0] : rects;
        }
    }

    public boolean setFormFieldText(PdfDocument doc, int pageIndex, int annotationIndex, String text) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetFormFieldText(doc.mNativeDocPtr, nativePagePtr, annotationIndex,
                    text == null ? "" : text);
        }
    }

    public boolean setFormFieldChecked(PdfDocument doc, int pageIndex, int annotationIndex, boolean checked) {
        synchronized (lock) {
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return false;
            }
            return nativeSetFormFieldChecked(doc.mNativeDocPtr, nativePagePtr, annotationIndex, checked);
        }
    }

    /** Get all links from given page */
    public List<PdfDocument.Link> getPageLinks(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            List<PdfDocument.Link> links = new ArrayList<>();
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return links;
            }
            long[] linkPtrs = nativeGetPageLinks(nativePagePtr);
            for (long linkPtr : linkPtrs) {
                Integer index = nativeGetDestPageIndex(doc.mNativeDocPtr, linkPtr);
                String uri = nativeGetLinkURI(doc.mNativeDocPtr, linkPtr);

                RectF rect = nativeGetLinkRect(linkPtr);
                if (rect != null && (index != null || uri != null)) {
                    links.add(new PdfDocument.Link(rect, index, uri));
                }

            }
            return links;
        }
    }

    /**
     * Map page coordinates to device screen coordinates
     *
     * @param doc       pdf document
     * @param pageIndex index of page
     * @param startX    left pixel position of the display area in device coordinates
     * @param startY    top pixel position of the display area in device coordinates
     * @param sizeX     horizontal size (in pixels) for displaying the page
     * @param sizeY     vertical size (in pixels) for displaying the page
     * @param rotate    page orientation: 0 (normal), 1 (rotated 90 degrees clockwise),
     *                  2 (rotated 180 degrees), 3 (rotated 90 degrees counter-clockwise)
     * @param pageX     X value in page coordinates
     * @param pageY     Y value in page coordinate
     * @return mapped coordinates
     */
    public Point mapPageCoordsToDevice(PdfDocument doc, int pageIndex, int startX, int startY, int sizeX,
                                       int sizeY, int rotate, double pageX, double pageY) {
        synchronized (lock) {
            Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (pagePtr == null || pagePtr == 0L) {
                return null;
            }
            return nativePageCoordsToDevice(pagePtr, startX, startY, sizeX, sizeY, rotate, pageX, pageY);
        }
    }

    /**
     * @return mapped coordinates
     * @see PdfiumCore#mapPageCoordsToDevice(PdfDocument, int, int, int, int, int, int, double, double)
     */
    public RectF mapRectToDevice(PdfDocument doc, int pageIndex, int startX, int startY, int sizeX,
                                 int sizeY, int rotate, RectF coords) {

        Point leftTop = mapPageCoordsToDevice(doc, pageIndex, startX, startY, sizeX, sizeY, rotate,
                coords.left, coords.top);
        Point rightBottom = mapPageCoordsToDevice(doc, pageIndex, startX, startY, sizeX, sizeY, rotate,
                coords.right, coords.bottom);
        if (leftTop == null || rightBottom == null) {
            return null;
        }
        return new RectF(leftTop.x, leftTop.y, rightBottom.x, rightBottom.y);
    }
}
