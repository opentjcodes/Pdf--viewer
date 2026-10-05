// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.model.PagePart;

class RenderScheduler {

    private static final String TAG = RenderScheduler.class.getSimpleName();

    interface RenderExecutor {
        RenderResult execute(RenderTask task) throws PageRenderingException;
    }

    interface ResultSink {
        void deliver(PagePart part);

        void error(PageRenderingException ex);
    }

    static final class RenderResult {

        static final RenderResult NONE = new RenderResult(null, false);

        private final PagePart part;
        private final boolean resubmittable;

        private RenderResult(PagePart part, boolean resubmittable) {
            this.part = part;
            this.resubmittable = resubmittable;
        }

        static RenderResult delivered(PagePart part) {
            return new RenderResult(part, false);
        }

        static RenderResult aborted() {
            return new RenderResult(null, true);
        }

        PagePart getPart() {
            return part;
        }

        boolean isResubmittable() {
            return resubmittable;
        }
    }

    private final RenderQueue queue;
    private final RenderExecutor executor;
    private final ResultSink sink;
    private final int schedulerEpoch;
    private volatile boolean running = false;
    private Thread thread;

    RenderScheduler(PDFView pdfView, RenderExecutor executor, int epoch) {
        this(new RenderQueue(new PdfViewGenerationSource(pdfView)), executor, new PdfViewSink(pdfView, epoch), epoch);
    }

    RenderScheduler(RenderQueue queue, RenderExecutor executor, ResultSink sink) {
        this(queue, executor, sink, 0);
    }

    RenderScheduler(RenderQueue queue, RenderExecutor executor, ResultSink sink, int epoch) {
        this.queue = queue;
        this.executor = executor;
        this.sink = sink;
        this.schedulerEpoch = epoch;
    }

