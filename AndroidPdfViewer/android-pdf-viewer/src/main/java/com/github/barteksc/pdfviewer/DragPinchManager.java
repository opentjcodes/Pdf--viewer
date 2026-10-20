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
import static com.github.barteksc.pdfviewer.util.Constants.Pinch.RENDER_DURING_SCALE_STEP;

import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import com.github.barteksc.pdfviewer.scroll.ScrollHandle;
import com.github.barteksc.pdfviewer.util.SnapEdge;

/**
 * This Manager takes care of moving the PDFView,
 * set its zoom track user actions.
 */
class DragPinchManager implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener, View.OnTouchListener {

    private static final float HORIZONTAL_INTENT_RATIO = 1.75f;
    private static final float HORIZONTAL_BREAKOUT_DRAIN = 0.75f;
    private static final float HORIZONTAL_BREAKOUT_SLOP_MULTIPLIER = 3f;

    private enum AxisLock { UNDECIDED, VERTICAL, FREE }
    private enum ConsumedTapState { NONE, AWAITING_CONFIRMATION, DISCARDED }

    private PDFView pdfView;
    private AnimationManager animationManager;

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    private boolean scrolling = false;
    private boolean scaling = false;
    private float scaleRenderZoom;
    private boolean enabled = false;
    private AxisLock axisLock = AxisLock.UNDECIDED;
    private float lockedHorizontalResistance = 0f;
    private ConsumedTapState consumedTapState = ConsumedTapState.NONE;
    private long consumedTapDownTime;
    private final int touchSlop;

    DragPinchManager(PDFView pdfView, AnimationManager animationManager) {
        this.pdfView = pdfView;
        this.animationManager = animationManager;
        gestureDetector = new GestureDetector(pdfView.getContext(), this);
        scaleGestureDetector = new ScaleGestureDetector(pdfView.getContext(), this);
        touchSlop = ViewConfiguration.get(pdfView.getContext()).getScaledTouchSlop();
        pdfView.setOnTouchListener(this);
    }

    void enable() {
        enabled = true;
    }

    void disable() {
        enabled = false;
        scaling = false;
        if (consumedTapState == ConsumedTapState.AWAITING_CONFIRMATION) {
            consumedTapState = ConsumedTapState.DISCARDED;
        }
        pdfView.cacheManager.setScaling(false);
    }

    void disableLongpress(){
        gestureDetector.setIsLongpressEnabled(false);
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        ConsumedTapState state = consumeTapConfirmation(e);
        if (state == ConsumedTapState.DISCARDED) {
            return false;
        }
        if (state == ConsumedTapState.NONE) {
            TextSelectionManager textSelectionManager = pdfView.getTextSelectionManager();
            if (textSelectionManager != null && textSelectionManager.handleSingleTap(e.getX(), e.getY())) {
                return true;
            }

            if (!pdfView.callbacks.callOnTap(e)) {
                pdfView.performLinkTap(e.getX(), e.getY());
            }
        }

        completeSingleTapConfirmation();
        return true;
    }

    private ConsumedTapState consumeTapConfirmation(MotionEvent event) {
        ConsumedTapState state = ConsumedTapState.NONE;
        if (!enabled) {
            state = ConsumedTapState.DISCARDED;
        } else if (consumedTapState != ConsumedTapState.NONE && consumedTapDownTime == event.getDownTime()) {
            state = consumedTapState;
        }
        clearConsumedTapUp();
        return state;
    }

    private void completeSingleTapConfirmation() {
        ScrollHandle ps = pdfView.getScrollHandle();
        if (ps != null) {
            ps.setScroll(pdfView.getPositionOffset());
        }

        pdfView.performClick();
    }

    private void clearConsumedTapUp() {
        consumedTapState = ConsumedTapState.NONE;
        consumedTapDownTime = 0L;
    }

    private void clearConsumedTapUp(long downTime) {
        if (consumedTapState != ConsumedTapState.NONE && consumedTapDownTime == downTime) {
            clearConsumedTapUp();
        }
    }


