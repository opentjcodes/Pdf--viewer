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

import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MAXIMUM_ZOOM;
import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MINIMUM_ZOOM;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.RelativeLayout;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler;
import com.github.barteksc.pdfviewer.link.LinkHandler;
import com.github.barteksc.pdfviewer.listener.Callbacks;
import com.github.barteksc.pdfviewer.listener.OnDocumentInteractionListener;
import com.github.barteksc.pdfviewer.listener.OnDrawListener;
import com.github.barteksc.pdfviewer.listener.OnErrorListener;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnLongPressListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener;
import com.github.barteksc.pdfviewer.listener.OnPageScrollListener;
import com.github.barteksc.pdfviewer.listener.OnRenderListener;
import com.github.barteksc.pdfviewer.listener.OnTapListener;
import com.github.barteksc.pdfviewer.listener.OnTextSelectionChangeListener;
import com.github.barteksc.pdfviewer.model.CropBounds;
import com.github.barteksc.pdfviewer.model.CropMargins;
import com.github.barteksc.pdfviewer.model.LinkTapEvent;
import com.github.barteksc.pdfviewer.model.PagePart;
import com.github.barteksc.pdfviewer.preview.GenerationSource;
import com.github.barteksc.pdfviewer.preview.PreviewBitmapAdapter;
import com.github.barteksc.pdfviewer.preview.PreviewBitmapPool;
import com.github.barteksc.pdfviewer.preview.PreviewCodec;
import com.github.barteksc.pdfviewer.preview.PreviewStore;
import com.github.barteksc.pdfviewer.preview.PreviewSweepCursor;
import com.github.barteksc.pdfviewer.preview.TagSource;
import com.github.barteksc.pdfviewer.preview.TransientPageFilter;
import com.github.barteksc.pdfviewer.scroll.ScrollHandle;
import com.github.barteksc.pdfviewer.source.AssetSource;
import com.github.barteksc.pdfviewer.source.ByteArraySource;
import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.github.barteksc.pdfviewer.source.FileSource;
import com.github.barteksc.pdfviewer.source.InputStreamSource;
import com.github.barteksc.pdfviewer.source.UriSource;
import com.github.barteksc.pdfviewer.util.Constants;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.github.barteksc.pdfviewer.util.MathUtils;
import com.github.barteksc.pdfviewer.util.SnapEdge;
import com.github.barteksc.pdfviewer.util.Util;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;
import com.shockwave.pdfium.util.Size;
import com.shockwave.pdfium.util.SizeF;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * It supports animations, zoom, cache, and swipe.
 * <p>
 * To fully understand this class you must know its principles :
 * - The PDF document is seen as if we always want to draw all the pages.
 * - The thing is that we only draw the visible parts.
 * - All parts are the same size, this is because we can't interrupt a native page rendering,
 * so we need these renderings to be as fast as possible, and be able to interrupt them
 * as soon as we can.
 * - The parts are loaded when the current offset or the current zoom level changes
 * <p>
 * Important :
 * - DocumentPage = A page of the PDF document.
 * - UserPage = A page as defined by the user.
 * By default, they're the same. But the user can change the pages order
 * using {@link #load(DocumentSource, String, int[])}. In this
 * particular case, a userPage of 5 can refer to a documentPage of 17.
 */
public class PDFView extends RelativeLayout {

    private static final String TAG = PDFView.class.getSimpleName();

    public static final float DEFAULT_MAX_SCALE = 3.0f;
    public static final float DEFAULT_MID_SCALE = 1.75f;
    public static final float DEFAULT_MIN_SCALE = 1.0f;

    public static final float NORMAL_SCALE = 1.0f;

    private static final float HIGHLIGHT_HIT_TOLERANCE = 2.5f;
    private static final float HIGHLIGHT_MATCH_TOLERANCE = 1.5f;

    private static final int FORM_FIELD_FILL_COLOR = 0x282196F3; // 16% Material blue tint

    private static final int FORM_FIELD_STROKE_COLOR = 0x662196F3; // 40% Material blue hairline

    private static final float FORM_FIELD_TOUCH_TOLERANCE_DP = 12f;

    private static final long WHEEL_SETTLE_DELAY_MS = 250;

    public static class ViewState {

        public final float zoom;
        public final int pageIndex;
        public final boolean swipeVertical;
        public final boolean horizontalReadingDirectionRtl;
        public final float relativeCrossAxisCenter;
        public final float pageCenterOffsetRatio;
        public final int pagesPerRow;
        public final boolean firstPageAlone;

        public ViewState(float zoom, int pageIndex, boolean swipeVertical, boolean horizontalReadingDirectionRtl,
                         float relativeCrossAxisCenter, float pageCenterOffsetRatio,
                         int pagesPerRow, boolean firstPageAlone) {
            this.zoom = zoom;
            this.pageIndex = pageIndex;
            this.swipeVertical = swipeVertical;
            this.horizontalReadingDirectionRtl = horizontalReadingDirectionRtl;
            this.relativeCrossAxisCenter = relativeCrossAxisCenter;
            this.pageCenterOffsetRatio = pageCenterOffsetRatio;
            this.pagesPerRow = pagesPerRow;
            this.firstPageAlone = firstPageAlone;
        }
    }

    public static final class HighlightRequest {
        public final int pageIndex;
        public final List<RectF> pdfRects;
        public final String selectedText;

        public HighlightRequest(int pageIndex, List<RectF> pdfRects, String selectedText) {
            List<RectF> safeRects = pdfRects == null ? Collections.<RectF>emptyList() : pdfRects;
            this.pageIndex = pageIndex;
            this.pdfRects = Collections.unmodifiableList(new ArrayList<>(safeRects));
            this.selectedText = selectedText == null ? "" : selectedText;
        }
    }

    public static final class FormField {
        public final int pageIndex;
        public final int annotationIndex;
        public final int type;
        public final String name;
        public final String alternateName;
        public final String value;
        public final boolean checked;
        public final boolean readOnly;
        public final boolean multiline;

        public FormField(int pageIndex, PdfDocument.FormField field) {
            this.pageIndex = pageIndex;
            this.annotationIndex = field.getAnnotationIndex();
            this.type = field.getType();
            this.name = field.getName();
            this.alternateName = field.getAlternateName();
            this.value = field.getValue();
            this.checked = field.isChecked();
            this.readOnly = field.isReadOnly();
            this.multiline = field.isMultiline();
        }
    }

    public static final class HighlightAnnotation {
        public final int pageIndex;
        public final int annotationIndex;
        public final String groupKey;
        public final RectF viewBounds;
        public final RectF pdfBounds;
        public final String quote;
        public final String note;

        public HighlightAnnotation(int pageIndex, int annotationIndex, String groupKey,
                                   RectF viewBounds, String quote) {
            this(pageIndex, annotationIndex, groupKey, viewBounds, null, quote, null);
        }

        public HighlightAnnotation(int pageIndex, int annotationIndex, String groupKey,
                                   RectF viewBounds, RectF pdfBounds, String quote) {
            this(pageIndex, annotationIndex, groupKey, viewBounds, pdfBounds, quote, null);
        }

        public HighlightAnnotation(int pageIndex, int annotationIndex, String groupKey,
                                   RectF viewBounds, RectF pdfBounds, String quote, String note) {
            this.pageIndex = pageIndex;
            this.annotationIndex = annotationIndex;
            this.groupKey = groupKey == null ? "" : groupKey;
            this.viewBounds = viewBounds == null ? null : new RectF(viewBounds);
            this.pdfBounds = pdfBounds == null ? null : new RectF(pdfBounds);
            this.quote = quote == null ? "" : quote;
            this.note = note == null ? "" : note;
        }
    }

    private float minZoom = DEFAULT_MIN_SCALE;
    private float midZoom = DEFAULT_MID_SCALE;
    private float maxZoom = DEFAULT_MAX_SCALE;

    public void clearCache() {
        cacheManager.recycle();
    }

    public void reloadPages() {
        clearCache();
        loadPages();
    }

    public void refreshPage(int pageIndex) {
        pageContentChanged(pageIndex);
    }

    public void pageContentChanged(int page) {
        if (pdfFile == null) {
            return;
        }
        invalidatePageContent(page);
        if (renderScheduler != null) {
            renderScheduler.cancelPage(page);
        }
        loadPages();
    }

    private void invalidatePageContent(int page) {
        bumpPageGeneration(page);
        cacheManager.invalidatePageParts(page);
        pdfFile.invalidateHighlightAnnotationCache(page);
        pdfFile.invalidateFormFieldRectCache(page);
        PreviewStore<Bitmap> store = previewStore;
        if (store != null) {
            store.invalidatePage(page);
        }
        sweepCursor.reset(page);
        requestPrewarm(page);
    }

    public void overlayContentChanged(int page) {
        if (pdfFile == null) {
            return;
        }
        requestPrewarm(page);
        invalidate();
    }

    private void bumpPageGeneration(int page) {
        if (pageGenerations != null && page >= 0 && page < pageGenerations.length()) {
            pageGenerations.incrementAndGet(page);
        }
    }

    public int getPageGeneration(int page) {
        if (pageGenerations != null && page >= 0 && page < pageGenerations.length()) {
            return pageGenerations.get(page);
        }
        return 0;
    }

    int getCurrentRenderEpoch() {
        return currentRenderEpoch;
    }

    void requestPrewarm(int page) {
        if (renderScheduler == null || page < 0) {
            return;
        }
        synchronized (prewarmPending) {
            if (!prewarmPending.add(page)) {
                return;
            }
        }
        renderScheduler.submitPrewarm(page);
    }

    public void setRenderInteractionActive(boolean active) {
        if (renderScheduler != null) {
            renderScheduler.setInteractionActive(active);
        }
    }

    void onPrewarmStarted(int page) {
        synchronized (prewarmPending) {
            prewarmPending.remove(page);
        }
    }

    void onPrewarmComplete(int page) {
        synchronized (prewarmPending) {
            prewarmPending.remove(page);
        }
        postInvalidate();
    }

    void requestPreview(int page) {
        final PreviewStore<Bitmap> store = previewStore;
        if (store == null || pdfFile == null || page < 0 || page >= pdfFile.getPagesCount()) {
            return;
        }
        synchronized (previewPending) {
            if (!previewPending.add(page)) {
                return;
            }
        }
        final int requested = page;
        store.requestDecode(page, new Runnable() {
            @Override
            public void run() {
                clearPreviewPending(requested);
                postInvalidate();
            }
        }, new Runnable() {
            @Override
            public void run() {
                submitPreviewRender(requested);
            }
        });
    }

    private void submitPreviewRender(int page) {
        RenderScheduler scheduler = renderScheduler;
        RenderTask task = buildPreviewTask(page, RenderTask.P0);
        if (scheduler == null || task == null) {
            clearPreviewPending(page);
            return;
        }
        scheduler.submit(task);
    }

    private RenderTask buildPreviewTask(int page, int priorityClass) {
        if (pdfFile == null || page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }
        float width = previewBucket;
        float height = width * pdfFile.fullPageAspectRatio(page);
        if (width <= 0f || height <= 0f) {
            return null;
        }
        return RenderTask.preview(page, width, height, annotationRendering, priorityClass);
    }

    private void clearPreviewPending(int page) {
        synchronized (previewPending) {
            previewPending.remove(page);
        }
    }

    Bitmap peekPreview(int page) {
        PreviewStore<Bitmap> store = previewStore;
        return store == null ? null : store.peek(page);
    }

    void onPreviewStarted(int page) {
        clearPreviewPending(page);
    }

    boolean onPreviewRendered(int page, Bitmap bitmap, int generation) {
        PreviewStore<Bitmap> store = previewStore;
        if (store != null && generation == getPageGeneration(page)) {
            store.put(page, bitmap, generation);
            if (generation != getPageGeneration(page)) {
                store.invalidatePage(page);
            }
        } else if (bitmap != null) {
            synchronized (bitmap) {
                bitmap.recycle();
            }
        }
        clearPreviewPending(page);
        boolean repaint = isPageInPreviewRange(page);
        if (repaint) {
            postInvalidate();
        }
        return repaint;
    }

    public void setPreviewTagSource(TagSource tagSource) {
        this.previewTagSource = tagSource;
    }

    public void setRenderFlinging(boolean flinging) {
        if (renderScheduler != null) {
            renderScheduler.setFlinging(flinging);
        }
    }

    public void attachPreviewDisk(File dir, String docKey) {
        PreviewStore<Bitmap> store = previewStore;
        if (store == null || dir == null || docKey == null) {
            return;
        }
        store.attachDisk(dir, docKey);
        sweepCursor.resetAll();
    }

    private boolean isPreviewLowTier() {
        Boolean cached = previewLowTierCache;
        if (cached != null) {
            return cached;
        }
        boolean low = computePreviewLowTier(getContext());
        previewLowTierCache = low;
        return low;
    }

    private static boolean computePreviewLowTier(Context context) {
        if (context == null) {
            return false;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            if (am.isLowRamDevice() || am.getMemoryClass() <= 128) {
                return true;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.totalMem <= (7L * 1024L * 1024L * 1024L) / 2L;
        } catch (RuntimeException e) {
            return false;
        }
    }

    int previewExtraFlags() {
        return isPreviewLowTier() ? PdfiumCore.RENDER_FLAG_NO_SMOOTHIMAGE : 0;
    }

    private void initPreviewStore(PdfFile file) {
        previewBucket = PreviewBucketPolicy.bucketFor(file.getMaxPageWidth(), isPreviewLowTier());

        previewEncoder = Executors.newSingleThreadExecutor(previewThreadFactory("pdf-preview-encode"));
        previewDecoder = Executors.newFixedThreadPool(2, previewThreadFactory("pdf-preview-decode"));

        AndroidPreviewBitmaps bitmaps = new AndroidPreviewBitmaps(previewBucket);
        GenerationSource generationSource = new GenerationSource() {
            @Override
            public int generationOf(int page) {
                return getPageGeneration(page);
            }
        };
        TagSource tagSource = new TagSource() {
            @Override
            public int tagOf(int page) {
                TagSource source = previewTagSource;
                return source == null ? 0 : source.tagOf(page);
            }
        };
        TransientPageFilter transientFilter = new TransientPageFilter() {
            @Override
            public boolean isTransient(int page) {
                return isSearchMarkerPage(page);
            }
        };
        long memoryBudget = Runtime.getRuntime().maxMemory() / 8;
        long diskBudget = 96L * 1024L * 1024L;
        previewStore = new PreviewStore<>(bitmaps, bitmaps, generationSource, tagSource, transientFilter,
                previewEncoder, previewDecoder, memoryBudget, diskBudget, 4);
        previewStore.setBucket(previewBucket);
        bitmaps.attachPool(previewStore.getPool());

        sweepCursor.ensureCapacity(file.getPagesCount());
        sweepCursor.resetAll();
        renderScheduler.setIdleProducer(createSweepProducer());
        registerPreviewTrimCallbacks();
    }

    private RenderQueue.IdleProducer createSweepProducer() {
        final PreviewSweepCursor.Coverage coverage = new PreviewSweepCursor.Coverage() {
            @Override
            public boolean covered(int page) {
                PreviewStore<Bitmap> store = previewStore;
                if (store == null) {
                    return true;
                }
                return store.peek(page) != null || store.hasOnDisk(page);
            }

            @Override
            public boolean pending(int page) {
                synchronized (previewPending) {
                    return previewPending.contains(page);
                }
            }
        };
        return new RenderQueue.IdleProducer() {
            @Override
            public RenderTask produce() {
                PdfFile file = pdfFile;
                if (file == null || previewStore == null) {
                    return null;
                }
                int count = file.getPagesCount();
                sweepCursor.ensureCapacity(count);
                int page = sweepCursor.nextPage(currentPage, lastScrollDir, coverage);
                if (page < 0) {
                    return null;
                }
                return buildPreviewTask(page, RenderTask.P2);
            }
        };
    }

    private void registerPreviewTrimCallbacks() {
        if (previewTrimCallbacks != null) {
            return;
        }
        previewTrimCallbacks = new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {
                PreviewStore<Bitmap> store = previewStore;
                if (store != null && (level >= TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN)) {
                    store.dropMemory();
                }
            }

            @Override
            public void onConfigurationChanged(Configuration newConfig) {
            }

            @Override
            public void onLowMemory() {
            }
        };
        getContext().registerComponentCallbacks(previewTrimCallbacks);
    }

    private static ThreadFactory previewThreadFactory(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };
    }

    boolean isSearchMarkerPage(int page) {
        synchronized (searchMarkerPages) {
            return searchMarkerPages.contains(page);
        }
    }

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    enum ScrollDir {
        NONE, START, END
    }

    private ScrollDir scrollDir = ScrollDir.NONE;

    /**
     * Rendered parts go to the cache manager
     */
    CacheManager cacheManager;

    /**
     * Animation manager manage all offset and zoom animation
     */
    private AnimationManager animationManager;

    /**
     * Drag manager manage all touch events
     */
    private DragPinchManager dragPinchManager;

    private TextSelectionManager textSelectionManager;

    private StampPlacementManager stampPlacementManager;

    private Runnable onStampPlacementDiscardListener;

    PdfFile pdfFile;

    /**
     * The index of the current sequence
     */
    private int currentPage;

    private boolean pageTrackingSuppressed = false;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentXOffset = 0;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentYOffset = 0;

    /**
     * The zoom level, always >= 1
     */
    private float zoom = 1f;

    /**
     * True if the PDFView has been recycled
     */
    private boolean recycled = true;

    private volatile int currentRenderEpoch;

    /**
     * Current state of the view
     */
    private State state = State.DEFAULT;

    /**
     * Async task used during the loading phase to decode a PDF document
     */
    private DecodingAsyncTask decodingAsyncTask;

    RenderScheduler renderScheduler;

    private PagesLoader pagesLoader;

    private AtomicIntegerArray pageGenerations;

    private final Set<Integer> prewarmPending = new LinkedHashSet<>();

    private final Set<Integer> searchMarkerPages = new LinkedHashSet<>();

    private static volatile Boolean previewLowTierCache;

    private volatile PreviewStore<Bitmap> previewStore;

    private final Set<Integer> previewPending = new LinkedHashSet<>();

    private volatile TagSource previewTagSource;

    private int previewBucket;

    private final PreviewSweepCursor sweepCursor = new PreviewSweepCursor();

    private volatile int lastScrollDir = 1;

    private ExecutorService previewEncoder;

    private ExecutorService previewDecoder;

    private ComponentCallbacks2 previewTrimCallbacks;

    private final Set<Integer> previewVisiblePages = new LinkedHashSet<>();

    Callbacks callbacks = new Callbacks();

    /**
     * Paint object for drawing
     */
    private Paint paint;

    /**
     * Paint object for drawing debug stuff
     */
    private Paint debugPaint;

    private Paint selectedHighlightFillPaint;

    private Paint selectedHighlightStrokePaint;

    private Paint highlightAnnotationOverlayPaint;

    private Paint highlightAnnotationMultiplyPaint;

    private final Path highlightAnnotationGroupPath = new Path();

    private Paint formFieldFillPaint;

    private Paint formFieldStrokePaint;

    private HighlightAnnotation selectedHighlightAnnotation;

    /**
     * Policy for fitting pages to screen
     */
    private FitPolicy pageFitPolicy = FitPolicy.WIDTH;

    private boolean fitEachPage = false;

    private boolean threeStepDoubleTapZoom = false;

    private boolean cropMargins = false;

    private CropMargins cachedCropMargins = null;

    private int defaultPage = 0;

    private ViewState defaultViewState = null;

    /* True if should scroll through pages vertically instead of horizontally */
    private boolean swipeVertical = true;

    private boolean horizontalReadingDirectionRtl = false;

    private boolean enableSwipe = true;

    private boolean horizontalSwipeDisabled = false;

    private boolean zoomDisabled = false;

    private boolean doubleTapEnabled = true;

    private boolean nightMode = false;

    private boolean pageSnap = true;

    private boolean freeScrollMode = false;

    /**
     * Pdfium core for loading and rendering PDFs
     */
    private PdfiumCore pdfiumCore;

    private ScrollHandle scrollHandle;

    private boolean isScrollHandleInit = false;

    public ScrollHandle getScrollHandle() {
        return scrollHandle;
    }

    /**
     * True if bitmap should use ARGB_8888 format and take more memory
     * False if bitmap should be compressed by using RGB_565 format and take less memory
     */
    private boolean bestQuality = false;

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    private boolean annotationRendering = false;

    /**
     * True if the view should render during scaling<br/>
     * Can not be forced on older API versions (< Build.VERSION_CODES.KITKAT) as the GestureDetector does
     * not detect scrolling while scaling.<br/>
     * False otherwise
     */
    private boolean renderDuringScale = false;

    /**
     * Antialiasing and bitmap filtering
     */
    private boolean enableAntialiasing = true;
    private PaintFlagsDrawFilter antialiasFilter =
        new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /**
     * Spacing between pages, in px
     */
    private int spacingPx = 0;

    private int pagesPerRow = 1;

    private boolean firstPageAlone = false;

    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */
    private boolean autoSpacing = false;

    /**
     * If true,the PdfView would release automatically when it is detached from window,
     * otherwise false
     */
    private boolean autoReleasingWhenDetachedFromWindow = true;

    /**
     * Fling a single page at a time
     */
    private boolean pageFling = true;

    /**
     * Pages numbers used when calling onDrawAllListener
     */
    private List<Integer> onDrawPagesNums = new ArrayList<>(10);

    /**
     * Holds info whether view has been added to layout and has width and height
     */
    private boolean hasSize = false;

    /**
     * Holds last used Configurator that should be loaded when view has size
     */
    private Configurator waitingDocumentConfigurator;

    /**
     * Construct the initial view
     */
    public PDFView(Context context, AttributeSet set) {
        super(context, set);

        if (isInEditMode()) {
            return;
        }

        cacheManager = new CacheManager();
        animationManager = new AnimationManager(this);
        textSelectionManager = new TextSelectionManager(this);
        stampPlacementManager = new StampPlacementManager(this);
        dragPinchManager = new DragPinchManager(this, animationManager);
        pagesLoader = new PagesLoader(this);

        paint = new Paint();
        debugPaint = new Paint();
        debugPaint.setStyle(Style.STROKE);
        selectedHighlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedHighlightFillPaint.setStyle(Style.FILL);
        selectedHighlightStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedHighlightStrokePaint.setStyle(Style.STROKE);
        selectedHighlightStrokePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        highlightAnnotationOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightAnnotationOverlayPaint.setStyle(Style.FILL);
        highlightAnnotationMultiplyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightAnnotationMultiplyPaint.setStyle(Style.FILL);
        highlightAnnotationMultiplyPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        formFieldFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        formFieldFillPaint.setStyle(Style.FILL);
        formFieldFillPaint.setColor(FORM_FIELD_FILL_COLOR);
        formFieldStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        formFieldStrokePaint.setStyle(Style.STROKE);
        formFieldStrokePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        formFieldStrokePaint.setColor(FORM_FIELD_STROKE_COLOR);
        setSelectedHighlightColor(0xFF3F51B5);

        pdfiumCore = new PdfiumCore(context);
        setWillNotDraw(false);
    }

    private void load(DocumentSource docSource, String password) {
        load(docSource, password, null);
    }

    private void load(DocumentSource docSource, String password, int[] userPages) {

        if (!recycled) {
            throw new IllegalStateException("Don't call load on a PDF View without recycling it first.");
        }

        recycled = false;
        // Start decoding document
        decodingAsyncTask = new DecodingAsyncTask(docSource, password, userPages, this, pdfiumCore);
        decodingAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Go to the given page.
     *
     * @param page Page index.
     */
    public void jumpTo(int page, boolean withAnimation) {
        if (pdfFile == null) {
            return;
        }

        page = pdfFile.determineValidPageNumberFrom(page);
        float offset = jumpShouldCenterPage(page)
                ? -snapOffsetForPage(page, SnapEdge.CENTER)
                : -pdfFile.getPageOffset(page, zoom);
        pageTrackingSuppressed = true;
        try {
            if (swipeVertical) {
                if (withAnimation) {
                    animationManager.startYAnimation(currentYOffset, offset);
                } else {
                    moveTo(currentXOffset, offset);
                }
            } else {
                if (withAnimation) {
                    animationManager.startXAnimation(currentXOffset, offset);
                } else {
                    moveTo(offset, currentYOffset);
                }
            }
        } finally {
            pageTrackingSuppressed = false;
        }
        showPage(page);
    }

    private boolean jumpShouldCenterPage(int page) {
        if (!pageSnap && !autoSpacing) {
            return false;
        }
        int row = pdfFile.getRowOfPage(page);
        float viewportLength = swipeVertical ? getHeight() : getWidth();
        return viewportLength >= pdfFile.getRowLength(row, zoom);
    }

    public void jumpTo(int pageIndex) {
        jumpTo(pageIndex, false);
    }

    public void jumpUsingPageNumber(int pageNumber) {
        jumpTo(pageNumber - 1, false);
    }

    void showPage(int pageNb) {
        if (recycled) {
            return;
        }

        // Check the page number and makes the
        // difference between UserPages and DocumentPages
        pageNb = pdfFile.determineValidPageNumberFrom(pageNb);
        currentPage = pageNb;

        loadPages();

        if (scrollHandle != null && !documentFitsView()) {
            scrollHandle.setPageNum(currentPage + 1);
            scrollHandle.setScroll(getPositionOffset());
        }

        callbacks.callOnPageChange(currentPage, pdfFile.getPagesCount());
        publishDebugState();
    }

    /**
     * Get current position as ratio of document length to visible area.
     * 0 means that document start is visible, 1 that document end is visible
     *
     * @return offset between 0 and 1
     */
    public float getPositionOffset() {
        if (pdfFile == null) return 0;

        float offset;
        if (swipeVertical) {
            offset = -currentYOffset / (pdfFile.getDocLen(zoom) - getHeight());
        } else {
            offset = -currentXOffset / (pdfFile.getDocLen(zoom) - getWidth());
        }
        return MathUtils.limit(offset, 0, 1);
    }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see PDFView#getPositionOffset()
     */
    public void setPositionOffset(float progress, boolean moveHandle) {
        if (pdfFile == null) return;

        if (swipeVertical) {
            moveTo(currentXOffset, (-pdfFile.getDocLen(zoom) + getHeight()) * progress, moveHandle);
        } else {
            moveTo((-pdfFile.getDocLen(zoom) + getWidth()) * progress, currentYOffset, moveHandle);
        }
        loadPageByOffset();
    }

    public void setPositionOffset(float progress) {
        setPositionOffset(progress, true);
    }

    public ViewState captureViewState() {
        if (state != State.SHOWN || pdfFile == null || pdfFile.getPagesCount() == 0 || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }

        int pageIndex = pdfFile.determineValidPageNumberFrom(findFocusPage(currentXOffset, currentYOffset));
        float centerX = -currentXOffset + getWidth() * 0.5f;
        float centerY = -currentYOffset + getHeight() * 0.5f;
        float relativeCrossAxisCenter;
        float pageCenterOffsetRatio;

        if (swipeVertical) {
            relativeCrossAxisCenter = ratioOrDefault(centerX, pdfFile.getMaxPageWidth(), 0.5f);
            pageCenterOffsetRatio = ratioOrDefault(
                    centerY - pdfFile.getPageOffset(pageIndex, zoom),
                    pdfFile.getPageLength(pageIndex, zoom),
                    0.5f);
        } else {
            relativeCrossAxisCenter = ratioOrDefault(centerY, pdfFile.getMaxPageHeight(), 0.5f);
            pageCenterOffsetRatio = ratioOrDefault(
                    centerX - pdfFile.getPageOffset(pageIndex, zoom),
                    pdfFile.getPageLength(pageIndex, zoom),
                    0.5f);
        }

        return new ViewState(
                zoom,
                pageIndex,
                swipeVertical,
                isHorizontalReadingDirectionRtl(),
                relativeCrossAxisCenter,
                MathUtils.limit(pageCenterOffsetRatio, 0f, 1f),
                pdfFile.getPagesPerRow(),
                pdfFile.isFirstPageAlone());
    }

    public void stopFling() {
        animationManager.stopFling();
    }

    public boolean isAnimating() {
        return animationManager != null && animationManager.isAnimating();
    }

    public boolean isFlinging() {
        return animationManager != null && animationManager.isFlinging();
    }

    public int getPageCount() {
        if (pdfFile == null) {
            return 0;
        }
        return pdfFile.getPagesCount();
    }

    public void setSwipeEnabled(boolean enableSwipe) {
        this.enableSwipe = enableSwipe;
    }

    public void setHorizontalSwipeDisabled(boolean horizontalSwipeDisabled) {
        this.horizontalSwipeDisabled = horizontalSwipeDisabled;
    }


    public void setZoomDisabled(boolean zoomDisabled) {
        this.zoomDisabled = zoomDisabled;
    }

    public void setNightMode(boolean nightMode) {
        this.nightMode = nightMode;
        if (nightMode) {
            ColorMatrix colorMatrixInverted =
                new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0});

            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrixInverted);
            paint.setColorFilter(filter);
        } else {
            paint.setColorFilter(null);
        }
    }

    void enableDoubleTap(boolean enableDoubleTap) {
        this.doubleTapEnabled = enableDoubleTap;
    }

    boolean isDoubleTapEnabled() {
        return doubleTapEnabled;
    }

    void onPageError(PageRenderingException ex) {
        if (!callbacks.callOnPageError(ex.getPage(), ex.getCause())) {
            Log.e(TAG, "Cannot open page " + ex.getPage(), ex.getCause());
        }
    }

    public void recycle() {
        waitingDocumentConfigurator = null;

        animationManager.stopAll();
        dragPinchManager.disable();

        // Stop tasks
        if (renderScheduler != null) {
            renderScheduler.stop();
        }
        if (decodingAsyncTask != null) {
            decodingAsyncTask.cancel(true);
        }

        // Clear caches
        cacheManager.recycle();

        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle.destroyLayout();
        }

        if (textSelectionManager != null) {
            textSelectionManager.recycle();
        }
        if (stampPlacementManager != null) {
            stampPlacementManager.recycle();
        }
        selectedHighlightAnnotation = null;

        if (pdfFile != null) {
            pdfFile.dispose();
            pdfFile = null;
        }

        scrollHandle = null;
        isScrollHandleInit = false;
        currentXOffset = currentYOffset = 0;
        zoom = 1f;
        defaultViewState = null;
        synchronized (prewarmPending) {
            prewarmPending.clear();
        }
        synchronized (searchMarkerPages) {
            searchMarkerPages.clear();
        }

        PreviewStore<Bitmap> store = previewStore;
        previewStore = null;
        if (store != null) {
            store.close();
        }
        if (previewTrimCallbacks != null) {
            getContext().unregisterComponentCallbacks(previewTrimCallbacks);
            previewTrimCallbacks = null;
        }
        if (previewEncoder != null) {
            previewEncoder.shutdownNow();
            previewEncoder = null;
        }
        if (previewDecoder != null) {
            previewDecoder.shutdownNow();
            previewDecoder = null;
        }
        synchronized (previewPending) {
            previewPending.clear();
        }
        sweepCursor.resetAll();

        pageGenerations = null;
        recycled = true;
        currentRenderEpoch++;
        callbacks = new Callbacks();
        state = State.DEFAULT;
    }

    public boolean isRecycled() {
        return recycled;
    }

    /**
     * Handle fling animation
     */
    @Override
    public void computeScroll() {
        super.computeScroll();
        if (isInEditMode()) {
            return;
        }
        animationManager.computeFling();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (pdfFile == null
                || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                || event.getActionMasked() != MotionEvent.ACTION_SCROLL) {
            return super.onGenericMotionEvent(event);
        }
        float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
        if (vScroll == 0f && hScroll == 0f) {
            return super.onGenericMotionEvent(event);
        }
        callbacks.callOnDocumentInteraction(event);
        if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
            if (zoomDisabled) {
                return true;
            }
            float dr = (float) Math.pow(1.15f, vScroll);
            float wantedZoom = zoom * dr;
            float minWheelZoom = Math.min(MINIMUM_ZOOM, getMinZoom());
            float maxWheelZoom = Math.min(MAXIMUM_ZOOM, getMaxZoom());
            if (wantedZoom < minWheelZoom) {
                dr = minWheelZoom / zoom;
            } else if (wantedZoom > maxWheelZoom) {
                dr = maxWheelZoom / zoom;
            }
            zoomCenteredRelativeTo(dr, new PointF(event.getX(), event.getY()));
            scheduleWheelSettle(false);
            return true;
        }
        if (!isSwipeEnabled()) {
            return true;
        }
        float factor = wheelScrollFactor();
        float dx;
        float dy;
        if (swipeVertical) {
            dx = horizontalSwipeDisabled ? 0 : -hScroll * factor;
            dy = vScroll * factor;
        } else {
            dx = isHorizontalReadingDirectionRtl() ? -vScroll * factor : vScroll * factor;
            dy = hScroll * factor;
        }
        moveRelativeTo(dx, dy);
        loadPageByOffset();
        scheduleWheelSettle(true);
        return true;
    }

    private float wheelScrollFactor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ViewConfiguration.get(getContext()).getScaledVerticalScrollFactor();
        }
        return 64 * getContext().getResources().getDisplayMetrics().density;
    }

    private boolean wheelSettleSnap = true;

    private void scheduleWheelSettle(boolean snap) {
        wheelSettleSnap = snap;
        removeCallbacks(wheelSettleRunnable);
        postDelayed(wheelSettleRunnable, WHEEL_SETTLE_DELAY_MS);
    }

    private final Runnable wheelSettleRunnable = new Runnable() {
        @Override
        public void run() {
            if (pdfFile == null || recycled) {
                return;
            }
            loadPages();
            if (wheelSettleSnap) {
                performPageSnap();
            }
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(wheelSettleRunnable);
        if (autoReleasingWhenDetachedFromWindow) {
            release();
        }
        super.onDetachedFromWindow();
    }

    public void release() {
        recycle();
        if (renderScheduler != null) {
            renderScheduler.join();
            renderScheduler = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        hasSize = true;
        if (waitingDocumentConfigurator != null) {
            waitingDocumentConfigurator.load();
        }
        if (isInEditMode() || state != State.SHOWN) {
            return;
        }

        // calculates the position of the point which in the center of view relative to big strip
        float centerPointInStripXOffset = -currentXOffset + oldw * 0.5f;
        float centerPointInStripYOffset = -currentYOffset + oldh * 0.5f;

        float relativeCenterPointInStripXOffset;
        float relativeCenterPointInStripYOffset;

        if (swipeVertical) {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile.getMaxPageWidth();
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile.getDocLen(zoom);
        } else {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile.getDocLen(zoom);
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile.getMaxPageHeight();
        }

        animationManager.stopAll();
        pdfFile.recalculatePageSizes(new Size(w, h));

        if (swipeVertical) {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile.getMaxPageWidth() + w * 0.5f;
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile.getDocLen(zoom) + h * 0.5f;
        } else {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile.getDocLen(zoom) + w * 0.5f;
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile.getMaxPageHeight() + h * 0.5f;
        }
        moveTo(currentXOffset, currentYOffset);
        loadPageByOffset();
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        if (pdfFile == null) {
            return true;
        }

        if (swipeVertical) {
            if (direction < 0 && currentXOffset < 0) {
                return true;
            } else if (direction > 0 && currentXOffset + toCurrentScale(pdfFile.getMaxPageWidth()) > getWidth()) {
                return true;
            }
        } else {
            if (direction < 0 && currentXOffset < 0) {
                return true;
            } else if (direction > 0 && currentXOffset + pdfFile.getDocLen(zoom) > getWidth()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canScrollVertically(int direction) {
        if (pdfFile == null) {
            return true;
        }

        if (swipeVertical) {
            if (direction < 0 && currentYOffset < 0) {
                return true;
            } else if (direction > 0 && currentYOffset + pdfFile.getDocLen(zoom) > getHeight()) {
                return true;
            }
        } else {
            if (direction < 0 && currentYOffset < 0) {
                return true;
            } else if (direction > 0 && currentYOffset + toCurrentScale(pdfFile.getMaxPageHeight()) > getHeight()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isInEditMode()) {
            return;
        }
        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background

        if (enableAntialiasing) {
            canvas.setDrawFilter(antialiasFilter);
        }

        Drawable bg = getBackground();
        if (bg == null) {
            canvas.drawColor(nightMode ? Color.BLACK : Color.WHITE);
        } else {
            bg.draw(canvas);
        }

        if (recycled) {
            return;
        }

        if (state != State.SHOWN) {
            return;
        }

        // Moves the canvas before drawing any element
        float currentXOffset = this.currentXOffset;
        float currentYOffset = this.currentYOffset;
        canvas.translate(currentXOffset, currentYOffset);
        Set<Integer> visiblePages = new LinkedHashSet<>();

        drawPreviews(canvas);

        // Draws parts
        for (PagePart part : cacheManager.getPageParts()) {
            visiblePages.add(part.getPage());
            drawPart(canvas, part);
            if (callbacks.getOnDrawAll() != null
                && !onDrawPagesNums.contains(part.getPage())) {
                onDrawPagesNums.add(part.getPage());
            }
        }

        for (Integer page : onDrawPagesNums) {
            drawWithListener(canvas, page, callbacks.getOnDrawAll());
        }
        onDrawPagesNums.clear();

        drawWithListener(canvas, currentPage, callbacks.getOnDraw());
        visiblePages.add(currentPage);

        drawFormFieldIndicators(canvas, visiblePages);
        drawHighlightAnnotationOverlays(canvas, visiblePages);
        drawSelectedHighlightAnnotation(canvas);

        if (textSelectionManager != null) {
            textSelectionManager.draw(canvas);
        }

        if (stampPlacementManager != null) {
            stampPlacementManager.draw(canvas);
        }

        canvas.translate(-currentXOffset, -currentYOffset);
    }

    private void drawFormFieldIndicators(Canvas canvas, Set<Integer> pages) {
        if (pdfFile == null || pages == null || pages.isEmpty()) {
            return;
        }
        for (Integer page : pages) {
            if (page == null || page < 0 || page >= pdfFile.getPagesCount()) {
                continue;
            }
            float[] rects = pdfFile.peekFormFieldRects(page);
            if (rects == null) {
                requestPrewarm(page);
                continue;
            }
            for (int i = 0; i + 3 < rects.length; i += 4) {
                RectF docRect = pdfFile.pdfRectToDocument(
                        page,
                        zoom,
                        rects[i],
                        rects[i + 1],
                        rects[i + 2],
                        rects[i + 3]
                );
                if (docRect == null) {
                    continue;
                }
                canvas.drawRect(docRect, formFieldFillPaint);
                canvas.drawRect(docRect, formFieldStrokePaint);
            }
        }
    }

    private void drawSelectedHighlightAnnotation(Canvas canvas) {
        if (selectedHighlightAnnotation == null || selectedHighlightAnnotation.pdfBounds == null
                || pdfFile == null) {
            return;
        }
        RectF pdfBounds = selectedHighlightAnnotation.pdfBounds;
        RectF docRect = pdfFile.pdfRectToDocument(
                selectedHighlightAnnotation.pageIndex,
                zoom,
                pdfBounds.left,
                pdfBounds.bottom,
                pdfBounds.right,
                pdfBounds.top
        );
        if (docRect == null) {
            return;
        }
        canvas.drawRect(docRect, selectedHighlightFillPaint);
        canvas.drawRect(docRect, selectedHighlightStrokePaint);
    }

    private void drawHighlightAnnotationOverlays(Canvas canvas, Set<Integer> pages) {
        if (!annotationRendering || pdfFile == null || pages == null || pages.isEmpty()) {
            return;
        }
        for (Integer page : pages) {
            if (page == null || page < 0 || page >= pdfFile.getPagesCount()) {
                continue;
            }
            List<PdfDocument.HighlightAnnotation> annotations = pdfFile.peekHighlightAnnotations(page);
            if (annotations == null) {
                requestPrewarm(page);
                continue;
            }
            Map<String, List<PdfDocument.HighlightAnnotation>> groups =
                    pdfFile.peekHighlightAnnotationGroups(page, annotations);
            for (List<PdfDocument.HighlightAnnotation> members : groups.values()) {
                Paint overlayPaint = highlightOverlayPaint(members.get(0));
                if (overlayPaint == null) {
                    continue;
                }
                highlightAnnotationGroupPath.rewind();
                boolean hasRect = false;
                for (PdfDocument.HighlightAnnotation annotation : members) {
                    RectF bounds = annotation.getBounds();
                    RectF docRect = pdfFile.pdfRectToDocument(
                            page,
                            zoom,
                            bounds.left,
                            bounds.bottom,
                            bounds.right,
                            bounds.top
                    );
                    if (docRect == null) {
                        continue;
                    }
                    highlightAnnotationGroupPath.addRect(docRect, Path.Direction.CW);
                    hasRect = true;
                }
                if (hasRect) {
                    canvas.drawPath(highlightAnnotationGroupPath, overlayPaint);
                }
            }
        }
    }

    private Paint highlightOverlayPaint(PdfDocument.HighlightAnnotation annotation) {
        if (nightMode) {
            highlightAnnotationOverlayPaint.setColor((0x66 << 24) | (annotation.getColor() & 0x00FFFFFF));
            return highlightAnnotationOverlayPaint;
        }
        if (!annotation.isAppOwned()) {
            return null;
        }
        highlightAnnotationMultiplyPaint.setColor(0xFF000000 | (annotation.getColor() & 0x00FFFFFF));
        return highlightAnnotationMultiplyPaint;
    }

    private void drawWithListener(Canvas canvas, int page, OnDrawListener listener) {
        if (listener != null) {
            float translateX, translateY;
            if (swipeVertical) {
                translateX = pdfFile.getSecondaryPageOffset(page, zoom);
                translateY = pdfFile.getPageOffset(page, zoom);
            } else {
                translateY = pdfFile.getSecondaryPageOffset(page, zoom);
                translateX = pdfFile.getPageOffset(page, zoom);
            }

            canvas.translate(translateX, translateY);
            SizeF size = pdfFile.getPageSize(page);
            listener.onLayerDrawn(canvas,
                toCurrentScale(size.getWidth()),
                toCurrentScale(size.getHeight()),
                page);

            canvas.translate(-translateX, -translateY);
        }
    }

    private void drawPreviews(Canvas canvas) {
        PreviewStore<Bitmap> store = previewStore;
        if (store == null || pdfFile == null) {
            return;
        }
        previewVisiblePages.clear();
        collectVisiblePages(previewVisiblePages);
        for (Integer page : previewVisiblePages) {
            Bitmap preview = store.peek(page);
            if (preview == null) {
                requestPreview(page);
            } else {
                drawPreview(canvas, page, preview);
            }
        }
    }

    private void collectVisiblePages(Set<Integer> out) {
        long range = visiblePageRange();
        if (range < 0L) {
            return;
        }
        int to = (int) range;
        for (int page = (int) (range >>> 32); page <= to; page++) {
            out.add(page);
        }
    }

    private boolean isPageInPreviewRange(int page) {
        long range = visiblePageRange();
        if (range < 0L) {
            return false;
        }
        return page >= (int) (range >>> 32) && page <= (int) range;
    }

    private long visiblePageRange() {
        if (pdfFile == null) {
            return -1L;
        }
        int count = pdfFile.getPagesCount();
        if (count <= 0) {
            return -1L;
        }
        float offset = swipeVertical ? currentYOffset : currentXOffset;
        float viewSize = swipeVertical ? getHeight() : getWidth();
        float startDist = Math.max(0f, -offset);
        float endDist = Math.max(0f, -offset + viewSize);
        int first = pdfFile.getPageAtOffset(startDist, zoom);
        int last = pdfFile.getPageAtOffset(endDist, zoom);
        int from = Math.max(0, Math.min(first, last) - 1);
        int to = Math.min(count - 1, Math.max(first, last) + 1);
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    private void drawPreview(Canvas canvas, int page, Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        synchronized (bitmap) {
            if (bitmap.isRecycled()) {
                return;
            }
            int bitmapWidth = bitmap.getWidth();
            int bitmapHeight = bitmap.getHeight();
            if (bitmapWidth <= 0 || bitmapHeight <= 0) {
                return;
            }

            SizeF size = pdfFile.getPageSize(page);
            float localTranslationX;
            float localTranslationY;
            if (swipeVertical) {
                localTranslationY = pdfFile.getPageOffset(page, zoom);
                localTranslationX = pdfFile.getSecondaryPageOffset(page, zoom);
            } else {
                localTranslationX = pdfFile.getPageOffset(page, zoom);
                localTranslationY = pdfFile.getSecondaryPageOffset(page, zoom);
            }
            canvas.translate(localTranslationX, localTranslationY);

            float width = toCurrentScale(size.getWidth());
            float height = toCurrentScale(size.getHeight());
            RectF dstRect = new RectF(0, 0, (int) width, (int) height);

            float translationX = currentXOffset + localTranslationX;
            float translationY = currentYOffset + localTranslationY;
            if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0
                    || translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
                canvas.translate(-localTranslationX, -localTranslationY);
                return;
            }

            CropBounds crop = pdfFile.getPageCropBounds(page);
            int srcLeft = (int) (crop.getLeft() * bitmapWidth);
            int srcTop = (int) (crop.getTop() * bitmapHeight);
            int srcRight = (int) (crop.getRight() * bitmapWidth);
            int srcBottom = (int) (crop.getBottom() * bitmapHeight);
            if (srcRight <= srcLeft || srcBottom <= srcTop) {
                canvas.translate(-localTranslationX, -localTranslationY);
                return;
            }

            Rect srcRect = new Rect(srcLeft, srcTop, srcRight, srcBottom);
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint);

            canvas.translate(-localTranslationX, -localTranslationY);
        }
    }

    /**
     * Draw a given PagePart on the canvas
     */
    private void drawPart(Canvas canvas, PagePart part) {
        // Can seem strange, but avoid lot of calls
        RectF pageRelativeBounds = part.getPageRelativeBounds();
        Bitmap renderedBitmap = part.getRenderedBitmap();

        if (renderedBitmap == null) {
            return;
        }

        synchronized (renderedBitmap) {
            if (renderedBitmap.isRecycled()) {
                return;
            }

            // Move to the target page
            float localTranslationX = 0;
            float localTranslationY = 0;
            SizeF size = pdfFile.getPageSize(part.getPage());

            if (swipeVertical) {
                localTranslationY = pdfFile.getPageOffset(part.getPage(), zoom);
                localTranslationX = pdfFile.getSecondaryPageOffset(part.getPage(), zoom);
            } else {
                localTranslationX = pdfFile.getPageOffset(part.getPage(), zoom);
                localTranslationY = pdfFile.getSecondaryPageOffset(part.getPage(), zoom);
            }
            canvas.translate(localTranslationX, localTranslationY);

            Rect srcRect = new Rect(0, 0, renderedBitmap.getWidth(),
                renderedBitmap.getHeight());

            float offsetX = toCurrentScale(pageRelativeBounds.left * size.getWidth());
            float offsetY = toCurrentScale(pageRelativeBounds.top * size.getHeight());
            float width = toCurrentScale(pageRelativeBounds.width() * size.getWidth());
            float height = toCurrentScale(pageRelativeBounds.height() * size.getHeight());

            // If we use float values for this rectangle, there will be a possible gap between
            // page parts, especially when the zoom level is high.
            RectF dstRect = new RectF((int) offsetX, (int) offsetY,
                (int) (offsetX + width),
                (int) (offsetY + height));

            // Check if bitmap is in the screen
            float translationX = currentXOffset + localTranslationX;
            float translationY = currentYOffset + localTranslationY;
            if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 ||
                translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
                canvas.translate(-localTranslationX, -localTranslationY);
                return;
            }

            canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint);

            if (Constants.DEBUG_MODE) {
                debugPaint.setColor(part.getPage() % 2 == 0 ? Color.RED : Color.BLUE);
                canvas.drawRect(dstRect, debugPaint);
            }

            // Restore the canvas position
            canvas.translate(-localTranslationX, -localTranslationY);
        }
    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    public void loadPages() {
        if (pdfFile == null || renderScheduler == null) {
            return;
        }

        synchronized (previewPending) {
            previewPending.clear();
        }

        renderScheduler.beginWave(RenderQueue.WaveKind.LOAD);
        cacheManager.makeANewSet();

        pagesLoader.loadPages();
        renderScheduler.endWave();
        redraw();
    }

    /**
     * Render the visible region of each visible page as a single part,
     * replacing any queued render tasks. Used for cheap whole-viewport
     * refreshes during a pinch gesture.
     */
    void loadViewportSnapshot() {
        if (pdfFile == null || renderScheduler == null) {
            return;
        }

        renderScheduler.beginWave(RenderQueue.WaveKind.SNAPSHOT);
        pagesLoader.loadViewportSnapshot();
        renderScheduler.endWave();
        redraw();
    }

    /**
     * Called when the PDF is loaded
     */
    void loadComplete(PdfFile pdfFile) {
        state = State.LOADED;

        this.pdfFile = pdfFile;
        if (pdfFile == null) {
            Log.e(TAG, "loadComplete: pdfFile is nul!!");
            return;
        }

        pageGenerations = new AtomicIntegerArray(pdfFile.getPagesCount());

        currentRenderEpoch++;
        int epoch = currentRenderEpoch;
        renderScheduler = new RenderScheduler(this, new RenderScheduler.PdfExecutor(this, epoch), epoch);
        renderScheduler.start();

        initPreviewStore(pdfFile);

        if (scrollHandle != null) {
            scrollHandle.setupLayout(this);
            isScrollHandleInit = true;
        }
        dragPinchManager.enable();
        callbacks.callOnLoadComplete(pdfFile.getPagesCount());
        if (!restoreDefaultViewState()) {
            jumpTo(defaultPage, false);
        }
        publishDebugState();
    }

    private boolean restoreDefaultViewState() {
        ViewState viewState = defaultViewState;
        defaultViewState = null;
        return applyViewState(viewState);
    }

    public boolean applyViewState(ViewState viewState) {
        if (viewState == null || pdfFile == null || pdfFile.getPagesCount() == 0) {
            return false;
        }

        int pageIndex = pdfFile.determineValidPageNumberFrom(viewState.pageIndex);
        zoomTo(validZoom(viewState.zoom));
        if (viewState.swipeVertical != swipeVertical
                || viewState.horizontalReadingDirectionRtl != isHorizontalReadingDirectionRtl()
                || viewState.pagesPerRow != pdfFile.getPagesPerRow()
                || viewState.firstPageAlone != pdfFile.isFirstPageAlone()
                || getWidth() <= 0
                || getHeight() <= 0) {
            jumpTo(pageIndex, false);
            return true;
        }

        float pageLength = pdfFile.getPageLength(pageIndex, zoom);
        if (!isUsable(pageLength)) {
            jumpTo(pageIndex, false);
            return true;
        }

        float pageCenterOffsetRatio = isValidNumber(viewState.pageCenterOffsetRatio)
                ? MathUtils.limit(viewState.pageCenterOffsetRatio, 0f, 1f)
                : 0.5f;
        float relativeCrossAxisCenter = isValidNumber(viewState.relativeCrossAxisCenter)
                ? viewState.relativeCrossAxisCenter
                : 0.5f;
        float pageCenterOffset = pdfFile.getPageOffset(pageIndex, zoom) + pageLength * pageCenterOffsetRatio;

        float offsetX;
        float offsetY;
        if (swipeVertical) {
            float crossAxisSize = pdfFile.getMaxPageWidth();
            if (!isUsable(crossAxisSize)) {
                jumpTo(pageIndex, false);
                return true;
            }
            offsetX = -(relativeCrossAxisCenter * crossAxisSize) + getWidth() * 0.5f;
            offsetY = -pageCenterOffset + getHeight() * 0.5f;
        } else {
            float crossAxisSize = pdfFile.getMaxPageHeight();
            if (!isUsable(crossAxisSize)) {
                jumpTo(pageIndex, false);
                return true;
            }
            offsetX = -pageCenterOffset + getWidth() * 0.5f;
            offsetY = -(relativeCrossAxisCenter * crossAxisSize) + getHeight() * 0.5f;
        }

        pageTrackingSuppressed = true;
        try {
            moveTo(offsetX, offsetY);
        } finally {
            pageTrackingSuppressed = false;
        }
        showPage(pageIndex);
        return true;
    }

    void loadError(Throwable t) {
        state = State.ERROR;
        // store reference, because callbacks will be cleared in recycle() method
        OnErrorListener onErrorListener = callbacks.getOnError();
        recycle();
        invalidate();
        if (onErrorListener != null) {
            onErrorListener.onError(t);
        } else {
            Log.e("PDFView", "load pdf error", t);
        }
        publishDebugState();
    }

    void redraw() {
        invalidate();
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     *
     * @param part The created PagePart.
     */
    public void onBitmapRendered(PagePart part) {
        // when it is first rendered part
        if (state == State.LOADED) {
            state = State.SHOWN;
            callbacks.callOnRender(pdfFile.getPagesCount());
        }

        if (part.getGeneration() != getPageGeneration(part.getPage())) {
            Bitmap bitmap = part.getRenderedBitmap();
            if (bitmap != null) {
                synchronized (bitmap) {
                    bitmap.recycle();
                }
            }
            return;
        }

        cacheManager.cachePart(part);
        redraw();
        publishDebugState();
    }

    public void moveTo(float offsetX, float offsetY) {
        /*
         * Changing the 3rd argument (moveHandle) to false solved the issue with animation when
         * scrolling file with the handler invisible.
         * I have spent at least 6 hours trying to find why the flinging animation gets so bad
         * when I hide the scrollHandler view.
         * I turned that off when the scroll isn't shown.
         * And to move the handle, I added few lines to the onSingleTapConfirmed() function in
         * DragPinchManager, that will move the handle based on pdfFile.getPositionOffset value.
         * */
        boolean shouldMove = scrollHandle != null && scrollHandle.customShown();
        moveTo(offsetX, offsetY, shouldMove);
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the the big strip.
     *
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    public void moveTo(float offsetX, float offsetY, boolean moveHandle) {
        if (pdfFile == null) {
            return;
        }

        if (swipeVertical) {
            // Check X offset
            float scaledPageWidth = toCurrentScale(pdfFile.getMaxPageWidth());
            if (scaledPageWidth < getWidth()) {
                offsetX = getWidth() / 2 - scaledPageWidth / 2;
            } else {
                if (offsetX > 0) {
                    offsetX = 0;
                } else if (offsetX + scaledPageWidth < getWidth()) {
                    offsetX = getWidth() - scaledPageWidth;
                }
            }

            // Check Y offset
            float contentHeight = pdfFile.getDocLen(zoom);
            if (contentHeight < getHeight()) { // whole document height visible on screen
                offsetY = (getHeight() - contentHeight) / 2;
            } else {
                if (offsetY > 0) { // top visible
                    offsetY = 0;
                } else if (offsetY + contentHeight < getHeight()) { // bottom visible
                    offsetY = -contentHeight + getHeight();
                }
            }

            if (offsetY < currentYOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetY > currentYOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        } else {
            // Check Y offset
            float scaledPageHeight = toCurrentScale(pdfFile.getMaxPageHeight());
            if (scaledPageHeight < getHeight()) {
                offsetY = getHeight() / 2 - scaledPageHeight / 2;
            } else {
                if (offsetY > 0) {
                    offsetY = 0;
                } else if (offsetY + scaledPageHeight < getHeight()) {
                    offsetY = getHeight() - scaledPageHeight;
                }
            }

            // Check X offset
            float contentWidth = pdfFile.getDocLen(zoom);
            if (contentWidth < getWidth()) { // whole document width visible on screen
                offsetX = (getWidth() - contentWidth) / 2;
            } else {
                if (offsetX > 0) { // left visible
                    offsetX = 0;
                } else if (offsetX + contentWidth < getWidth()) { // right visible
                    offsetX = -contentWidth + getWidth();
                }
            }

            if (offsetX < currentXOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetX > currentXOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        }

        if (scrollDir == ScrollDir.END) {
            lastScrollDir = 1;
        } else if (scrollDir == ScrollDir.START) {
            lastScrollDir = -1;
        }

        currentXOffset = offsetX;
        currentYOffset = offsetY;
        float positionOffset = getPositionOffset();

        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle.setScroll(positionOffset);
        }

        callbacks.callOnPageScroll(getCurrentPage(), positionOffset);

        redraw();

        showPageAtOffset();
    }

    void loadPageByOffset() {
        if (pdfFile == null || 0 == pdfFile.getPagesCount()) {
            return;
        }
        if (!showPageAtOffset()) {
            loadPages();
        }
    }

    private boolean showPageAtOffset() {
        if (pdfFile == null || 0 == pdfFile.getPagesCount() || pageTrackingSuppressed) {
            return false;
        }

        float offset, screenCenter;
        if (swipeVertical) {
            offset = currentYOffset;
            screenCenter = ((float) getHeight()) / 2;
        } else {
            offset = currentXOffset;
            screenCenter = ((float) getWidth()) / 2;
        }

        int page = pdfFile.getPageAtOffset(-(offset - screenCenter), zoom);

        if (page < 0 || page > pdfFile.getPagesCount() - 1 || page == getCurrentPage()) {
            return false;
        }

        pageTrackingSuppressed = true;
        try {
            showPage(page);
        } finally {
            pageTrackingSuppressed = false;
        }
        return true;
    }

    /**
     * Animate to the nearest snapping position for the current SnapPolicy
     */
    public void performPageSnap() {
        if (!pageSnap || pdfFile == null || pdfFile.getPagesCount() == 0) {
            return;
        }
        int centerPage = findFocusPage(currentXOffset, currentYOffset);
        SnapEdge edge = findSnapEdge(centerPage);
        if (edge == SnapEdge.NONE) {
            return;
        }

        float offset = snapOffsetForPage(centerPage, edge);
        if (swipeVertical) {
            animationManager.startYAnimation(currentYOffset, -offset);
        } else {
            animationManager.startXAnimation(currentXOffset, -offset);
        }
    }

    /**
     * Find the edge to snap to when showing the specified page
     */
    SnapEdge findSnapEdge(int page) {
        if (!pageSnap || page < 0) {
            return SnapEdge.NONE;
        }
        int row = pdfFile.getRowOfPage(page);
        float currentOffset = swipeVertical ? currentYOffset : currentXOffset;
        float offset = -pdfFile.getRowOffset(row, zoom);
        int length = swipeVertical ? getHeight() : getWidth();
        float pageLength = pdfFile.getRowLength(row, zoom);

        if (length >= pageLength) {
            return SnapEdge.CENTER;
        }
        else if (currentOffset >= offset) {
            return SnapEdge.START;
        }
        else if (offset - pageLength > currentOffset - length) {
            return SnapEdge.END;
        }
        else {
            return SnapEdge.NONE;
        }
    }

    /**
     * Get the offset to move to in order to snap to the page
     */
    float snapOffsetForPage(int pageIndex, SnapEdge edge) {
        int row = pdfFile.getRowOfPage(pageIndex);
        float offset = pdfFile.getRowOffset(row, zoom);

        float length = swipeVertical ? getHeight() : getWidth();
        float pageLength = pdfFile.getRowLength(row, zoom);

        if (edge == SnapEdge.CENTER) {
            offset = offset - length / 2f + pageLength / 2f;
        } else if (edge == SnapEdge.END) {
            offset = offset - length + pageLength;
        }
        return offset;
    }

    int findFocusPage(float xOffset, float yOffset) {
        float currOffset = swipeVertical ? yOffset : xOffset;
        float length = swipeVertical ? getHeight() : getWidth();
        // make sure first and last page can be found
        if (currOffset > -1) {
            return pdfFile.getRowFirstPage(pdfFile.getRowAtLayoutIndex(0));
        } else if (currOffset < -pdfFile.getDocLen(zoom) + length + 1) {
            int lastRow = pdfFile.getRowAtLayoutIndex(pdfFile.getRowCount() - 1);
            return pdfFile.getRowFirstPage(lastRow) + pdfFile.getPagesInRow(lastRow) - 1;
        }
        // else find page in center
        float center = currOffset - length / 2f;
        return pdfFile.getPageAtOffset(-center, zoom);
    }

    public int getPageAfterRowStep(int fromPage, int direction) {
        if (pdfFile == null || pdfFile.getPagesCount() == 0) {
            return fromPage;
        }
        int row = pdfFile.getRowOfPage(pdfFile.determineValidPageNumberFrom(fromPage));
        int targetRow = Math.max(0, Math.min(pdfFile.getRowCount() - 1, row + direction));
        return pdfFile.getRowFirstPage(targetRow);
    }

    public int getRowFirstPage(int pageIndex) {
        if (pdfFile == null) {
            return pageIndex;
        }
        return pdfFile.getRowFirstPage(pdfFile.getRowOfPage(pageIndex));
    }

    public int getRowLastPage(int pageIndex) {
        if (pdfFile == null) {
            return pageIndex;
        }
        int row = pdfFile.getRowOfPage(pageIndex);
        return pdfFile.getRowFirstPage(row) + pdfFile.getPagesInRow(row) - 1;
    }

    public LinkTapEvent findLinkAt(float x, float y) {
        if (pdfFile == null) {
            return null;
        }
        float mappedX = -getCurrentXOffset() + x;
        float mappedY = -getCurrentYOffset() + y;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? mappedY : mappedX,
                isSwipeVertical() ? mappedX : mappedY, getZoom());
        SizeF pageSize = pdfFile.getScaledPageSize(page, getZoom());
        int pageX, pageY;
        if (isSwipeVertical()) {
            pageX = (int) pdfFile.getSecondaryPageOffset(page, getZoom());
            pageY = (int) pdfFile.getPageOffset(page, getZoom());
        } else {
            pageY = (int) pdfFile.getSecondaryPageOffset(page, getZoom());
            pageX = (int) pdfFile.getPageOffset(page, getZoom());
        }
        for (PdfDocument.Link link : pdfFile.getPageLinks(page)) {
            RectF mapped = pdfFile.mapRectToDevice(page, pageX, pageY, (int) pageSize.getWidth(),
                    (int) pageSize.getHeight(), link.getBounds());
            if (mapped == null) {
                continue;
            }
            mapped.sort();
            if (mapped.contains(mappedX, mappedY)) {
                return new LinkTapEvent(x, y, mappedX, mappedY, mapped, link);
            }
        }
        return null;
    }

    public boolean hasLinkAt(float x, float y) {
        return findLinkAt(x, y) != null;
    }

    /**
     * @return true if single page fills the entire screen in the scrolling direction
     */
    public boolean pageFillsScreen() {
        if (pdfFile == null) {
            return false;
        }
        int row = pdfFile.getRowOfPage(currentPage);
        float start = -pdfFile.getRowOffset(row, zoom);
        float end = start - pdfFile.getRowLength(row, zoom);
        if (isSwipeVertical()) {
            return start > currentYOffset && end < currentYOffset - getHeight();
        } else {
            return start > currentXOffset && end < currentXOffset - getWidth();
        }
    }

    /**
     * Move relatively to the current position.
     *
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see #moveTo(float, float)
     */
    public void moveRelativeTo(float dx, float dy) {
        moveTo(currentXOffset + dx, currentYOffset + dy);
    }

    /**
     * Change the zoom level
     */
    public void zoomTo(float zoom) {
        this.zoom = zoom;
        publishDebugState();
    }

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     *
     * @param zoom  The zoom level.
     * @param pivot The point on the screen that should stays.
     */
    public void zoomCenteredTo(float zoom, PointF pivot) {
        float dzoom = zoom / this.zoom;
        zoomTo(zoom);
        float baseX = currentXOffset * dzoom;
        float baseY = currentYOffset * dzoom;
        baseX += (pivot.x - pivot.x * dzoom);
        baseY += (pivot.y - pivot.y * dzoom);
        moveTo(baseX, baseY);
    }

    /**
     * @see #zoomCenteredTo(float, PointF)
     */
    public void zoomCenteredRelativeTo(float dzoom, PointF pivot) {
        zoomCenteredTo(zoom * dzoom, pivot);
    }

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     *
     * @return true if whole document can displayed at once, false otherwise
     */
    public boolean documentFitsView() {
        if (pdfFile == null) {
            return true;
        }
        float len = pdfFile.getDocLen(1);
        if (swipeVertical) {
            return len < getHeight();
        } else {
            return len < getWidth();
        }
    }

    public void fitToWidth(int page) {
        if (state != State.SHOWN) {
            Log.e(TAG, "Cannot fit, document not rendered yet");
            return;
        }
        zoomTo(getWidth() / pdfFile.getPageSize(page).getWidth());
        jumpTo(page);
    }

    public SizeF getPageSize(int pageIndex) {
        if (pdfFile == null) {
            return new SizeF(0, 0);
        }
        return pdfFile.getPageSize(pageIndex);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getVisiblePageIndex() {
        if (pdfFile == null) {
            return currentPage;
        }
        return pdfFile.determineValidPageNumberFrom(findFocusPage(currentXOffset, currentYOffset));
    }

    public float getCurrentXOffset() {
        return currentXOffset;
    }

    public float getCurrentYOffset() {
        return currentYOffset;
    }

    public float toRealScale(float size) {
        return size / zoom;
    }

    public float toCurrentScale(float size) {
        return size * zoom;
    }

    public float getZoom() {
        return zoom;
    }

    private float validZoom(float value) {
        if (!isValidNumber(value)) {
            return NORMAL_SCALE;
        }
        return MathUtils.limit(value, minZoom, maxZoom);
    }

    private static float ratioOrDefault(float numerator, float denominator, float fallback) {
        if (!isUsable(denominator)) {
            return fallback;
        }
        float ratio = numerator / denominator;
        return isValidNumber(ratio) ? ratio : fallback;
    }

    private static boolean isUsable(float value) {
        return isValidNumber(value) && value > 0f;
    }

    private static boolean isValidNumber(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    TextSelectionManager getTextSelectionManager() {
        return textSelectionManager;
    }

    StampPlacementManager getStampPlacementManager() {
        return stampPlacementManager;
    }

    boolean isNightModeEnabled() {
        return nightMode;
    }

    boolean startTextSelectionAt(float viewX, float viewY) {
        return textSelectionManager != null && textSelectionManager.startWordSelectionAt(viewX, viewY);
    }

    public boolean isTextSelectionEnabled() {
        return textSelectionManager != null && textSelectionManager.isEnabled();
    }

    public void setTextSelectionEnabled(boolean enabled) {
        if (textSelectionManager != null) {
            textSelectionManager.setEnabled(enabled);
        }
    }

    public void setTextSelectionColor(int color) {
        if (textSelectionManager != null) {
            textSelectionManager.setSelectionColor(color);
        }
        setSelectedHighlightColor(color);
    }

    private void setSelectedHighlightColor(int color) {
        if (selectedHighlightFillPaint == null || selectedHighlightStrokePaint == null) {
            return;
        }
        selectedHighlightFillPaint.setColor((0x33 << 24) | (color & 0x00FFFFFF));
        selectedHighlightStrokePaint.setColor((0xCC << 24) | (color & 0x00FFFFFF));
    }

    public void setSelectedHighlightAnnotation(HighlightAnnotation annotation) {
        selectedHighlightAnnotation = annotation;
        invalidate();
    }

    public void clearSelectedHighlightAnnotation() {
        if (selectedHighlightAnnotation == null) {
            return;
        }
        selectedHighlightAnnotation = null;
        invalidate();
    }

    public boolean hasTextSelection() {
        return textSelectionManager != null && textSelectionManager.hasSelection();
    }

    public String getSelectedText() {
        if (textSelectionManager == null) {
            return "";
        }
        return textSelectionManager.getSelectedText();
    }

    public HighlightRequest getHighlightRequest() {
        if (textSelectionManager == null) {
            return null;
        }
        return textSelectionManager.getHighlightRequest();
    }

    public boolean addHighlight(HighlightRequest request, int color, String groupKey) {
        return addHighlight(request, color, groupKey, null);
    }

    public boolean addHighlight(HighlightRequest request, int color, String groupKey, String creationDate) {
        if (request == null) {
            return false;
        }
        return addHighlightAnnotation(request.pageIndex, request.pdfRects, color, request.selectedText,
                groupKey, creationDate);
    }

    public boolean addHighlightAnnotation(int pageIndex, List<RectF> pdfRects, int color,
                                          String contents, String groupKey) {
        return addHighlightAnnotation(pageIndex, pdfRects, color, contents, groupKey, null);
    }

    public boolean addHighlightAnnotation(int pageIndex, List<RectF> pdfRects, int color,
                                          String contents, String groupKey, String creationDate) {
        if (pdfFile == null || pdfRects == null || pdfRects.isEmpty()) {
            return false;
        }
        try {
            boolean created = pdfFile.createHighlightAnnotation(pageIndex, pdfRects, color, contents,
                    groupKey, creationDate);
            if (created) {
                overlayContentChanged(pageIndex);
            }
            return created;
        } catch (Throwable throwable) {
            Log.e(TAG, "addHighlightAnnotation: failed to create highlight", throwable);
            return false;
        }
    }

    public static class PendingStamp {
        public final int pageIndex;
        public final RectF pdfRect;
        public final float[][] strokes;
        public final int color;
        public final float normalizedStrokeWidth;

        PendingStamp(int pageIndex, RectF pdfRect, float[][] strokes, int color, float normalizedStrokeWidth) {
            this.pageIndex = pageIndex;
            this.pdfRect = pdfRect;
            this.strokes = strokes;
            this.color = color;
            this.normalizedStrokeWidth = normalizedStrokeWidth;
        }
    }

    public void startStampPlacement(int pageIndex, RectF pdfRect, float[][] strokes, int color,
                                    float normalizedStrokeWidth) {
        if (stampPlacementManager == null) {
            return;
        }
        stampPlacementManager.start(pageIndex, pdfRect, strokes, color, normalizedStrokeWidth);
        invalidate();
    }

    public boolean startStampPlacementAtViewCenter(float[][] strokes, int color,
                                                   float normalizedStrokeWidth, float aspect,
                                                   float pageWidthFraction) {
        if (stampPlacementManager == null || pdfFile == null || aspect <= 0 || pageWidthFraction <= 0) {
            return false;
        }
        float docX = -currentXOffset + getWidth() / 2f;
        float docY = -currentYOffset + getHeight() / 2f;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, zoom);
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return false;
        }
        pdfFile.ensurePageGeometry(page);
        PointF center = pdfFile.documentToPdf(page, zoom, docX, docY);
        if (center == null) {
            return false;
        }
        SizeF frameSize = pdfFile.getPageFrameSize(page);
        float frameWidth = frameSize.getWidth();
        float frameHeight = frameSize.getHeight();
        if (frameWidth <= 0 || frameHeight <= 0) {
            return false;
        }
        PointF frameCenter = pdfFile.userToFrame(page, center.x, center.y);
        float width = pageWidthFraction * frameWidth;
        float height = width * aspect;
        if (height > frameHeight) {
            height = frameHeight;
            width = height / aspect;
        }
        if (width > frameWidth) {
            width = frameWidth;
            height = width * aspect;
        }
        float left = Math.max(0f, Math.min(frameCenter.x - width / 2f, frameWidth - width));
        float top = Math.max(0f, Math.min(frameCenter.y - height / 2f, frameHeight - height));
        RectF rect = pdfFile.frameRectToUser(page, left, top, left + width, top + height);
        startStampPlacement(page, rect, strokes, color, normalizedStrokeWidth);
        return true;
    }

    public void cancelStampPlacement() {
        if (stampPlacementManager == null) {
            return;
        }
        stampPlacementManager.cancel();
        invalidate();
    }

    public void setOnStampPlacementDiscardListener(Runnable listener) {
        this.onStampPlacementDiscardListener = listener;
    }

    void notifyStampPlacementDiscard() {
        if (onStampPlacementDiscardListener != null) {
            onStampPlacementDiscardListener.run();
        }
    }

    public boolean hasPendingStampPlacement() {
        return stampPlacementManager != null && stampPlacementManager.hasPending();
    }

    public PendingStamp getPendingStampPlacement() {
        if (stampPlacementManager == null || !stampPlacementManager.hasPending()) {
            return null;
        }
        return new PendingStamp(stampPlacementManager.getPendingPageIndex(),
                stampPlacementManager.getPendingRect(),
                stampPlacementManager.getPendingStrokes(),
                stampPlacementManager.getPendingColor(),
                stampPlacementManager.getPendingNormalizedStrokeWidth());
    }

    public boolean commitPendingStampPlacement() {
        PendingStamp pending = getPendingStampPlacement();
        if (pending == null) {
            return false;
        }
        boolean added = addSignature(pending.pageIndex, pending.pdfRect, pending.strokes,
                pending.color, pending.normalizedStrokeWidth);
        if (added) {
            stampPlacementManager.cancel();
            invalidate();
        }
        return added;
    }

    public boolean addSignature(int pageIndex, RectF pdfRect, float[][] strokes, int color,
                                float normalizedStrokeWidth) {
        if (pdfFile == null) {
            return false;
        }
        try {
            boolean created = pdfFile.addSignature(pageIndex, pdfRect, strokes, color,
                    normalizedStrokeWidth);
            if (created) {
                pageContentChanged(pageIndex);
            }
            return created;
        } catch (Throwable throwable) {
            Log.e(TAG, "addSignature: failed to add signature", throwable);
            return false;
        }
    }

    public HighlightAnnotation findHighlightAnnotationAt(float viewX, float viewY) {
        if (pdfFile == null) {
            return null;
        }
        float docX = -getCurrentXOffset() + viewX;
        float docY = -getCurrentYOffset() + viewY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, getZoom());
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }

        List<PdfDocument.HighlightAnnotation> annotations;
        try {
            annotations = pdfFile.getHighlightAnnotations(page);
        } catch (Throwable throwable) {
            Log.e(TAG, "findHighlightAnnotationAt: failed to read highlights", throwable);
            return null;
        }
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }
        PointF point = pdfFile.documentToPdf(page, getZoom(), docX, docY);

        PdfDocument.HighlightAnnotation hit = null;
        for (int i = annotations.size() - 1; i >= 0; i--) {
            PdfDocument.HighlightAnnotation candidate = annotations.get(i);
            String group = candidate.getGroupKey();
            if (group == null || group.isEmpty()) {
                continue;
            }
            RectF bounds = candidate.getBounds();
            if (bounds != null && pdfRectContainsPoint(bounds, point.x, point.y, HIGHLIGHT_HIT_TOLERANCE)) {
                hit = candidate;
                break;
            }
        }
        if (hit == null) {
            return null;
        }

        String hitGroup = hit.getGroupKey();
        RectF pdfBounds = unionGroupBounds(annotations, hitGroup, hit.getBounds());
        RectF viewBounds = null;
        if (pdfBounds != null) {
            viewBounds = pdfFile.pdfRectToDocument(
                    page,
                    getZoom(),
                    pdfBounds.left,
                    pdfBounds.bottom,
                    pdfBounds.right,
                    pdfBounds.top
            );
            if (viewBounds != null) {
                viewBounds.offset(getCurrentXOffset(), getCurrentYOffset());
            }
        }
        return new HighlightAnnotation(
                page,
                hit.getAnnotationIndex(),
                hitGroup,
                viewBounds,
                pdfBounds,
                hit.getQuote(),
                hit.getNote()
        );
    }

    public List<PdfDocument.HighlightAnnotation> debugPageHighlights(int pageIndex) {
        if (!debugStateEnabled || pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()) {
            return Collections.emptyList();
        }
        try {
            // Keep the previous snapshot available to UI callbacks while the test hook reads fresh native state.
            if (!pdfFile.refillPageCaches(pageIndex)) {
                return Collections.emptyList();
            }
            List<PdfDocument.HighlightAnnotation> annotations = pdfFile.peekHighlightAnnotations(pageIndex);
            return annotations == null ? Collections.<PdfDocument.HighlightAnnotation>emptyList() : annotations;
        } catch (Throwable throwable) {
            Log.e(TAG, "debugPageHighlights: failed to read highlights", throwable);
            return Collections.emptyList();
        }
    }

    public PdfDocument.FormField debugFormField(int pageIndex, int annotationIndex) {
        if (!debugStateEnabled || pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()) {
            return null;
        }
        try {
            return pdfFile.getFormFieldByIndex(pageIndex, annotationIndex);
        } catch (Throwable throwable) {
            Log.e(TAG, "debugFormField: failed to read form field", throwable);
            return null;
        }
    }

    public RectF findHighlightPdfBounds(int pageIndex, String groupKey, int annotationIndex) {
        if (debugStateEnabled) {
            debugFindHighlightPdfBoundsCalls++;
        }
        if (pdfFile == null || pageIndex < 0 || pageIndex >= pdfFile.getPagesCount()) {
            return null;
        }
        List<PdfDocument.HighlightAnnotation> annotations;
        try {
            pdfFile.invalidateHighlightAnnotationCache(pageIndex);
            annotations = pdfFile.getHighlightAnnotations(pageIndex);
        } catch (Throwable throwable) {
            Log.e(TAG, "findHighlightPdfBounds: failed to read highlights", throwable);
            return null;
        }
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }
        if (groupKey != null && !groupKey.isEmpty()) {
            for (PdfDocument.HighlightAnnotation candidate : annotations) {
                if (groupKey.equals(candidate.getGroupKey())) {
                    return candidate.getBounds();
                }
            }
        }
        for (PdfDocument.HighlightAnnotation candidate : annotations) {
            if (candidate.getAnnotationIndex() == annotationIndex) {
                return candidate.getBounds();
            }
        }
        return null;
    }

    public HighlightAnnotation findHighlightAnnotationMatching(HighlightRequest request) {
        if (pdfFile == null || request == null || request.pdfRects.isEmpty()) {
            return null;
        }
        int page = request.pageIndex;
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }
        List<PdfDocument.HighlightAnnotation> annotations;
        try {
            annotations = pdfFile.getHighlightAnnotations(page);
        } catch (Throwable throwable) {
            Log.e(TAG, "findHighlightAnnotationMatching: failed to read highlights", throwable);
            return null;
        }
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }

        Map<String, List<PdfDocument.HighlightAnnotation>> groups = new HashMap<>();
        for (PdfDocument.HighlightAnnotation candidate : annotations) {
            String group = candidate.getGroupKey();
            if (group == null || group.isEmpty()) {
                continue;
            }
            List<PdfDocument.HighlightAnnotation> members = groups.get(group);
            if (members == null) {
                members = new ArrayList<>();
                groups.put(group, members);
            }
            members.add(candidate);
        }

        for (Map.Entry<String, List<PdfDocument.HighlightAnnotation>> entry : groups.entrySet()) {
            if (!groupMatchesRects(entry.getValue(), request.pdfRects)) {
                continue;
            }
            PdfDocument.HighlightAnnotation first = entry.getValue().get(0);
            String group = entry.getKey();
            RectF pdfBounds = unionGroupBounds(annotations, group, first.getBounds());
            RectF viewBounds = null;
            if (pdfBounds != null) {
                viewBounds = pdfFile.pdfRectToDocument(
                        page,
                        getZoom(),
                        pdfBounds.left,
                        pdfBounds.bottom,
                        pdfBounds.right,
                        pdfBounds.top
                );
                if (viewBounds != null) {
                    viewBounds.offset(getCurrentXOffset(), getCurrentYOffset());
                }
            }
            return new HighlightAnnotation(
                    page,
                    first.getAnnotationIndex(),
                    group,
                    viewBounds,
                    pdfBounds,
                    first.getQuote(),
                    first.getNote()
            );
        }
        return null;
    }

    private static boolean groupMatchesRects(List<PdfDocument.HighlightAnnotation> members, List<RectF> rects) {
        if (members.size() != rects.size()) {
            return false;
        }
        boolean[] used = new boolean[members.size()];
        for (RectF rect : rects) {
            boolean matched = false;
            for (int i = 0; i < members.size(); i++) {
                if (used[i]) {
                    continue;
                }
                RectF bounds = members.get(i).getBounds();
                if (bounds != null && pdfRectsAlmostEqual(rect, bounds, HIGHLIGHT_MATCH_TOLERANCE)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean pdfRectsAlmostEqual(RectF a, RectF b, float tolerance) {
        return Math.abs(Math.min(a.left, a.right) - Math.min(b.left, b.right)) <= tolerance
                && Math.abs(Math.max(a.left, a.right) - Math.max(b.left, b.right)) <= tolerance
                && Math.abs(Math.min(a.top, a.bottom) - Math.min(b.top, b.bottom)) <= tolerance
                && Math.abs(Math.max(a.top, a.bottom) - Math.max(b.top, b.bottom)) <= tolerance;
    }

    private static boolean pdfRectContainsPoint(RectF rect, float x, float y, float tolerance) {
        float left = Math.min(rect.left, rect.right);
        float right = Math.max(rect.left, rect.right);
        float bottom = Math.min(rect.top, rect.bottom);
        float top = Math.max(rect.top, rect.bottom);
        return x >= left - tolerance && x <= right + tolerance
                && y >= bottom - tolerance && y <= top + tolerance;
    }

    private static RectF unionGroupBounds(List<PdfDocument.HighlightAnnotation> annotations,
                                          String group, RectF fallback) {
        if (group == null || group.isEmpty()) {
            return fallback;
        }
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float top = -Float.MAX_VALUE;
        float bottom = Float.MAX_VALUE;
        boolean found = false;
        for (PdfDocument.HighlightAnnotation candidate : annotations) {
            if (!group.equals(candidate.getGroupKey())) {
                continue;
            }
            RectF b = candidate.getBounds();
            if (b == null) {
                continue;
            }
            left = Math.min(left, Math.min(b.left, b.right));
            right = Math.max(right, Math.max(b.left, b.right));
            top = Math.max(top, Math.max(b.top, b.bottom));
            bottom = Math.min(bottom, Math.min(b.top, b.bottom));
            found = true;
        }
        if (!found) {
            return fallback;
        }
        return new RectF(left, top, right, bottom);
    }

    public boolean setHighlightAnnotationColor(HighlightAnnotation annotation, int color) {
        if (pdfFile == null || annotation == null) {
            return false;
        }
        try {
            boolean updated = pdfFile.setHighlightAnnotationColor(
                    annotation.pageIndex,
                    annotation.annotationIndex,
                    annotation.groupKey,
                    color
            );
            if (updated) {
                overlayContentChanged(annotation.pageIndex);
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setHighlightAnnotationColor: failed to update highlight", throwable);
            return false;
        }
    }

    public boolean setHighlightAnnotationNote(HighlightAnnotation annotation, String note, String modifiedDate) {
        if (pdfFile == null || annotation == null) {
            return false;
        }
        try {
            boolean updated = pdfFile.setHighlightAnnotationNote(
                    annotation.pageIndex,
                    annotation.annotationIndex,
                    annotation.groupKey,
                    note,
                    modifiedDate
            );
            if (updated) {
                overlayContentChanged(annotation.pageIndex);
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setHighlightAnnotationNote: failed to update highlight", throwable);
            return false;
        }
    }

    public boolean removeHighlightAnnotation(HighlightAnnotation annotation) {
        if (pdfFile == null || annotation == null) {
            return false;
        }
        try {
            boolean removed = pdfFile.removeHighlightAnnotation(
                    annotation.pageIndex,
                    annotation.annotationIndex,
                    annotation.groupKey
            );
            if (removed) {
                overlayContentChanged(annotation.pageIndex);
            }
            return removed;
        } catch (Throwable throwable) {
            Log.e(TAG, "removeHighlightAnnotation: failed to remove highlight", throwable);
            return false;
        }
    }

    public FormField findFormFieldAt(float viewX, float viewY) {
        if (pdfFile == null) {
            return null;
        }
        float docX = -getCurrentXOffset() + viewX;
        float docY = -getCurrentYOffset() + viewY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? docY : docX,
                isSwipeVertical() ? docX : docY, getZoom());
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return null;
        }

        pdfFile.ensurePageGeometry(page);
        PointF point = pdfFile.documentToPdf(page, getZoom(), docX, docY);
        try {
            float tolerance = formFieldTouchTolerance(page, docX, docY, point);
            float[] rects = pdfFile.peekFormFieldRects(page);
            if (rects != null && !pointNearFormFieldRect(rects, point.x, point.y, tolerance)) {
                return null;
            }
            PdfDocument.FormField field = pdfFile.getFormFieldAtPoint(page, point.x, point.y, tolerance);
            return field == null ? null : new FormField(page, field);
        } catch (Throwable throwable) {
            Log.e(TAG, "findFormFieldAt: failed to hit-test form field", throwable);
            return null;
        }
    }

    private static boolean pointNearFormFieldRect(float[] rects, float pdfX, float pdfY, float tolerance) {
        for (int i = 0; i + 3 < rects.length; i += 4) {
            float left = Math.min(rects[i], rects[i + 2]) - tolerance;
            float right = Math.max(rects[i], rects[i + 2]) + tolerance;
            float bottom = Math.min(rects[i + 1], rects[i + 3]) - tolerance;
            float top = Math.max(rects[i + 1], rects[i + 3]) + tolerance;
            if (pdfX >= left && pdfX <= right && pdfY >= bottom && pdfY <= top) {
                return true;
            }
        }
        return false;
    }

    private float formFieldTouchTolerance(int page, float docX, float docY, PointF pdfPoint) {
        float tolerancePx = FORM_FIELD_TOUCH_TOLERANCE_DP * getResources().getDisplayMetrics().density;
        PointF shifted = pdfFile.documentToPdf(page, getZoom(), docX + tolerancePx, docY);
        return (float) Math.hypot(shifted.x - pdfPoint.x, shifted.y - pdfPoint.y);
    }

    public boolean setFormFieldText(int pageIndex, int annotationIndex, String text) {
        if (pdfFile == null) {
            return false;
        }
        try {
            boolean updated = pdfFile.setFormFieldText(pageIndex, annotationIndex, text);
            if (updated) {
                pageContentChanged(pageIndex);
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setFormFieldText: failed to update form field", throwable);
            return false;
        }
    }

    public boolean setFormFieldChecked(int pageIndex, int annotationIndex, boolean checked) {
        if (pdfFile == null) {
            return false;
        }
        try {
            boolean updated = pdfFile.setFormFieldChecked(pageIndex, annotationIndex, checked);
            if (updated) {
                pageContentChanged(pageIndex);
            }
            return updated;
        } catch (Throwable throwable) {
            Log.e(TAG, "setFormFieldChecked: failed to update form field", throwable);
            return false;
        }
    }

    public boolean saveAsCopy(File outputFile) throws IOException {
        if (pdfFile == null || outputFile == null) {
            return false;
        }
        return pdfFile.saveAsCopy(outputFile);
    }

    public boolean saveDecryptedCopy(File outputFile) throws IOException {
        if (pdfFile == null || outputFile == null) {
            return false;
        }
        return pdfFile.saveDecryptedCopy(outputFile);
    }

    public void clearTextSelection() {
        if (textSelectionManager != null) {
            textSelectionManager.clear();
        }
    }

    public boolean isZooming() {
        return zoom != minZoom;
    }

    public void setDefaultPage(int defaultPage) {   // changed by User
        this.defaultPage = defaultPage;
    }

    private void setDefaultViewState(ViewState defaultViewState) {
        this.defaultViewState = defaultViewState;
    }

    public void resetZoom() {
        zoomTo(NORMAL_SCALE);
    }

    public void resetZoomWithAnimation() {
        zoomWithAnimation(NORMAL_SCALE);   // mudlej: I think double tap should always reset to 1f rather than the min zoom
    }

    public void resetZoomToFitPageWithAnimation() {
        if (pdfFile == null || swipeVertical || getHeight() <= 0) {
            resetZoomWithAnimation();
            return;
        }
        float pageHeight = pdfFile.getPageSize(currentPage).getHeight();
        if (pageHeight <= 0) {
            resetZoomWithAnimation();
            return;
        }
        float fitHeightZoom = getHeight() / pageHeight;
        zoomWithAnimation(MathUtils.limit(fitHeightZoom, minZoom, NORMAL_SCALE));
    }

    public void zoomWithAnimation(float centerX, float centerY, float scaleTo) {
        animationManager.startZoomAnimation(centerX, centerY, zoom, validZoom(scaleTo));
    }

    public boolean performLinkTap(float x, float y) {
        LinkTapEvent event = findLinkAt(x, y);
        if (event == null) {
            return false;
        }
        callbacks.callLinkHandler(event);
        return true;
    }

    public RectF focusOnPdfRect(int pageIndex, RectF pdfRect, float targetZoom) {
        if (pdfFile == null || pdfRect == null || getWidth() == 0 || getHeight() == 0) {
            return null;
        }
        if (targetZoom < zoom) {
            targetZoom = zoom;
        }
        RectF docRect = pdfFile.pdfRectToDocument(pageIndex, targetZoom,
                pdfRect.left, pdfRect.bottom, pdfRect.right, pdfRect.top, false);
        if (docRect == null) {
            return null;
        }
        float targetXOffset = clampTargetXOffset(-(docRect.centerX() - getWidth() / 2f), targetZoom);
        float targetYOffset = clampTargetYOffset(-(docRect.centerY() - getHeight() / 2f), targetZoom);
        float scale = targetZoom / zoom;
        if (scale > 1.001f) {
            float pivotX = (targetXOffset - currentXOffset * scale) / (1f - scale);
            float pivotY = (targetYOffset - currentYOffset * scale) / (1f - scale);
            animationManager.startZoomAnimation(pivotX, pivotY, zoom, targetZoom,
                    new PointF(targetXOffset, targetYOffset));
        } else {
            moveTo(targetXOffset, targetYOffset);
            loadPages();
        }
        return new RectF(
                docRect.left + targetXOffset,
                docRect.top + targetYOffset,
                docRect.right + targetXOffset,
                docRect.bottom + targetYOffset
        );
    }

    private float clampTargetXOffset(float offsetX, float targetZoom) {
        if (swipeVertical) {
            float scaledPageWidth = pdfFile.getMaxPageWidth() * targetZoom;
            if (scaledPageWidth < getWidth()) {
                return getWidth() / 2f - scaledPageWidth / 2f;
            }
            return Math.max(Math.min(offsetX, 0), getWidth() - scaledPageWidth);
        }
        float contentWidth = pdfFile.getDocLen(targetZoom);
        if (contentWidth < getWidth()) {
            return (getWidth() - contentWidth) / 2f;
        }
        return Math.max(Math.min(offsetX, 0), getWidth() - contentWidth);
    }

    private float clampTargetYOffset(float offsetY, float targetZoom) {
        if (swipeVertical) {
            float contentHeight = pdfFile.getDocLen(targetZoom);
            if (contentHeight < getHeight()) {
                return (getHeight() - contentHeight) / 2f;
            }
            return Math.max(Math.min(offsetY, 0), getHeight() - contentHeight);
        }
        float scaledPageHeight = pdfFile.getMaxPageHeight() * targetZoom;
        if (scaledPageHeight < getHeight()) {
            return getHeight() / 2f - scaledPageHeight / 2f;
        }
        return Math.max(Math.min(offsetY, 0), getHeight() - scaledPageHeight);
    }

    public void zoomWithAnimation(float scale) {
        animationManager.startZoomAnimation((float) getWidth() / 2, (float) getHeight() / 2, zoom, validZoom(scale));
    }

    private void setScrollHandle(ScrollHandle scrollHandle) {
        this.scrollHandle = scrollHandle;
    }

    /**
     * Get page number at given offset
     *
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    public int getPageAtPositionOffset(float positionOffset) {
        if (pdfFile == null) {
            return 0;
        }
        return pdfFile.getPageAtOffset(pdfFile.getDocLen(zoom) * positionOffset, zoom);
    }

    public float getMinZoom() {
        return minZoom;
    }

    public void setMinZoom(float minZoom) {
        this.minZoom = minZoom;
    }

    public float getMidZoom() {
        return midZoom;
    }

    public void setMidZoom(float midZoom) {
        this.midZoom = midZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public void setMaxZoom(float maxZoom) {
        this.maxZoom = maxZoom;
    }

    public void useBestQuality(boolean bestQuality) {
        this.bestQuality = bestQuality;
    }

    public boolean isBestQuality() {
        return bestQuality;
    }

    public boolean isSwipeVertical() {
        return swipeVertical;
    }

    public boolean isSwipeEnabled() {
        return enableSwipe;
    }

    public boolean isHorizontalSwipeDisabled() {
        return horizontalSwipeDisabled;
    }

    public boolean isZoomDisabled() {
        return zoomDisabled;
    }

    private void setSwipeVertical(boolean swipeVertical) {
        this.swipeVertical = swipeVertical;
    }

    private void setHorizontalReadingDirectionRtl(boolean horizontalReadingDirectionRtl) {
        this.horizontalReadingDirectionRtl = horizontalReadingDirectionRtl;
    }

    public boolean isHorizontalReadingDirectionRtl() {
        return !swipeVertical && horizontalReadingDirectionRtl;
    }

    public void enableAnnotationRendering(boolean annotationRendering) {
        this.annotationRendering = annotationRendering;
    }

    public boolean isAnnotationRendering() {
        return annotationRendering;
    }

    public void enableRenderDuringScale(boolean renderDuringScale) {
        this.renderDuringScale = renderDuringScale;
    }

    public boolean isAntialiasing() {
        return enableAntialiasing;
    }

    public void enableAntialiasing(boolean enableAntialiasing) {
        this.enableAntialiasing = enableAntialiasing;
    }

    public int getSpacingPx() {
        return spacingPx;
    }

    public boolean isAutoSpacingEnabled() {
        return autoSpacing;
    }

    public void setPageFling(boolean pageFling) {
        this.pageFling = pageFling;
    }

    public boolean isPageFlingEnabled() {
        return pageFling;
    }

    private void setSpacing(int spacingDp) {
        this.spacingPx = Util.getDP(getContext(), spacingDp);
    }

    private void setAutoSpacing(boolean autoSpacing) {
        this.autoSpacing = autoSpacing;
    }

    private void setPagesPerRow(int pagesPerRow) {
        this.pagesPerRow = pagesPerRow;
    }

    public int getPagesPerRow() {
        return pagesPerRow;
    }

    private void setFirstPageAlone(boolean firstPageAlone) {
        this.firstPageAlone = firstPageAlone;
    }

    public boolean isFirstPageAlone() {
        return firstPageAlone;
    }

    private void setAutoReleasingWhenDetachedFromWindow(boolean autoReleasing) {
        this.autoReleasingWhenDetachedFromWindow = autoReleasing;
    }

    private void setPageFitPolicy(FitPolicy pageFitPolicy) {
        this.pageFitPolicy = pageFitPolicy;
    }

    public FitPolicy getPageFitPolicy() {
        return pageFitPolicy;
    }

    private void setFitEachPage(boolean fitEachPage) {
        this.fitEachPage = fitEachPage;
    }

    public boolean isFitEachPage() {
        return fitEachPage;
    }

    private void setThreeStepDoubleTapZoom(boolean threeStepDoubleTapZoom) {
        this.threeStepDoubleTapZoom = threeStepDoubleTapZoom;
    }

    public boolean isThreeStepDoubleTapZoom() {
        return threeStepDoubleTapZoom;
    }

    private void setCropMargins(boolean cropMargins) {
        this.cropMargins = cropMargins;
    }

    public boolean isCropMarginsEnabled() {
        return cropMargins;
    }

    private void setCachedCropMargins(CropMargins cachedCropMargins) {
        this.cachedCropMargins = cachedCropMargins;
    }

    CropMargins getCachedCropMargins() {
        return cachedCropMargins;
    }

    public boolean isPageSnap() {
        return pageSnap;
    }

    public void setPageSnap(boolean pageSnap) {
        this.pageSnap = pageSnap;
    }

    public boolean isFreeScrollMode() {
        return freeScrollMode;
    }

    private void setFreeScrollMode(boolean freeScrollMode) {
        this.freeScrollMode = freeScrollMode;
    }

    public boolean doRenderDuringScale() {
        return renderDuringScale;
    }

    /**
     * Returns null if document is not loaded
     */
    public PdfDocument.Meta getDocumentMeta() {
        if (pdfFile == null) {
            Log.e(TAG, "getDocumentMeta: pdfFile is null!");
            return null;
        }
        return pdfFile.getMetaData();
    }

    /**
     * Will be empty until document is loaded
     */
    public List<PdfDocument.Bookmark> getTableOfContents() {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getBookmarks();
    }

    public SizeF getPagePointSize(int pageIndex) {
        if (pdfFile == null) {
            return null;
        }
        return pdfFile.getPagePointSize(pageIndex);
    }

    public List<PdfDocument.FontInfo> getAllFonts(int maxPages) {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getAllFonts(maxPages);
    }

    /**
     * Will be empty until document is loaded
     */
    public List<PdfDocument.Link> getLinks(int page) {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getPageLinks(page);
    }


    /**
     * Get the text of page
     */     // added by Mudlej
    public String getPageText(int pagNumber) {
        if (pdfFile == null) {
            return "";
        }
        return pdfFile.getPageText(pagNumber);
    }

    public String getPageRawText(int pageNumber) {
        if (pdfFile == null) {
            return "";
        }
        return pdfFile.getPageRawText(pageNumber - 1);
    }

    public Rect[] createHighlightText(int pageNumber, int start, int end, Boolean padding) {
        Rect[] emptyArray = new Rect[0];
        if (pdfFile == null) {
            return emptyArray;
        }
        try {
            int pageIndex = pageNumber - 1;
            Rect[] result = pdfFile.createHighlightText(pageIndex, start, end, padding);
            if (result != null && result.length > 0) {
                synchronized (searchMarkerPages) {
                    searchMarkerPages.add(pageIndex);
                }
                pageContentChanged(pageIndex);
            }
            recordDebugSearchHighlight(pageIndex, result);
            return result == null ? emptyArray : result;
        } catch (Throwable throwable) {
            Log.e(TAG, "createHighlightText: An error occurred while highlight search result", throwable);
            recordDebugSearchHighlight(pageNumber - 1, emptyArray);
            return emptyArray;
        }
    }

    public void clearSearchResultsHighlight(int pageNumber) {
        if (pdfFile == null) {
            return;
        }
        List<Integer> pages;
        synchronized (searchMarkerPages) {
            if (pageNumber - 1 >= 0) {
                searchMarkerPages.add(pageNumber - 1);
            }
            if (searchMarkerPages.isEmpty()) {
                return;
            }
            pages = new ArrayList<>(searchMarkerPages);
        }
        for (Integer page : pages) {
            pdfFile.clearSearchResultsAnnot(page);
            invalidatePageContent(page);
        }
        synchronized (searchMarkerPages) {
            searchMarkerPages.removeAll(pages);
        }
        loadPages();
    }

    /**
     * Get the text of page
     */     // added by Mudlej
    public Map<Integer, String> getPagesText(int start, int end) {
        if (pdfFile == null) {
            return new HashMap<>();
        }

        return pdfFile.getPagesText(start, end);
    }

    /**
     * Use an asset file as the pdf source
     */
    public Configurator fromAsset(String assetName) {
        return new Configurator(new AssetSource(assetName));
    }

    /**
     * Use a file as the pdf source
     */
    public Configurator fromFile(File file) {
        return new Configurator(new FileSource(file));
    }

    /**
     * Use URI as the pdf source, for use with content providers
     */
    public interface MainThreadViolationReporter {
        void onMainThreadPdfiumWork(String path, Throwable stack);
    }

    public static void setMainThreadViolationReporter(MainThreadViolationReporter reporter) {
        PdfFile.setMainThreadViolationReporter(reporter);
    }

    public Configurator fromUri(Uri uri) {
        return new Configurator(new UriSource(uri));
    }

    /**
     * Use bytearray as the pdf source, documents is not saved
     */
    public Configurator fromBytes(byte[] bytes) {
        return new Configurator(new ByteArraySource(bytes));
    }

    /**
     * Use stream as the pdf source. Stream will be written to bytearray, because native code does not support Java Streams
     */
    public Configurator fromStream(InputStream stream) {
        return new Configurator(new InputStreamSource(stream));
    }

    /**
     * Use custom source as pdf source
     */
    public Configurator fromSource(DocumentSource docSource) {
        return new Configurator(docSource);
    }

    public interface DebugStateSource {

        String loadState();

        String fileHash();

        String docUri();

        boolean autoScrolling();

        boolean saving();

        boolean dirty();

        long dwellMs();
    }

    private static boolean debugStateEnabled = false;

    private DebugStateSource debugStateSource;

    private boolean debugStatePublishPending;

    private int debugFindHighlightPdfBoundsCalls;

    private int debugSearchHighlightPage = -1;

    private Rect[] debugSearchHighlightRects;

    public static void enableDebugState(boolean enabled) {
        debugStateEnabled = enabled;
    }

    public static boolean isDebugStateEnabled() {
        return debugStateEnabled;
    }

    public void attachDebugStateSource(DebugStateSource source) {
        this.debugStateSource = source;
        publishDebugState();
    }

    private final Runnable debugStatePublishRunnable = new Runnable() {
        @Override
        public void run() {
            debugStatePublishPending = false;
            if (!debugStateEnabled) {
                return;
            }
            setContentDescription(debugState());
        }
    };

    void publishDebugState() {
        if (!debugStateEnabled || debugStatePublishPending) {
            return;
        }
        debugStatePublishPending = true;
        post(debugStatePublishRunnable);
    }

    public String debugState() {
        if (!debugStateEnabled) {
            return "";
        }
        StringBuilder out = new StringBuilder(768);
        appendDebugDocument(out);
        appendDebugRender(out);
        appendDebugMotion(out);
        appendDebugLayout(out);
        appendDebugGeometry(out);
        appendDebugContent(out);
        if (textSelectionManager != null) {
            textSelectionManager.appendDebugState(out);
        }
        return out.toString();
    }

    private void appendDebugDocument(StringBuilder out) {
        DebugStateSource source = debugStateSource;
        debugPut(out, "page", currentPage);
        debugPut(out, "pageCount", getPageCount());
        debugPut(out, "focusPage", getVisiblePageIndex());
        debugPut(out, "loadState", source == null ? "" : debugText(source.loadState()));
        debugPut(out, "fileHash", source == null ? "" : debugText(source.fileHash()));
        debugPut(out, "docUri", source == null ? "" : debugText(source.docUri()));
    }

    private void appendDebugRender(StringBuilder out) {
        debugPut(out, "state", state.name());
        debugPut(out, "render", isDebugRenderIdle() ? "idle" : "busy");
        debugPut(out, "quality", debugQuality());
        debugPut(out, "renderPending", debugPendingTiles());
        debugPut(out, "pageGen", getPageGeneration(currentPage));
    }

    private void appendDebugMotion(StringBuilder out) {
        debugPut(out, "zoom", debugFloat(zoom));
        debugPut(out, "minZoom", debugFloat(minZoom));
        debugPut(out, "midZoom", debugFloat(midZoom));
        debugPut(out, "maxZoom", debugFloat(maxZoom));
        debugPut(out, "offsetX", debugFloat(currentXOffset));
        debugPut(out, "offsetY", debugFloat(currentYOffset));
        debugPut(out, "positionOffset", debugFloat(getPositionOffset()));
        ViewState viewState = captureViewState();
        debugPut(out, "pageOffsetRatio", viewState == null ? "" : debugFloat(viewState.pageCenterOffsetRatio));
        debugPut(out, "animating", isAnimating());
        debugPut(out, "flinging", isFlinging());
        if (pdfFile == null) {
            debugPut(out, "snapAligned", false);
            debugPut(out, "snapDelta", "");
            return;
        }
        float delta = debugSnapDelta();
        debugPut(out, "snapAligned", pageSnap && Math.abs(delta) <= 1f);
        debugPut(out, "snapDelta", debugFloat(delta));
    }

    private float debugSnapDelta() {
        int focus = getVisiblePageIndex();
        float target = -snapOffsetForPage(focus, findSnapEdge(focus));
        return (swipeVertical ? currentYOffset : currentXOffset) - target;
    }

    private void appendDebugLayout(StringBuilder out) {
        debugPut(out, "swipeVertical", swipeVertical);
        debugPut(out, "pagesPerRow", pdfFile == null ? pagesPerRow : pdfFile.getPagesPerRow());
        debugPut(out, "firstPageAlone", pdfFile == null ? firstPageAlone : pdfFile.isFirstPageAlone());
        debugPut(out, "pageSnap", pageSnap);
        debugPut(out, "pageFling", pageFling);
        debugPut(out, "freeScroll", freeScrollMode);
        debugPut(out, "fitPolicy", pageFitPolicy.name());
        debugPut(out, "rtl", isHorizontalReadingDirectionRtl());
        debugPut(out, "spacing", spacingPx);
        debugPut(out, "rowFirst", getRowFirstPage(currentPage));
        debugPut(out, "rowLast", getRowLastPage(currentPage));
        debugPut(out, "zoomLocked", zoomDisabled);
        debugPut(out, "hSwipeLocked", horizontalSwipeDisabled);
    }

    private void appendDebugGeometry(StringBuilder out) {
        debugPut(out, "viewW", getWidth());
        debugPut(out, "viewH", getHeight());
        if (pdfFile == null) {
            debugPut(out, "docLen", "");
            debugPut(out, "maxPageW", "");
            debugPut(out, "maxPageH", "");
            debugPut(out, "pageW", "");
            debugPut(out, "pageH", "");
            debugPut(out, "pageRect", "");
            debugPut(out, "snapOffset", "");
            return;
        }
        SizeF pageSize = pdfFile.getScaledPageSize(currentPage, zoom);
        float originX = swipeVertical
                ? pdfFile.getSecondaryPageOffset(currentPage, zoom)
                : pdfFile.getPageOffset(currentPage, zoom);
        float originY = swipeVertical
                ? pdfFile.getPageOffset(currentPage, zoom)
                : pdfFile.getSecondaryPageOffset(currentPage, zoom);
        RectF pageRect = new RectF(
                originX + currentXOffset,
                originY + currentYOffset,
                originX + currentXOffset + pageSize.getWidth(),
                originY + currentYOffset + pageSize.getHeight()
        );
        int focus = getVisiblePageIndex();
        debugPut(out, "docLen", debugFloat(pdfFile.getDocLen(zoom)));
        debugPut(out, "maxPageW", debugFloat(pdfFile.getMaxPageWidth()));
        debugPut(out, "maxPageH", debugFloat(pdfFile.getMaxPageHeight()));
        debugPut(out, "pageW", debugFloat(pageSize.getWidth()));
        debugPut(out, "pageH", debugFloat(pageSize.getHeight()));
        debugPut(out, "pageRect", debugRect(pageRect));
        debugPut(out, "snapOffset", debugFloat(snapOffsetForPage(focus, findSnapEdge(focus))));
    }

    private void appendDebugContent(StringBuilder out) {
        DebugStateSource source = debugStateSource;
        List<PdfDocument.HighlightAnnotation> annotations =
                pdfFile == null ? null : pdfFile.peekHighlightAnnotations(currentPage);
        debugPut(out, "annotations", annotations == null ? "" : Integer.toString(annotations.size()));
        debugPut(out, "autoScrolling", source != null && source.autoScrolling());
        debugPut(out, "saving", source != null && source.saving());
        debugPut(out, "dirty", source != null && source.dirty());
        debugPut(out, "markerPages", debugMarkerPages());
        debugPut(out, "dwellMs", source == null ? "" : Long.toString(source.dwellMs()));
        debugPut(out, "hlBoundsCalls", debugFindHighlightPdfBoundsCalls);
        debugPut(out, "groupCacheMisses",
                pdfFile == null ? "" : Integer.toString(pdfFile.debugHighlightGroupCacheMisses));
    }

    private String debugMarkerPages() {
        StringBuilder pages = new StringBuilder();
        synchronized (searchMarkerPages) {
            for (Integer page : searchMarkerPages) {
                if (pages.length() > 0) {
                    pages.append(',');
                }
                pages.append(page);
            }
        }
        return pages.toString();
    }

    private boolean isDebugRenderIdle() {
        if (state != State.SHOWN) {
            return false;
        }
        RenderScheduler scheduler = renderScheduler;
        if (scheduler == null || scheduler.debugWaveOpen() || scheduler.debugPendingTiles() > 0) {
            return false;
        }
        if (!"full".equals(debugQuality())) {
            return false;
        }
        return !isAnimating() && !isFlinging();
    }

    private String debugQuality() {
        if (pdfFile == null || state != State.SHOWN) {
            return "none";
        }
        return pagesLoader.debugQuality();
    }

    private int debugPendingTiles() {
        RenderScheduler scheduler = renderScheduler;
        return scheduler == null ? 0 : scheduler.debugPendingTiles();
    }

    static void debugPut(StringBuilder out, String key, String value) {
        if (out.length() > 0) {
            out.append(';');
        }
        out.append(key).append('=').append(value);
    }

    static void debugPut(StringBuilder out, String key, int value) {
        debugPut(out, key, Integer.toString(value));
    }

    static void debugPut(StringBuilder out, String key, boolean value) {
        debugPut(out, key, value ? "true" : "false");
    }

    static String debugFloat(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    static String debugRect(RectF rect) {
        return debugFloat(rect.left) + ',' + debugFloat(rect.top) + ','
                + debugFloat(rect.right) + ',' + debugFloat(rect.bottom);
    }

    public void debugSetViewport(float zoom, float offsetX, float offsetY) {
        if (!debugStateEnabled || pdfFile == null) {
            return;
        }
        animationManager.stopAll();
        zoomTo(zoom);
        moveTo(offsetX, offsetY);
        loadPageByOffset();
        loadPages();
        publishDebugState();
    }

    public void debugSetZoomAtPageOrigin(int pageIndex, float zoom) {
        if (!debugStateEnabled || pdfFile == null) {
            return;
        }
        int page = pdfFile.determineValidPageNumberFrom(pageIndex);
        float primary = pdfFile.getPageOffset(page, zoom);
        float secondary = pdfFile.getSecondaryPageOffset(page, zoom);
        if (swipeVertical) {
            debugSetViewport(zoom, -secondary, -primary);
        } else {
            debugSetViewport(zoom, -primary, -secondary);
        }
    }

    private void recordDebugSearchHighlight(int pageIndex, Rect[] rects) {
        if (!debugStateEnabled) {
            return;
        }
        debugSearchHighlightPage = pageIndex;
        debugSearchHighlightRects = rects;
    }

    public String debugSearchHighlightBounds() {
        if (!debugStateEnabled) {
            return "";
        }
        StringBuilder out = new StringBuilder(128);
        debugPut(out, "page", debugSearchHighlightPage);
        debugPut(out, "count", debugSearchHighlightRects == null ? 0 : debugSearchHighlightRects.length);
        debugPut(out, "rects", debugSearchHighlightRectList());
        return out.toString();
    }

    private String debugSearchHighlightRectList() {
        if (debugSearchHighlightRects == null) {
            return "";
        }
        StringBuilder rects = new StringBuilder();
        for (Rect rect : debugSearchHighlightRects) {
            if (rect == null) {
                continue;
            }
            if (rects.length() > 0) {
                rects.append('|');
            }
            rects.append(rect.left).append(',').append(rect.top).append(',')
                    .append(rect.right).append(',').append(rect.bottom);
        }
        return rects.toString();
    }

    public static final class DebugCacheStats {

        public final boolean documentOpen;

        public final boolean previewAttached;

        public final int activePartCount;

        public final int passivePartCount;

        public final long activePartBytes;

        public final long passivePartBytes;

        public final boolean partCacheScaling;

        public final int partCacheLimit;

        public final int snapshotCacheLimit;

        public final float partSizePx;

        public final int previewEntryCount;

        public final long previewBytes;

        public final long previewMaxBytes;

        public final int previewBucketWidthPx;

        public final int previewPoolPerBucketCap;

        public final int[] previewPoolBucketWidths;

        public final int[] previewPoolBucketSizes;

        public final int openPageCount;

        public final int openedPageEntryCount;

        public final int renderPageCount;

        public final int textOpenedPageCount;

        public final int pinnedRenderPage;

        public final int maxOpenRenderPages;

        DebugCacheStats(boolean documentOpen, boolean previewAttached, long[] partStats,
                        long[] previewStats, int[] poolStats, int poolCap, int[] pageStats) {
            this.documentOpen = documentOpen;
            this.previewAttached = previewAttached;
            this.activePartCount = (int) partStats[0];
            this.passivePartCount = (int) partStats[1];
            this.activePartBytes = partStats[2];
            this.passivePartBytes = partStats[3];
            this.partCacheScaling = partStats[4] != 0;
            this.partCacheLimit = Constants.Cache.CACHE_SIZE;
            this.snapshotCacheLimit = Constants.Cache.SNAPSHOTS_CACHE_SIZE;
            this.partSizePx = Constants.PART_SIZE;
            this.previewEntryCount = (int) previewStats[0];
            this.previewBytes = previewStats[1];
            this.previewMaxBytes = previewStats[2];
            this.previewBucketWidthPx = (int) previewStats[3];
            this.previewPoolPerBucketCap = poolCap;
            this.previewPoolBucketWidths = new int[poolStats.length / 2];
            this.previewPoolBucketSizes = new int[poolStats.length / 2];
            for (int i = 0; i < poolStats.length / 2; i++) {
                this.previewPoolBucketWidths[i] = poolStats[i * 2];
                this.previewPoolBucketSizes[i] = poolStats[i * 2 + 1];
            }
            this.openPageCount = pageStats[0];
            this.openedPageEntryCount = pageStats[1];
            this.renderPageCount = pageStats[2];
            this.textOpenedPageCount = pageStats[3];
            this.pinnedRenderPage = pageStats[4];
            this.maxOpenRenderPages = pageStats[5];
        }

        public int totalPartCount() {
            return activePartCount + passivePartCount;
        }

        public long totalPartBytes() {
            return activePartBytes + passivePartBytes;
        }

        public int previewPoolSize(int bucketWidthPx) {
            for (int i = 0; i < previewPoolBucketWidths.length; i++) {
                if (previewPoolBucketWidths[i] == bucketWidthPx) {
                    return previewPoolBucketSizes[i];
                }
            }
            return 0;
        }

        public int previewPoolLargestBucketSize() {
            int largest = 0;
            for (int size : previewPoolBucketSizes) {
                if (size > largest) {
                    largest = size;
                }
            }
            return largest;
        }

        public int previewPoolTotalSize() {
            int total = 0;
            for (int size : previewPoolBucketSizes) {
                total += size;
            }
            return total;
        }
    }

    /**
     * Peak recorded immediately after a page-part insertion while scaling. CacheManager removes an equal part
     * from both queues before insertion, so non-zero active and passive tile counts describe disjoint current
     * and previous tile sets rather than duplicate accounting.
     */
    public static final class DebugPartCachePeak {

        public final long observationId;
        public final int activePartCount;
        public final int passivePartCount;
        public final int activeTileCount;
        public final int passiveTileCount;
        public final int totalPartCount;
        public final int scalingInsertions;

        DebugPartCachePeak(long[] stats) {
            this.observationId = stats[0];
            this.activePartCount = (int) stats[1];
            this.passivePartCount = (int) stats[2];
            this.activeTileCount = (int) stats[3];
            this.passiveTileCount = (int) stats[4];
            this.totalPartCount = (int) stats[5];
            this.scalingInsertions = (int) stats[6];
        }
    }

    private static final long[] NO_PART_STATS = new long[]{0, 0, 0, 0, 0};

    private static final long[] NO_PREVIEW_STATS = new long[]{0, 0, 0, 0};

    private static final int[] NO_POOL_STATS = new int[0];

    private static final int[] NO_PAGE_STATS = new int[]{0, 0, 0, 0, -1, 0};

    public static int debugPreviewReuseRecycles() {
        if (!debugStateEnabled) {
            return 0;
        }
        return AndroidPreviewBitmaps.debugReuseRecycles.get();
    }

    public static void debugForgetPreviewReuseRecycles() {
        if (!debugStateEnabled) {
            return;
        }
        AndroidPreviewBitmaps.debugReuseRecycles.set(0);
    }

    public DebugCacheStats readCacheStats() {
        if (!debugStateEnabled) {
            return null;
        }
        PdfFile file = pdfFile;
        PreviewStore<Bitmap> store = previewStore;
        return new DebugCacheStats(
                file != null,
                store != null,
                cacheManager == null ? NO_PART_STATS : cacheManager.debugPartStats(),
                store == null ? NO_PREVIEW_STATS : store.debugMemoryStats(),
                store == null ? NO_POOL_STATS : store.debugPoolStats(),
                store == null ? 0 : store.debugPoolCap(),
                file == null ? NO_PAGE_STATS : file.debugPageStats()
        );
    }

    public long debugResetPartCachePeak() {
        if (!debugStateEnabled || cacheManager == null) {
            return -1L;
        }
        return cacheManager.debugResetPartPeak();
    }

    public DebugPartCachePeak debugPartCachePeak() {
        if (!debugStateEnabled || cacheManager == null) {
            return null;
        }
        return new DebugPartCachePeak(cacheManager.debugPartPeakStats());
    }

    static String debugText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '%') {
                escaped.append("%25");
            } else if (character == ';') {
                escaped.append("%3B");
            } else if (character == '=') {
                escaped.append("%3D");
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private enum State {DEFAULT, LOADED, SHOWN, ERROR}

    public class Configurator {

        private final DocumentSource documentSource;

        private int[] pageNumbers = null;

        private boolean enableSwipe = true;

        private boolean horizontalSwipeDisabled = false;

        private boolean zoomDisabled = false;

        private boolean enableDoubleTap = true;

        private OnDrawListener onDrawListener;

        private OnDrawListener onDrawAllListener;

        private OnLoadCompleteListener onLoadCompleteListener;

        private OnErrorListener onErrorListener;

        private OnPageChangeListener onPageChangeListener;

        private OnPageScrollListener onPageScrollListener;

        private OnDocumentInteractionListener onDocumentInteractionListener;

        private OnRenderListener onRenderListener;

        private OnTapListener onTapListener;

        private OnTapListener onTapUpListener;

        private OnLongPressListener onLongPressListener;

        private OnTextSelectionChangeListener onTextSelectionChangeListener;

        private OnPageErrorListener onPageErrorListener;

        private LinkHandler linkHandler = new DefaultLinkHandler(PDFView.this);

        private int defaultPage = 0;

        private ViewState defaultViewState = null;

        private boolean swipeHorizontal = false;

        private boolean horizontalReadingDirectionRtl = false;

        private boolean annotationRendering = false;

        private String password = null;

        private ScrollHandle scrollHandle = null;

        private boolean antialiasing = true;

        private int spacing = 0;

        private boolean autoSpacing = false;

        private int pagesPerRow = 1;

        private boolean firstPageAlone = false;

        private boolean autoReleasingWhenDetachedFromWindow = true;

        private FitPolicy pageFitPolicy = FitPolicy.WIDTH;

        private boolean fitEachPage = false;

        private boolean threeStepDoubleTapZoom = false;

        private boolean cropMargins = false;

        private CropMargins cachedCropMargins = null;

        private boolean pageFling = false;

        private boolean pageSnap = false;

        private boolean freeScrollMode = false;

        private boolean renderDuringScale = false;

        private boolean nightMode = false;

        private boolean textSelectionEnabled = false;

        private int textSelectionColor = 0xFF3F51B5;

        private boolean debugChecks = false;
        private boolean mainThreadChecks = false;

        private Configurator(DocumentSource documentSource) {
            this.documentSource = documentSource;
        }

        public Configurator pages(int... pageNumbers) {
            this.pageNumbers = pageNumbers;
            return this;
        }

        public Configurator enableSwipe(boolean enableSwipe) {
            this.enableSwipe = enableSwipe;
            return this;
        }

        public Configurator disableHorizontalSwipe(boolean disableHorizontalSwipe) {
            this.horizontalSwipeDisabled = disableHorizontalSwipe;
            return this;
        }

        public Configurator zoomDisabled(boolean zoomDisabled) {
            this.zoomDisabled = zoomDisabled;
            return this;
        }

        public Configurator enableDoubleTap(boolean enableDoubleTap) {
            this.enableDoubleTap = enableDoubleTap;
            return this;
        }

        public Configurator enableAnnotationRendering(boolean annotationRendering) {
            this.annotationRendering = annotationRendering;
            return this;
        }

        public Configurator onDraw(OnDrawListener onDrawListener) {
            this.onDrawListener = onDrawListener;
            return this;
        }

        public Configurator onDrawAll(OnDrawListener onDrawAllListener) {
            this.onDrawAllListener = onDrawAllListener;
            return this;
        }

        public Configurator onLoad(OnLoadCompleteListener onLoadCompleteListener) {
            this.onLoadCompleteListener = onLoadCompleteListener;
            return this;
        }

        public Configurator onPageScroll(OnPageScrollListener onPageScrollListener) {
            this.onPageScrollListener = onPageScrollListener;
            return this;
        }

        public Configurator onDocumentInteraction(OnDocumentInteractionListener onDocumentInteractionListener) {
            this.onDocumentInteractionListener = onDocumentInteractionListener;
            return this;
        }

        public Configurator onError(OnErrorListener onErrorListener) {
            this.onErrorListener = onErrorListener;
            return this;
        }

        public Configurator onPageError(OnPageErrorListener onPageErrorListener) {
            this.onPageErrorListener = onPageErrorListener;
            return this;
        }

        public Configurator onPageChange(OnPageChangeListener onPageChangeListener) {
            this.onPageChangeListener = onPageChangeListener;
            return this;
        }

        public Configurator onRender(OnRenderListener onRenderListener) {
            this.onRenderListener = onRenderListener;
            return this;
        }

        public Configurator onTap(OnTapListener onTapListener) {
            this.onTapListener = onTapListener;
            return this;
        }

        public Configurator onTapUp(OnTapListener onTapUpListener) {
            this.onTapUpListener = onTapUpListener;
            return this;
        }

        public Configurator onLongPress(OnLongPressListener onLongPressListener) {
            this.onLongPressListener = onLongPressListener;
            return this;
        }

        public Configurator enableTextSelection(boolean enabled) {
            this.textSelectionEnabled = enabled;
            return this;
        }

        public Configurator textSelectionColor(int color) {
            this.textSelectionColor = color;
            return this;
        }

        public Configurator onTextSelectionChange(OnTextSelectionChangeListener onTextSelectionChangeListener) {
            this.onTextSelectionChangeListener = onTextSelectionChangeListener;
            return this;
        }

        public Configurator linkHandler(LinkHandler linkHandler) {
            this.linkHandler = linkHandler;
            return this;
        }

        public Configurator defaultPage(int defaultPage) {
            this.defaultPage = defaultPage;
            return this;
        }

        public Configurator defaultViewState(ViewState defaultViewState) {
            this.defaultViewState = defaultViewState;
            return this;
        }

        public Configurator swipeHorizontal(boolean swipeHorizontal) {
            this.swipeHorizontal = swipeHorizontal;
            return this;
        }

        public Configurator horizontalReadingDirectionRtl(boolean horizontalReadingDirectionRtl) {
            this.horizontalReadingDirectionRtl = horizontalReadingDirectionRtl;
            return this;
        }

        public Configurator password(String password) {
            this.password = password;
            return this;
        }

        public Configurator scrollHandle(ScrollHandle scrollHandle) {
            this.scrollHandle = scrollHandle;
            return this;
        }

        public Configurator enableAntialiasing(boolean antialiasing) {
            this.antialiasing = antialiasing;
            return this;
        }

        public Configurator spacing(int spacing) {
            this.spacing = spacing;
            return this;
        }

        public Configurator autoSpacing(boolean autoSpacing) {
            this.autoSpacing = autoSpacing;
            return this;
        }

        public Configurator pagesPerRow(int pagesPerRow) {
            this.pagesPerRow = pagesPerRow;
            return this;
        }

        public Configurator firstPageAlone(boolean firstPageAlone) {
            this.firstPageAlone = firstPageAlone;
            return this;
        }

        public Configurator autoReleasingWhenDetachedFromWindow(boolean autoReleasing) {
            this.autoReleasingWhenDetachedFromWindow = autoReleasing;
            return this;
        }

        public Configurator pageFitPolicy(FitPolicy pageFitPolicy) {
            this.pageFitPolicy = pageFitPolicy;
            return this;
        }

        public Configurator fitEachPage(boolean fitEachPage) {
            this.fitEachPage = fitEachPage;
            return this;
        }

        public Configurator threeStepDoubleTapZoom(boolean threeStepDoubleTapZoom) {
            this.threeStepDoubleTapZoom = threeStepDoubleTapZoom;
            return this;
        }

        public Configurator cropMargins(boolean cropMargins) {
            this.cropMargins = cropMargins;
            return this;
        }

        public Configurator cachedCropMargins(CropMargins cachedCropMargins) {
            this.cachedCropMargins = cachedCropMargins;
            return this;
        }

        public Configurator pageSnap(boolean pageSnap) {
            this.pageSnap = pageSnap;
            return this;
        }

        public Configurator renderDuringScale(boolean renderDuringScale) {
            this.renderDuringScale = renderDuringScale;
            return this;
        }

        public Configurator pageFling(boolean pageFling) {
            this.pageFling = pageFling;
            return this;
        }

        public Configurator freeScrollMode(boolean freeScrollMode) {
            this.freeScrollMode = freeScrollMode;
            return this;
        }

        public Configurator nightMode(boolean nightMode) {
            this.nightMode = nightMode;
            return this;
        }

        public Configurator debugChecks(boolean debugChecks) {
            this.debugChecks = debugChecks;
            return this;
        }

        public Configurator mainThreadChecks(boolean mainThreadChecks) {
            this.mainThreadChecks = mainThreadChecks;
            return this;
        }

        public Configurator disableLongpress() {
            PDFView.this.dragPinchManager.disableLongpress();
            return this;
        }

        public void load() {
            if (!hasSize) {
                waitingDocumentConfigurator = this;
                return;
            }
            PDFView.this.recycle();
            PdfFile.setDebugChecksEnabled(debugChecks);
            PdfFile.setMainThreadChecksEnabled(mainThreadChecks || debugChecks);
            PdfiumCore.setTimingLogsEnabled(debugChecks);
            PDFView.this.callbacks.setOnLoadComplete(onLoadCompleteListener);
            PDFView.this.callbacks.setOnError(onErrorListener);
            PDFView.this.callbacks.setOnDraw(onDrawListener);
            PDFView.this.callbacks.setOnDrawAll(onDrawAllListener);
            PDFView.this.callbacks.setOnPageChange(onPageChangeListener);
            PDFView.this.callbacks.setOnPageScroll(onPageScrollListener);
            PDFView.this.callbacks.setOnDocumentInteraction(onDocumentInteractionListener);
            PDFView.this.callbacks.setOnRender(onRenderListener);
            PDFView.this.callbacks.setOnTap(onTapListener);
            PDFView.this.callbacks.setOnTapUp(onTapUpListener);
            PDFView.this.callbacks.setOnLongPress(onLongPressListener);
            PDFView.this.callbacks.setOnPageError(onPageErrorListener);
            PDFView.this.callbacks.setOnTextSelectionChange(onTextSelectionChangeListener);
            PDFView.this.callbacks.setLinkHandler(linkHandler);
            PDFView.this.setSwipeEnabled(enableSwipe);
            PDFView.this.setHorizontalSwipeDisabled(horizontalSwipeDisabled);
            PDFView.this.setZoomDisabled(zoomDisabled);
            PDFView.this.setNightMode(nightMode);
            PDFView.this.enableDoubleTap(enableDoubleTap);
            PDFView.this.setDefaultPage(defaultPage);
            PDFView.this.setDefaultViewState(defaultViewState);
            PDFView.this.setSwipeVertical(!swipeHorizontal);
            PDFView.this.setHorizontalReadingDirectionRtl(horizontalReadingDirectionRtl);
            PDFView.this.enableAnnotationRendering(annotationRendering);
            PDFView.this.setScrollHandle(scrollHandle);
            PDFView.this.enableAntialiasing(antialiasing);
            PDFView.this.enableRenderDuringScale(renderDuringScale);
            PDFView.this.setSpacing(spacing);
            PDFView.this.setAutoSpacing(autoSpacing);
            PDFView.this.setPagesPerRow(pagesPerRow);
            PDFView.this.setFirstPageAlone(firstPageAlone);
            PDFView.this.setAutoReleasingWhenDetachedFromWindow(autoReleasingWhenDetachedFromWindow);
            PDFView.this.setPageFitPolicy(pageFitPolicy);
            PDFView.this.setFitEachPage(fitEachPage);
            PDFView.this.setThreeStepDoubleTapZoom(threeStepDoubleTapZoom);
            PDFView.this.setCropMargins(cropMargins);
            PDFView.this.setCachedCropMargins(cachedCropMargins);
            PDFView.this.setPageSnap(pageSnap);
            PDFView.this.setPageFling(pageFling);
            PDFView.this.setFreeScrollMode(freeScrollMode);
            PDFView.this.setTextSelectionColor(textSelectionColor);
            PDFView.this.setTextSelectionEnabled(textSelectionEnabled);

            if (pageNumbers != null) {
                PDFView.this.load(documentSource, password, pageNumbers);
            } else {
                PDFView.this.load(documentSource, password);
            }
        }
    }
}