    void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "PDF renderer");
        thread.start();
    }

    void stop() {
        running = false;
        queue.stop();
    }

    void join() {
        Thread current = thread;
        if (current == null) {
            return;
        }
        try {
            current.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void beginWave(RenderQueue.WaveKind kind) {
        queue.beginWave(kind);
    }

    void endWave() {
        queue.endWave();
    }

    boolean debugWaveOpen() {
        return queue.debugWaveOpen();
    }

    int debugPendingTiles() {
        return queue.debugPendingTiles();
    }

    void submit(RenderTask task) {
        queue.submit(task);
    }

    void submitPrewarm(int page) {
        queue.submit(RenderTask.prewarm(page));
    }

    void cancelPage(int page) {
        queue.cancelPage(page);
    }

    void setInteractionActive(boolean active) {
        queue.setInteractionActive(active);
    }

    void setFlinging(boolean value) {
        queue.setFlinging(value);
    }

    void setIdleProducer(RenderQueue.IdleProducer producer) {
        queue.setIdleProducer(producer);
    }

    private void loop() {
        while (running) {
            RenderTask task = queue.pollNext();
            if (task == null) {
                break;
            }
            runTask(task);
        }
    }

    void runTask(RenderTask task) {
        RenderResult result;
        try {
            result = executor.execute(task);
        } catch (final PageRenderingException ex) {
            queue.completed(task);
            sink.error(ex);
            return;
        } catch (RuntimeException ex) {
            queue.completed(task);
            Log.e(TAG, "runTask: rendering task failed", ex);
            return;
        } catch (OutOfMemoryError ex) {
            queue.completed(task);
            Log.e(TAG, "runTask: out of memory while rendering", ex);
            return;
        }
        queue.completed(task);
        if (result == null) {
            return;
        }
        if (result.isResubmittable() && !task.isQueueCancelled() && running) {
            queue.submit(task.copyForResubmit());
            return;
        }
        PagePart part = result.getPart();
        if (part != null) {
            if (running) {
                sink.deliver(part);
            } else {
                recyclePart(part);
            }
        }
    }

    private static void recyclePart(PagePart part) {
        Bitmap bitmap = part.getRenderedBitmap();
        if (bitmap != null) {
            synchronized (bitmap) {
                bitmap.recycle();
            }
        }
    }

    void runningForTest(boolean value) {
        running = value;
    }

    static final class PdfExecutor implements RenderExecutor {

        private final PDFView pdfView;
        private final int schedulerEpoch;
        private final RectF renderBounds = new RectF();
        private final Rect roundedRenderBounds = new Rect();
        private final Matrix renderMatrix = new Matrix();

        PdfExecutor(PDFView pdfView, int epoch) {
            this.pdfView = pdfView;
            this.schedulerEpoch = epoch;
        }

        @Override
        public RenderResult execute(RenderTask task) throws PageRenderingException {
            PdfFile pdfFile = pdfView.pdfFile;
            if (pdfFile == null) {
                return RenderResult.NONE;
            }

            if (task.kind == RenderTask.Kind.PREWARM) {
                pdfFile.pinPage(task.page);
                try {
                    pdfView.onPrewarmStarted(task.page);
                    pdfFile.refillPageCaches(task.page);
                    pdfView.onPrewarmComplete(task.page);
                } finally {
                    pdfFile.unpinPage();
                }
                return RenderResult.NONE;
            }

            if (task.kind == RenderTask.Kind.PREVIEW) {
                return executePreview(pdfFile, task);
            }

            pdfFile.pinPage(task.page);
            try {
                pdfFile.openPage(task.page);

                int w = Math.round(task.renderWidth);
                int h = Math.round(task.renderHeight);
                if (w == 0 || h == 0 || pdfFile.pageHasError(task.page)) {
                    return RenderResult.NONE;
                }

                Bitmap render;
                try {
                    render = Bitmap.createBitmap(w, h, task.bestQuality ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
                } catch (IllegalArgumentException | OutOfMemoryError e) {
                    Log.e(TAG, "Cannot create bitmap", e);
                    return RenderResult.NONE;
                }
                try {
                    calculateBounds(w, h, task);
                    pdfFile.renderPageBitmap(render, task.page, roundedRenderBounds, task.annotationRendering);
                    pdfFile.prewarmPageCaches(task.page);
                } catch (RuntimeException | Error e) {
                    render.recycle();
                    throw e;
                }

                PagePart part = new PagePart(task.page, render,
                        new RectF(task.boundsLeft, task.boundsTop, task.boundsRight, task.boundsBottom),
                        task.cacheOrder);
                if (task.snapshot) {
                    part.markSnapshot();
                }
                part.setGeneration(task.generation);
                return RenderResult.delivered(part);
            } finally {
                pdfFile.unpinPage();
            }
        }

        private RenderResult executePreview(PdfFile pdfFile, RenderTask task) throws PageRenderingException {
            long startMs = SystemClock.uptimeMillis();
            pdfView.onPreviewStarted(task.page);
            boolean freshlyOpened = pdfFile.openPage(task.page);
            boolean closeAfter = freshlyOpened;
            RenderResult result;
            pdfFile.pinPage(task.page);
            try {
                int w = Math.round(task.renderWidth);
                int h = Math.round(task.renderHeight);
                if (w <= 0 || h <= 0 || pdfFile.pageHasError(task.page)) {
                    result = RenderResult.NONE;
                } else {
                    Bitmap render = null;
                    try {
                        render = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                    } catch (IllegalArgumentException | OutOfMemoryError e) {
                        Log.e(TAG, "Cannot create preview bitmap", e);
                    }
                    if (render == null) {
                        result = RenderResult.NONE;
                    } else {
                        boolean completed = pdfFile.renderFullPageBitmapCancellable(render, task.page,
                                task.annotationRendering, pdfView.previewExtraFlags(), task.cancel);
                        if (!completed) {
                            recyclePart(render);
                            result = RenderResult.aborted();
                        } else if (schedulerEpoch != pdfView.getCurrentRenderEpoch()) {
                            recyclePart(render);
                            result = RenderResult.aborted();
                        } else {
                            boolean repainted = pdfView.onPreviewRendered(task.page, render, task.generation);
                            if (PdfFile.isDebugChecksEnabled()) {
                                Log.d("MjPdfPerf", "preview p" + task.page + " " + (SystemClock.uptimeMillis() - startMs) + "ms "
                                        + (repainted ? "repaint" : "skipped-repaint"));
                            }
                            result = RenderResult.NONE;
                        }
                    }
                }
            } finally {
                pdfFile.unpinPage();
            }
            if (closeAfter) {
                pdfFile.closeRenderOwnedPage(task.page);
            }
            return result;
        }

        private void calculateBounds(int width, int height, RenderTask task) {
            renderMatrix.reset();
            renderMatrix.postTranslate(-task.boundsLeft * width, -task.boundsTop * height);
            renderMatrix.postScale(1 / (task.boundsRight - task.boundsLeft), 1 / (task.boundsBottom - task.boundsTop));

            renderBounds.set(0, 0, width, height);
            renderMatrix.mapRect(renderBounds);
            renderBounds.round(roundedRenderBounds);
        }

        private static void recyclePart(Bitmap bitmap) {
            if (bitmap != null) {
                synchronized (bitmap) {
                    bitmap.recycle();
                }
            }
        }
    }

    static final class PdfViewSink implements ResultSink {

        private final PDFView pdfView;
        private final int schedulerEpoch;

        PdfViewSink(PDFView pdfView, int epoch) {
            this.pdfView = pdfView;
            this.schedulerEpoch = epoch;
        }

        @Override
        public void deliver(final PagePart part) {
            pdfView.post(new Runnable() {
                @Override
                public void run() {
                    if (pdfView.isRecycled() || schedulerEpoch != pdfView.getCurrentRenderEpoch()) {
                        recyclePart(part);
                        return;
                    }
                    pdfView.onBitmapRendered(part);
                }
            });
        }

        @Override
        public void error(final PageRenderingException ex) {
            pdfView.post(new Runnable() {
                @Override
                public void run() {
                    pdfView.onPageError(ex);
                }
            });
        }
    }

    static final class PdfViewGenerationSource implements RenderQueue.GenerationSource {

        private final PDFView pdfView;

        PdfViewGenerationSource(PDFView pdfView) {
            this.pdfView = pdfView;
        }

        @Override
        public int generationOf(int page) {
            return pdfView.getPageGeneration(page);
        }
    }
}
