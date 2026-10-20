package com.github.barteksc.pdfviewer.scroll;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.R;
import com.github.barteksc.pdfviewer.util.Util;

public class DefaultScrollHandle extends RelativeLayout implements ScrollHandle {

    private final static int HANDLE_LONG = 78;
    private final static int HANDLE_SHORT = 40;
    private final static int DEFAULT_TEXT_SIZE = 16;
    public final static String DEBUG_HANDLE_DESCRIPTION = "scroll-handle";
    public final static String DEBUG_HANDLE_LABEL_DESCRIPTION = "scroll-handle-label";

    private float relativeHandlerMiddle = 0f;

    protected TextView textView;
    protected Context context;
    private boolean inverted;
    private final boolean showPageCount;
    private PDFView pdfView;
    private float currentPos;

    private final TapGestureDetector tapGestureDetector;

    private final Handler handler = new Handler();
    private final Runnable hidePageScrollerRunnable = new Runnable() {
        @Override
        public void run() {
            customHide();
        }
    };
    boolean permanentHidden = false;
    private int topReachLimit = 0;
    private View.OnTouchListener customOnTouchListener;
    private boolean dragging;

    public DefaultScrollHandle(Context context) {
        this(context, false, true);
    }

    public DefaultScrollHandle(Context context, boolean inverted) {
        this(context, inverted, true);
    }

    public DefaultScrollHandle(Context context, boolean inverted, boolean showPageCount) {
        super(context);
        this.context = context;
        this.inverted = inverted;
        this.showPageCount = showPageCount;
        tapGestureDetector = new TapGestureDetector(context);
        textView = new TextView(context);
        setVisibility(INVISIBLE);
        setCustomColorForText(context);
//        setTextColor(Color.BLACK);
        //setTextColor(Color.parseColor("#CDCDCD"));
        setTextSize(DEFAULT_TEXT_SIZE);
    }

    private void setCustomColorForText(Context context) {
        TypedValue typedValue = new TypedValue();
        boolean found = context.getTheme().resolveAttribute(R.attr.colorOnSurface, typedValue, true);
        if (found) {
            int colorOnSurface = typedValue.data;
            setTextColor(colorOnSurface);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void setupLayout(PDFView pdfView) {
        int align, width, height;
        Drawable background;
        // determine handler position, default is right (when scrolling vertically) or bottom (when scrolling horizontally)
        if (pdfView.isSwipeVertical()) {
            width = HANDLE_LONG;
            height = HANDLE_SHORT;
            if (inverted) { // left
                align = ALIGN_PARENT_LEFT;
                background = ContextCompat.getDrawable(context, R.drawable.default_scroll_handle_left);
            } else { // right
                align = ALIGN_PARENT_RIGHT;
                background = ContextCompat.getDrawable(context, R.drawable.default_scroll_handle_right);
            }
        } else {
            width = HANDLE_SHORT;
            height = HANDLE_LONG;
            if (inverted) { // top
                align = ALIGN_PARENT_TOP;
                background = ContextCompat.getDrawable(context, R.drawable.default_scroll_handle_top);
            } else { // bottom
                align = ALIGN_PARENT_BOTTOM;
                background = ContextCompat.getDrawable(context, R.drawable.default_scroll_handle_bottom);
            }
        }

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN) {
            setBackgroundDrawable(background);
        } else  {
            setBackground(background);
        }

        LayoutParams lp = new LayoutParams(Util.getDP(context, width), Util.getDP(context, height));
        lp.setMargins(0, 0, 0, 0);

        LayoutParams tvlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvlp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);

        addView(textView, tvlp);

        lp.addRule(align);
        pdfView.addView(this, lp);

        this.pdfView = pdfView;

        if (PDFView.isDebugStateEnabled()) {
            setContentDescription(DEBUG_HANDLE_DESCRIPTION);
            textView.setContentDescription(DEBUG_HANDLE_LABEL_DESCRIPTION);
        }
    }

    @Override
    public void setTopReachLimit(int limitPx) {
        topReachLimit = Math.max(0, limitPx);
        if (pdfView != null && pdfView.isSwipeVertical() && getY() < topReachLimit) {
            setY(topReachLimit);
            calculateMiddle();
            invalidate();
        }
    }

