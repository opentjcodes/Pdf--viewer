// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import com.github.barteksc.pdfviewer.preview.PreviewBitmapAdapter;
import com.github.barteksc.pdfviewer.preview.PreviewBitmapPool;
import com.github.barteksc.pdfviewer.preview.PreviewCodec;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

final class AndroidPreviewBitmaps implements PreviewBitmapAdapter<Bitmap>, PreviewCodec<Bitmap> {

    private static final int JPEG_QUALITY = 80;

    static final AtomicInteger debugReuseRecycles = new AtomicInteger();

    private final int bucketWidth;
    private volatile PreviewBitmapPool<Bitmap> pool;

    private final Paint srcPaint = new Paint();
    private Bitmap encodeScratch;

    AndroidPreviewBitmaps(int bucketWidth) {
        this.bucketWidth = bucketWidth;
        srcPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
    }

    void attachPool(PreviewBitmapPool<Bitmap> pool) {
        this.pool = pool;
    }

    @Override
    public int byteCount(Bitmap bitmap) {
        return bitmap == null ? 0 : bitmap.getByteCount();
    }

    @Override
    public void recycle(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        synchronized (bitmap) {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    @Override
    public byte[] encode(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        synchronized (bitmap) {
            if (bitmap.isRecycled()) {
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap.Config config = bitmap.getConfig();
            if (config == null) {
                config = Bitmap.Config.RGB_565;
            }
            if (encodeScratch == null || encodeScratch.isRecycled()
                    || encodeScratch.getWidth() != width
                    || encodeScratch.getHeight() != height
                    || encodeScratch.getConfig() != config) {
                if (encodeScratch != null) {
                    encodeScratch.recycle();
                    encodeScratch = null;
                }
                try {
                    encodeScratch = Bitmap.createBitmap(width, height, config);
                } catch (OutOfMemoryError e) {
                    return null;
                }
            }
            Canvas canvas = new Canvas(encodeScratch);
            canvas.drawBitmap(bitmap, 0, 0, srcPaint);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeScratch.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        return out.toByteArray();
    }

    @Override
    public Bitmap decode(byte[] data) {
        if (data == null) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        PreviewBitmapPool<Bitmap> currentPool = pool;
        Bitmap reuse = currentPool == null ? null : currentPool.acquire(bucketWidth);
        if (reuse != null) {
            options.inMutable = true;
            options.inBitmap = reuse;
            boolean decodeAttempted = false;
            synchronized (reuse) {
                if (!reuse.isRecycled()) {
                    decodeAttempted = true;
                    try {
                        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                        if (decoded != null) {
                            return decoded;
                        }
                    } catch (IllegalArgumentException e) {
                    }
                }
            }
            if (decodeAttempted && PDFView.isDebugStateEnabled()) {
                debugReuseRecycles.incrementAndGet();
            }
            recycle(reuse);
            options.inBitmap = null;
            options.inMutable = false;
        }
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (RuntimeException retry) {
            return null;
        }
    }
}
