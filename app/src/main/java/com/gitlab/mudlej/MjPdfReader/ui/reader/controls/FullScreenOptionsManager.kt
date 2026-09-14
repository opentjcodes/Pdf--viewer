// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.children
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.ReaderTestHooks
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.gitlab.mudlej.MjPdfReader.core.ui.divideToPercent
import com.google.android.material.button.MaterialButton
import java.util.Date
import kotlin.reflect.KFunction1


class FullScreenOptionsManager(
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val preferences: Preferences,
) {

    enum class VisibilityState { VISIBLE, INVISIBLE }

    private val delayHandler = Handler(Looper.getMainLooper())
    
    private var visibility: VisibilityState = VisibilityState.INVISIBLE
    private var labelVisibility: VisibilityState = VisibilityState.VISIBLE
    private var isHandleDragged = false
    private var buttonsVisible = false
    private var infoVisible = false

    private val viewsList: MutableList<View> = mutableListOf(
        binding.fullScreenButtonsLayout,
        binding.fullScreenInfoLayout,
        binding.exitFullScreenButton,
        binding.rotateScreenButton,

        binding.brightnessLayout,
        binding.brightnessButton,
        binding.brightnessSeekBar,
        binding.brightnessPercentage,

        binding.autoScrollLayout,
        binding.autoScrollButton,
        binding.decScrollSpeedButton,
        binding.toggleAutoScrollButton,
        binding.reverseScrollDirectionButton,
        binding.incScrollSpeedButton,

        binding.toggleHorizontalSwipeButton,
        binding.toggleZoomLockButton,
        binding.screenshotButton,
        binding.toggleLabelButton,
    )
    private val registeredButtonLabels = linkedMapOf<MaterialButton, String?>()

    init {
        setOnTouchListenerForAll()
        binding.fullScreenButtonsLayout.background?.mutate()?.alpha = PANEL_BACKGROUND_ALPHA
        binding.fullScreenButtonsLayout.clipToOutline = true
        fixedButtonLabelRes().forEach { (button, labelRes) ->
            TooltipCompat.setTooltipText(button, button.context.getString(labelRes))
        }
    }

    private fun fixedButtonLabelRes(): Map<MaterialButton, Int> = binding.run {
        linkedMapOf(
            exitFullScreenButton to R.string.exit,
            rotateScreenButton to R.string.rotate,
            brightnessButton to R.string.brightness,
            autoScrollButton to R.string.auto_scroll,
            toggleHorizontalSwipeButton to R.string.horizontal_lock,
            toggleZoomLockButton to R.string.zoom_lock,
            screenshotButton to R.string.screenshot,
            toggleLabelButton to R.string.hide_labels,
        )
    }

    fun isVisible() = visibility == VisibilityState.VISIBLE

    fun showAll() {
        if (vm.isFullScreenToggled) {
            showFullScreenButtons()
        }
        showPageHandle()
        showAutoScrollLayout()
        showBrightnessLayout()
        visibility = VisibilityState.VISIBLE
    }

    fun hideAll() {
        hideFullScreenButtons()
        hidePageHandle()
        hideAutoScrollLayout()
        hideBrightnessLayout()
        visibility = VisibilityState.INVISIBLE
    }

    fun toggleAll() {
        if (isVisible()) hideAll() else showAll()
    }

    fun showAllDelayed() {
        delayAction(::showAll)
    }

    fun hideAllDelayed() {
        delayAction(::hideAll)
    }

    fun toggleAllDelayed() {
        delayAction(::toggleAll)
    }

    fun showAllTemporarily() {
        doTemporarily(::showAll, ::hideAll)
    }

    fun hideAllTemporarily() {
        doTemporarily(::hideAll, ::showAll)
    }

    fun toggleAllTemporarily() {
        doTemporarily(::toggleAll, ::toggleAll)
    }

    fun showAllTemporarilyOrHide() {
        if (!isVisible()) {
            showAllTemporarily()
        }
        else {
            hideAll()
        }
    }

    fun permanentlyHidePageHandle() {
        binding.pdfView.scrollHandle?.permanentHide()
    }

    fun refreshInfo() {
        val shouldShowInfo = updateInfoContent()
            && (isHandleDragged || buttonsVisible)

        setInfoVisibility(shouldShowInfo)
    }

    private fun setInfoVisibility(show: Boolean) {
        if (infoVisible == show) {
            return
        }
        infoVisible = show
        val info = binding.fullScreenInfoLayout
        info.animate().cancel()
        if (show) {
            if (info.visibility != View.VISIBLE) {
                info.alpha = 0f
            }
            info.visibility = View.VISIBLE
            info.animate()
                .alpha(1f)
                .setDuration(SHOW_ANIMATION_MILLIS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else if (info.visibility == View.VISIBLE) {
            info.animate()
                .alpha(0f)
                .setDuration(HIDE_ANIMATION_MILLIS)
                .setInterpolator(LinearInterpolator())
                .withEndAction {
                    info.visibility = View.GONE
                    info.alpha = 1f
                }
                .start()
        }
    }

    fun onHandleDragStarted() {
        isHandleDragged = true
        refreshInfo()
    }

    fun onHandleDragEnded() {
        isHandleDragged = false
        refreshInfo()
    }

    fun registerFullScreenButton(button: MaterialButton, label: String?) {
        if (!viewsList.contains(button)) {
            viewsList.add(button)
        }
        registeredButtonLabels[button] = label
        TooltipCompat.setTooltipText(button, label)
        button.setOnTouchListener(getOnTouchListener())
        if (labelVisibility == VisibilityState.INVISIBLE) {
            button.text = ""
            makeButtonCircular(button)
        }
    }

    private fun updateInfoContent(): Boolean {
        val context = binding.root.context
        val settings = FullScreenInfoSettings.from(preferences)
        val titleVisible = settings.showPdfName && vm.doc.name.isNotBlank()
        val pageInfo = getPageInfo(context, settings)

        binding.fullScreenInfoTime.text = DateFormat.getTimeFormat(context).format(Date())
        binding.fullScreenInfoTime.visibility = if (settings.showTime) View.VISIBLE else View.GONE
        binding.fullScreenInfoTitle.text = vm.doc.getTitle()
        binding.fullScreenInfoTitle.visibility = if (titleVisible) View.VISIBLE else View.GONE
        binding.fullScreenInfoPage.text = pageInfo.orEmpty()
        binding.fullScreenInfoPage.visibility = if (pageInfo != null) View.VISIBLE else View.GONE

        return settings.showTime || titleVisible || pageInfo != null
    }

    private fun getPageInfo(context: Context, settings: FullScreenInfoSettings): String? {
        val pageNumber = minOf(vm.doc.pageRangeStart, vm.doc.pageNumber) + 1
        val rangeEnd = (vm.doc.pageRangeEnd + 1).coerceAtLeast(pageNumber)
        val pageCount = vm.doc.length.coerceAtLeast(rangeEnd)
        val percentage = rangeEnd.divideToPercent(pageCount).coerceIn(1, 100)

        return when {
            settings.showPageNumber && settings.showReadingPercentage && rangeEnd > pageNumber -> context.getString(
                R.string.fullscreen_page_range_info,
                pageNumber,
                rangeEnd,
                pageCount,
                percentage,
            )
            settings.showPageNumber && settings.showReadingPercentage -> context.getString(
                R.string.fullscreen_page_info,
                pageNumber,
                pageCount,
                percentage,
            )
            settings.showPageNumber && rangeEnd > pageNumber -> context.getString(
                R.string.fullscreen_page_range_number_info,
                pageNumber,
                rangeEnd,
                pageCount,
            )
            settings.showPageNumber -> context.getString(
                R.string.fullscreen_page_number_info,
                pageNumber,
                pageCount,
            )
            settings.showReadingPercentage -> context.getString(
                R.string.fullscreen_percentage_info,
                percentage,
            )
            else -> null
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    fun getOnTouchListener(): View.OnTouchListener {
        val isEventFullyConsumed = false    // false so clickOnListener will be triggered
        return View.OnTouchListener { _, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> delayHandler.reset()
                MotionEvent.ACTION_UP -> hideAllDelayed()
            }
            isEventFullyConsumed
        }
    }

    fun isLabelsVisible(): Boolean {
        return labelVisibility == VisibilityState.VISIBLE
    }

    fun toggleLabelVisibility(drawableOf: KFunction1<Int, Drawable?>, getLabel: KFunction1<Int, String?>) {
        if (binding.fullScreenButtonsLayout.visibility == View.VISIBLE) {
            val transition = ChangeBounds().apply {
                duration = LABEL_ANIMATION_MILLIS
                interpolator = DecelerateInterpolator()
            }
            TransitionManager.beginDelayedTransition(binding.viewActionsLayout, transition)
        }
        binding.apply {
            val buttons = LinkedHashMap<MaterialButton, String?>()
            fixedButtonLabelRes().forEach { (button, labelRes) -> buttons[button] = getLabel(labelRes) }
            buttons.putAll(registeredButtonLabels)
            if (labelVisibility == VisibilityState.VISIBLE) {
                buttons.keys.forEach { button ->
                    button.text = ""
                    makeButtonCircular(button)
                }
                toggleLabelButton.icon = drawableOf(R.drawable.ic_double_arrow_right)
            }
            else {
                buttons.forEach { (button, text) ->
                    button.text = text.orEmpty()
                    resetButtonShape(button)
                }
                toggleLabelButton.icon = drawableOf(R.drawable.ic_double_arrow_left)
            }
        }
        labelVisibility = inverseVisibility(labelVisibility)
    }

    private fun makeButtonCircular(button: MaterialButton) {
        val padding = button.resources.getDimensionPixelSize(R.dimen.fs_button_padding)
        val iconSize = button.resources.getDimensionPixelSize(R.dimen.fs_button_size)
        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        button.iconPadding = 0
        button.setPaddingRelative(padding, button.paddingTop, padding, button.paddingBottom)
        button.layoutParams.width = iconSize + 2 * padding
        button.requestLayout()
    }

    private fun resetButtonShape(button: MaterialButton) {
        val padding = button.resources.getDimensionPixelSize(R.dimen.fs_button_padding)
        val paddingEnd = button.resources.getDimensionPixelSize(R.dimen.fs_button_padding_end)
        button.iconGravity = MaterialButton.ICON_GRAVITY_START
        button.iconPadding = button.resources.getDimensionPixelSize(R.dimen.fs_button_icon_padding)
        button.setPaddingRelative(padding, button.paddingTop, paddingEnd, button.paddingBottom)
        button.layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        button.requestLayout()
    }

    // -------------
    private fun delayAction(action: Runnable) {
        delayHandler.reset()
        delayHandler.postDelayed(action, preferences.getHideDelay().toLong())
    }

    private fun doTemporarily(action: Runnable, undoAction: Runnable) {
        delayHandler.reset()
        action.run()
        delayHandler.postDelayed(undoAction, preferences.getHideDelay().toLong())
    }

    private fun showFullScreenButtons() = changeFullScreenButtonsVisibility(true)

    private fun hideFullScreenButtons() = changeFullScreenButtonsVisibility(false)

    private fun changeFullScreenButtonsVisibility(isVisible: Boolean) {
        buttonsVisible = isVisible && hasVisibleButtons()
        val panel = binding.fullScreenButtonsLayout
        panel.animate().cancel()
        if (buttonsVisible) {
            if (panel.visibility != View.VISIBLE) {
                panel.alpha = 0f
                panel.translationX = slideOffset()
            }
            panel.visibility = View.VISIBLE
            val animator = panel.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SHOW_ANIMATION_MILLIS)
                .setInterpolator(DecelerateInterpolator())
            if (BuildConfig.DEBUG) {
                animator.setUpdateListener(null)
            }
            animator.start()
        } else if (panel.visibility == View.VISIBLE) {
            val animator = panel.animate()
                .alpha(0f)
                .translationX(slideOffset())
                .setDuration(HIDE_ANIMATION_MILLIS)
                .setInterpolator(LinearInterpolator())
                .withEndAction {
                    panel.visibility = View.GONE
                    panel.alpha = 1f
                    panel.translationX = 0f
                }
            if (BuildConfig.DEBUG) {
                ReaderTestHooks.fullScreenPanelHideStarted()
                animator.setUpdateListener {
                    ReaderTestHooks.fullScreenPanelHideTranslation(panel.translationX)
                }
            }
            animator.start()
        }
        refreshInfo()
    }

    private fun slideOffset(): Float {
        val offset = SLIDE_OFFSET_DP * binding.root.resources.displayMetrics.density
        return if (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) offset else -offset
    }

    private fun hasVisibleButtons(): Boolean {
        return binding.fullScreenButtonsList.children.any { it.visibility == View.VISIBLE }
    }

    private fun showPageHandle() {
        binding.pdfView.scrollHandle?.customShow()
    }

    private fun hidePageHandle() {
        binding.pdfView.scrollHandle?.customHide()
    }

    private fun showAutoScrollLayout() {
        if (vm.isFullScreenToggled && vm.isAutoScrollClicked) {
            binding.autoScrollLayout.visibility = View.VISIBLE
            binding.autoScrollSpeedText.visibility = View.VISIBLE
        }
    }

    private fun hideAutoScrollLayout() {
        if (vm.isFullScreenToggled && vm.isAutoScrollClicked) {
            binding.autoScrollLayout.visibility = View.GONE
            binding.autoScrollSpeedText.visibility = View.GONE
        }
    }

    private fun showBrightnessLayout() {
        if (vm.isFullScreenToggled && vm.isBrightnessClicked) {
            binding.brightnessLayout.visibility = View.VISIBLE
        }
    }

    private fun hideBrightnessLayout() {
        if (vm.isFullScreenToggled && vm.isBrightnessClicked) {
            binding.brightnessLayout.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setOnTouchListenerForAll() {
        viewsList.forEach { it.setOnTouchListener(getOnTouchListener()) }
    }

    private fun Handler.reset() {
        this.removeCallbacksAndMessages(null)
    }

    private fun inverseVisibility(visibility: VisibilityState): VisibilityState {
        return if (visibility == VisibilityState.VISIBLE) VisibilityState.INVISIBLE
        else VisibilityState.VISIBLE
    }

    companion object {
        private const val PANEL_BACKGROUND_ALPHA = 240
        private const val SHOW_ANIMATION_MILLIS = 180L
        private const val HIDE_ANIMATION_MILLIS = 150L
        private const val LABEL_ANIMATION_MILLIS = 150L
        private const val SLIDE_OFFSET_DP = 16f
    }

    private data class FullScreenInfoSettings(
        val showTime: Boolean,
        val showPdfName: Boolean,
        val showPageNumber: Boolean,
        val showReadingPercentage: Boolean,
    ) {
        companion object {
            fun from(preferences: Preferences): FullScreenInfoSettings {
                return FullScreenInfoSettings(
                    showTime = preferences.getFullScreenInfoShowTime(),
                    showPdfName = preferences.getFullScreenInfoShowPdfName(),
                    showPageNumber = preferences.getFullScreenInfoShowPageNumber(),
                    showReadingPercentage = preferences.getFullScreenInfoShowReadingPercentage(),
                )
            }
        }
    }

}