    @Override
    public void setOnTouchListener(View.OnTouchListener onTouchListener) {
        customOnTouchListener = onTouchListener;
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener onClickListener) {
        super.setOnClickListener(onClickListener);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void destroyLayout() {
        pdfView.removeView(this);
    }

    @Override
    public void setScroll(float position) {
        if (!shown()) {
            show();
        } else {
            handler.removeCallbacks(hidePageScrollerRunnable);
        }
        if (pdfView == null || dragging) {
            return;
        }
        float pdfViewSize = pdfView.isSwipeVertical() ? pdfView.getHeight() : pdfView.getWidth();
        float minPos = pdfView.isSwipeVertical() ? topReachLimit : 0;
        float maxPos = pdfViewSize - Util.getDP(context, HANDLE_SHORT);
        if (maxPos <= minPos) {
            return;
        }
        setPositionAbsolute(minPos + position * (maxPos - minPos));
    }

    private void setPosition(float pos) {
        setPositionAbsolute(pos - relativeHandlerMiddle);
    }

    private void setPositionAbsolute(float pos) {
        if (Float.isInfinite(pos) || Float.isNaN(pos)) {
            return;
        }
        float pdfViewSize;
        if (pdfView.isSwipeVertical()) {
            pdfViewSize = pdfView.getHeight();
        } else {
            pdfViewSize = pdfView.getWidth();
        }
        float minPos = pdfView.isSwipeVertical() ? topReachLimit : 0;
        if (pos < minPos) {
            pos = minPos;
        } else if (pos > pdfViewSize - Util.getDP(context, HANDLE_SHORT)) {
            pos = pdfViewSize - Util.getDP(context, HANDLE_SHORT);
        }

        if (pdfView.isSwipeVertical()) {
            setY(pos);
        } else {
            setX(pos);
        }

        calculateMiddle();
        invalidate();
    }

    private void calculateMiddle() {
        float pos, viewSize, pdfViewSize;
        if (pdfView.isSwipeVertical()) {
            pos = getY();
            viewSize = getHeight();
            pdfViewSize = pdfView.getHeight();
        } else {
            pos = getX();
            viewSize = getWidth();
            pdfViewSize = pdfView.getWidth();
        }
        relativeHandlerMiddle = ((pos + relativeHandlerMiddle) / pdfViewSize) * viewSize;
    }

    @Override
    public void hideDelayed() {
        handler.postDelayed(hidePageScrollerRunnable, 3000);
    }

    @Override
    public void setPageNum(int pageNum) {
        String text = getPageNumText(pageNum);
        if (!textView.getText().equals(text)) {
            textView.setText(text);
        }
    }

    private String getPageNumText(int pageNum) {
        String pageText = String.valueOf(pageNum);
        if (pdfView != null) {
            int rowFirstPage = pdfView.getRowFirstPage(pageNum - 1) + 1;
            int rowLastPage = pdfView.getRowLastPage(pageNum - 1) + 1;
            if (rowLastPage > rowFirstPage) {
                pageText = rowFirstPage + "-" + rowLastPage;
            }
        }
        if (showPageCount && pdfView != null && pdfView.getPageCount() > 0) {
            return pageText + "/" + pdfView.getPageCount();
        }
        return pageText;
    }

    @Override
    public boolean shown() {
        return getVisibility() == VISIBLE;
    }

    // I disables show and hide for it, only thru my own custom methods, which are called only
    // when the view is tapped
    @Override
    public void show() {
//        setVisibility(VISIBLE);
    }

    @Override
    public void hide() {
//        setVisibility(INVISIBLE);
    }

    @Override public void permanentHide() { permanentHidden = true; setVisibility(INVISIBLE); }
    @Override public void disablePermanentHide() { permanentHidden = false; }
    @Override public boolean customShown() { return getVisibility() == VISIBLE; }
    @Override public void customShow() { if (!permanentHidden) setVisibility(VISIBLE); }
    @Override public void customHide() { if (!permanentHidden) setVisibility(INVISIBLE); }

    public void setTextColor(int color) {
        textView.setTextColor(color);
    }

    /**
     * @param size text size in dp
     */
    public void setTextSize(int size) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
    }

    private boolean isPDFViewReady() {
        return pdfView != null && pdfView.getPageCount() > 0 && !pdfView.documentFitsView();
    }

    @SuppressLint("SetTextI18n")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (tapGestureDetector.isTap(event)) {
            performClick();
        }

        if (customOnTouchListener != null) {
            customOnTouchListener.onTouch(this, event);
        }

        if (!isPDFViewReady()) {
            return super.onTouchEvent(event);
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                pdfView.stopFling();
                pdfView.setRenderInteractionActive(true);
                handler.removeCallbacks(hidePageScrollerRunnable);
                if (pdfView.isSwipeVertical()) {
                    currentPos = event.getRawY() - getY();
                }
                else {
                    currentPos = event.getRawX() - getX();
                }
            case MotionEvent.ACTION_MOVE:
                if (pdfView.isSwipeVertical()) {
                    setPosition(event.getRawY() - currentPos + relativeHandlerMiddle);
                } else {
                    setPosition(event.getRawX() - currentPos + relativeHandlerMiddle);
                }
                pdfView.setPositionOffset(computePositionOffset(), false);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                dragging = false;
                pdfView.setRenderInteractionActive(false);
                pdfView.performPageSnap();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private float computePositionOffset() {
        float pdfViewSize;
        float pos;
        if (pdfView.isSwipeVertical()) {
            pdfViewSize = pdfView.getHeight();
            pos = getY();
        } else {
            pdfViewSize = pdfView.getWidth();
            pos = getX();
        }
        float minPos = pdfView.isSwipeVertical() ? topReachLimit : 0;
        float maxPos = pdfViewSize - Util.getDP(context, HANDLE_SHORT);
        if (maxPos <= minPos) {
            return 0;
        }
        float offset = (pos - minPos) / (maxPos - minPos);
        return Math.max(0, Math.min(1, offset));
    }

    @Override
    public void cancelHideRunner() {
        handler.removeCallbacks(hidePageScrollerRunnable);
    }

    @Override
    public void activateHandlerHideDelayed() {
        hideDelayed();
    }
}
