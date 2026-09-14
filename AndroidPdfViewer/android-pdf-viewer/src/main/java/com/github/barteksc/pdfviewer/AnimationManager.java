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

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.graphics.PointF;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;


/**
 * This manager is used by the PDFView to launch animations.
 * It uses the ValueAnimator appeared in API 11 to start
 * an animation, and call moveTo() on the PDFView as a result
 * of each animation update.
 */
class AnimationManager {

    private enum AnimationKind { SCROLL, ZOOM }

    private PDFView pdfView;

    private ValueAnimator animation;

    private AnimationKind animationKind;

    private OverScroller scroller;

    private boolean flinging = false;

    private boolean pageFlinging = false;

    public AnimationManager(PDFView pdfView) {
        this.pdfView = pdfView;
        scroller = new OverScroller(pdfView.getContext());
    }

    public void startXAnimation(float xFrom, float xTo) {
        stopAll();
        animationKind = AnimationKind.SCROLL;
        animation = ValueAnimator.ofFloat(xFrom, xTo);
        XAnimation xAnimation = new XAnimation();
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(xAnimation);
        animation.addListener(xAnimation);
        animation.setDuration(400);
        animation.start();
        pdfView.publishDebugState();
    }

    public void startYAnimation(float yFrom, float yTo) {
        stopAll();
        animationKind = AnimationKind.SCROLL;
        animation = ValueAnimator.ofFloat(yFrom, yTo);
        YAnimation yAnimation = new YAnimation();
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(yAnimation);
        animation.addListener(yAnimation);
        animation.setDuration(400);
        animation.start();
        pdfView.publishDebugState();
    }

    public void startZoomAnimation(float centerX, float centerY, float zoomFrom, float zoomTo) {
        startZoomAnimation(centerX, centerY, zoomFrom, zoomTo, null);
    }

    public void startZoomAnimation(float centerX, float centerY, float zoomFrom, float zoomTo, PointF endOffsets) {
        stopAll();
        animationKind = AnimationKind.ZOOM;
        animation = ValueAnimator.ofFloat(zoomFrom, zoomTo);
        animation.setInterpolator(new DecelerateInterpolator());
        ZoomAnimation zoomAnim = new ZoomAnimation(centerX, centerY, zoomTo, endOffsets);
        animation.addUpdateListener(zoomAnim);
        animation.addListener(zoomAnim);
        animation.setDuration(400);
        animation.start();
        pdfView.publishDebugState();
    }

    public void startFlingAnimation(int startX, int startY, int velocityX, int velocityY, int minX, int maxX, int minY, int maxY) {
        stopAll();
        flinging = true;
        notifyFlingState();
        scroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY);
        pdfView.publishDebugState();
    }

    public void startPageFlingAnimation(float targetOffset) {
        if (pdfView.isSwipeVertical()) {
            startYAnimation(pdfView.getCurrentYOffset(), targetOffset);
        } else {
            startXAnimation(pdfView.getCurrentXOffset(), targetOffset);
        }
        pageFlinging = true;
        notifyFlingState();
        pdfView.publishDebugState();
    }

    void computeFling() {
        if (scroller.computeScrollOffset()) {
            pdfView.moveTo(scroller.getCurrX(), scroller.getCurrY());
            pdfView.loadPageByOffset();
        } else if (flinging) { // fling finished
            flinging = false;
            notifyFlingState();
            pdfView.loadPages();
            hideHandle();
            pdfView.performPageSnap();
            pdfView.publishDebugState();
        }
    }

    public void stopAll() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
        stopFling();
    }

    public void stopScrollAnimation() {
        if (animation != null && animationKind == AnimationKind.SCROLL) {
            animation.cancel();
            animation = null;
        }
    }

    public void stopFling() {
        flinging = false;
        notifyFlingState();
        scroller.forceFinished(true);
    }

    public boolean isFlinging() {
        return flinging || pageFlinging;
    }

    boolean isAnimating() {
        return (animation != null && animation.isRunning()) || !scroller.isFinished();
    }

    private void notifyFlingState() {
        pdfView.setRenderFlinging(isFlinging());
    }

    class XAnimation extends AnimatorListenerAdapter implements AnimatorUpdateListener {

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            float offset = (Float) animation.getAnimatedValue();
            pdfView.moveTo(offset, pdfView.getCurrentYOffset());
            pdfView.loadPageByOffset();
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            pdfView.loadPages();
            pageFlinging = false;
            notifyFlingState();
            hideHandle();
            pdfView.publishDebugState();
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            pdfView.loadPages();
            pageFlinging = false;
            notifyFlingState();
            hideHandle();
            pdfView.publishDebugState();
        }
    }

    class YAnimation extends AnimatorListenerAdapter implements AnimatorUpdateListener {

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            float offset = (Float) animation.getAnimatedValue();
            pdfView.moveTo(pdfView.getCurrentXOffset(), offset);
            pdfView.loadPageByOffset();
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            pdfView.loadPages();
            pageFlinging = false;
            notifyFlingState();
            hideHandle();
            pdfView.publishDebugState();
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            pdfView.loadPages();
            pageFlinging = false;
            notifyFlingState();
            hideHandle();
            pdfView.publishDebugState();
        }
    }

    class ZoomAnimation implements AnimatorUpdateListener, AnimatorListener {

        private final float centerX;
        private final float centerY;
        private final float targetZoom;
        private final PointF endOffsets;

        public ZoomAnimation(float centerX, float centerY, float targetZoom, PointF endOffsets) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.targetZoom = targetZoom;
            this.endOffsets = endOffsets;
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            float zoom = (Float) animation.getAnimatedValue();
            pdfView.zoomCenteredTo(zoom, new PointF(centerX, centerY));
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            pdfView.loadPages();
            hideHandle();
            pdfView.publishDebugState();
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (endOffsets != null) {
                pdfView.zoomTo(targetZoom);
                pdfView.moveTo(endOffsets.x, endOffsets.y);
                pdfView.loadPages();
            } else {
                pdfView.loadPages();
                pdfView.performPageSnap();
            }
            hideHandle();
            pdfView.publishDebugState();
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }

        @Override
        public void onAnimationStart(Animator animation) {
        }

    }

    private void hideHandle() {
        if (pdfView.getScrollHandle() != null) {
            pdfView.getScrollHandle().hideDelayed();
        }
    }

}