    private void startPageFling(MotionEvent downEvent, MotionEvent ev, float velocityX, float velocityY) {
        if (!checkDoPageFling(velocityX, velocityY)) {
            return;
        }

        int direction;
        if (pdfView.isSwipeVertical()) {
            direction = velocityY > 0 ? -1 : 1;
        } else {
            direction = velocityX > 0 ? -1 : 1;
            if (pdfView.isHorizontalReadingDirectionRtl()) {
                direction = -direction;
            }
        }
        // get the focused page during the down event to ensure only a single page is changed
        float delta = 0;
        if (downEvent != null) {
            delta = pdfView.isSwipeVertical() ? ev.getY() - downEvent.getY() : ev.getX() - downEvent.getX();
        }
        float offsetX = pdfView.getCurrentXOffset() - delta * pdfView.getZoom();
        float offsetY = pdfView.getCurrentYOffset() - delta * pdfView.getZoom();
        int startingPage = pdfView.findFocusPage(offsetX, offsetY);
        int targetPage = pdfView.getPageAfterRowStep(startingPage, direction);

        SnapEdge edge = pdfView.findSnapEdge(targetPage);
        float offset = pdfView.snapOffsetForPage(targetPage, edge);
        animationManager.startPageFlingAnimation(-offset);
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        clearConsumedTapUp(e.getDownTime());
        if (!pdfView.isDoubleTapEnabled() || pdfView.isZoomDisabled()) {
            return false;
        }

        float midZoom = Math.min(pdfView.getMidZoom(), pdfView.getMaxZoom());
        if (pdfView.getZoom() < PDFView.NORMAL_SCALE) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), PDFView.NORMAL_SCALE);
        }
        else if (pdfView.getZoom() < midZoom) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), midZoom);
        }
        else if (pdfView.isThreeStepDoubleTapZoom() && pdfView.getZoom() < pdfView.getMaxZoom()) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), pdfView.getMaxZoom());
        }
        else {
            pdfView.resetZoomToFitPageWithAnimation();
        }
        return true;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        animationManager.stopFling();
        axisLock = AxisLock.UNDECIDED;
        lockedHorizontalResistance = 0f;
        clearConsumedTapUp();
        return true;
    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        boolean consumed = pdfView.callbacks.callOnTapUp(e);
        if (consumed) {
            consumedTapState = ConsumedTapState.AWAITING_CONFIRMATION;
            consumedTapDownTime = e.getDownTime();
        } else {
            clearConsumedTapUp();
        }
        return consumed;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        TextSelectionManager textSelectionManager = pdfView.getTextSelectionManager();
        if (textSelectionManager != null && textSelectionManager.isDraggingHandle()) {
            return true;
        }

        StampPlacementManager stampPlacementManager = pdfView.getStampPlacementManager();
        if (stampPlacementManager != null && stampPlacementManager.isDragging()) {
            return true;
        }

        if (!scrolling) {
            animationManager.stopScrollAnimation();
        }
        scrolling = true;
        pdfView.setRenderInteractionActive(true);
        if (pdfView.isZooming() || pdfView.isSwipeEnabled()) {
            if (pdfView.isHorizontalSwipeDisabled()) distanceX = 0;
            if (pdfView.isFreeScrollMode()) distanceX = applyAxisLock(e1, e2, distanceX, distanceY);

            pdfView.moveRelativeTo(-distanceX, -distanceY);
        }
        if (!scaling) {
            pdfView.loadPageByOffset();
        }
        return true;
    }

    private float applyAxisLock(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (axisLock == AxisLock.UNDECIDED && e1 != null) {
            float totalDx = e2.getX() - e1.getX();
            float totalDy = e2.getY() - e1.getY();
            if (Math.hypot(totalDx, totalDy) >= touchSlop) {
                axisLock = Math.abs(totalDx) > HORIZONTAL_INTENT_RATIO * Math.abs(totalDy)
                        ? AxisLock.FREE
                        : AxisLock.VERTICAL;
            }
        }
        if (axisLock == AxisLock.VERTICAL) {
            lockedHorizontalResistance = Math.max(0f,
                    lockedHorizontalResistance + Math.abs(distanceX) - HORIZONTAL_BREAKOUT_DRAIN * Math.abs(distanceY));
            if (lockedHorizontalResistance >= touchSlop * HORIZONTAL_BREAKOUT_SLOP_MULTIPLIER) {
                axisLock = AxisLock.FREE;
                lockedHorizontalResistance = 0f;
            }
        }
        return axisLock == AxisLock.FREE ? distanceX : 0;
    }

    private void onScrollEnd(MotionEvent event) {
        pdfView.setRenderInteractionActive(false);
        pdfView.loadPages();
        hideHandle();
        if (!animationManager.isFlinging()) {
            pdfView.performPageSnap();
        }
    }

    @Override
    public void onLongPress(MotionEvent e) {
        if (pdfView.startTextSelectionAt(e.getX(), e.getY())) {
            return;
        }
        pdfView.callbacks.callOnLongPress(e);
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (!pdfView.isSwipeEnabled()) {
            return false;
        }
        if (pdfView.pdfFile == null) {
            return false;
        }
        if (pdfView.isHorizontalSwipeDisabled()) velocityX = 0;
        if (pdfView.isFreeScrollMode() && axisLock != AxisLock.FREE) velocityX = 0;
        //if (pdfView.isVerticalSwipeDisabled()) velocityX = 0;

        if (pdfView.isPageFlingEnabled()) {
            if (pdfView.pageFillsScreen()) {
                onBoundedFling(velocityX, velocityY);
            } else {
                startPageFling(e1, e2, velocityX, velocityY);
            }
            return true;
        }

        int xOffset = (int) pdfView.getCurrentXOffset();
        int yOffset = (int) pdfView.getCurrentYOffset();

        float minX, minY;
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfView.isSwipeVertical()) {
            minX = -(pdfView.toCurrentScale(pdfFile.getMaxPageWidth()) - pdfView.getWidth());
            minY = -(pdfFile.getDocLen(pdfView.getZoom()) - pdfView.getHeight());
        } else {
            minX = -(pdfFile.getDocLen(pdfView.getZoom()) - pdfView.getWidth());
            minY = -(pdfView.toCurrentScale(pdfFile.getMaxPageHeight()) - pdfView.getHeight());
        }

        animationManager.startFlingAnimation(xOffset, yOffset, (int) (velocityX), (int) (velocityY),
                (int) minX, 0, (int) minY, 0);
        return true;
    }

    private void onBoundedFling(float velocityX, float velocityY) {
        int xOffset = (int) pdfView.getCurrentXOffset();
        int yOffset = (int) pdfView.getCurrentYOffset();

        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null) {
            return;
        }

        int row = pdfFile.getRowOfPage(pdfView.getCurrentPage());
        float pageStart = -pdfFile.getRowOffset(row, pdfView.getZoom());
        float pageEnd = pageStart - pdfFile.getRowLength(row, pdfView.getZoom());
        float minX, minY, maxX, maxY;
        if (pdfView.isSwipeVertical()) {
            minX = -(pdfView.toCurrentScale(pdfFile.getMaxPageWidth()) - pdfView.getWidth());
            minY = pageEnd + pdfView.getHeight();
            maxX = 0;
            maxY = pageStart;
        } else {
            minX = pageEnd + pdfView.getWidth();
            minY = -(pdfView.toCurrentScale(pdfFile.getMaxPageHeight()) - pdfView.getHeight());
            maxX = pageStart;
            maxY = 0;
        }

        animationManager.startFlingAnimation(xOffset, yOffset, (int) (velocityX), (int) (velocityY),
                (int) minX, (int) maxX, (int) minY, (int) maxY);
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        float dr = detector.getScaleFactor();
        float wantedZoom = pdfView.getZoom() * dr;
        float minZoom = Math.min(MINIMUM_ZOOM, pdfView.getMinZoom());
        float maxZoom = Math.min(MAXIMUM_ZOOM, pdfView.getMaxZoom());
        if (wantedZoom < minZoom) {
            dr = minZoom / pdfView.getZoom();
        } else if (wantedZoom > maxZoom) {
            dr = maxZoom / pdfView.getZoom();
        }
        pdfView.zoomCenteredRelativeTo(dr, new PointF(detector.getFocusX(), detector.getFocusY()));
        loadSnapshotDuringScaleStep();
        return true;
    }

    private void loadSnapshotDuringScaleStep() {
        if (!pdfView.doRenderDuringScale()) {
            return;
        }
        float zoom = pdfView.getZoom();
        float step = zoom > scaleRenderZoom ? zoom / scaleRenderZoom : scaleRenderZoom / zoom;
        if (step < RENDER_DURING_SCALE_STEP) {
            return;
        }
        scaleRenderZoom = zoom;
        pdfView.loadViewportSnapshot();
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        if (pdfView.isZoomDisabled()) {
            return false;
        }
        scaling = true;
        animationManager.stopScrollAnimation();
        scaleRenderZoom = pdfView.getZoom();
        pdfView.cacheManager.setScaling(true);
        pdfView.setRenderInteractionActive(true);
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        pdfView.cacheManager.setScaling(false);
        pdfView.setRenderInteractionActive(false);
        pdfView.loadPages();
        hideHandle();
        scaling = false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (!enabled) {
            return false;
        }

        if (isInteractionEvent(event)) {
            pdfView.callbacks.callOnDocumentInteraction(event);
        }

        TextSelectionManager textSelectionManager = pdfView.getTextSelectionManager();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            animationManager.stopFling();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            axisLock = AxisLock.UNDECIDED;
            lockedHorizontalResistance = 0f;
            clearConsumedTapUp(event.getDownTime());
        }
        if (textSelectionManager != null && textSelectionManager.handleTouch(event)) {
            return true;
        }

        StampPlacementManager stampPlacementManager = pdfView.getStampPlacementManager();
        if (stampPlacementManager != null && stampPlacementManager.handleTouch(event)) {
            return true;
        }

        boolean retVal = scaleGestureDetector.onTouchEvent(event);
        retVal = gestureDetector.onTouchEvent(event) || retVal;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (scrolling) {
                scrolling = false;
                onScrollEnd(event);
            }
        }
        return retVal;
    }

    private boolean isInteractionEvent(MotionEvent event) {
        int action = event.getActionMasked();
        return action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL;
    }

    private void hideHandle() {
        ScrollHandle scrollHandle = pdfView.getScrollHandle();
//        if (scrollHandle != null && scrollHandle.shown()) { /*---*/
        if (scrollHandle != null) {
            scrollHandle.hideDelayed();
        }
    }

    private boolean checkDoPageFling(float velocityX, float velocityY) {
        float absX = Math.abs(velocityX);
        float absY = Math.abs(velocityY);
        return pdfView.isSwipeVertical() ? absY > absX : absX > absY;
    }
}
