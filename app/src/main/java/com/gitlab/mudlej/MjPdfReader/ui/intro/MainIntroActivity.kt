// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.intro

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityIntroBinding

class MainIntroActivity : AppCompatActivity() {

    private val pages = listOf(
        IntroPage(
            R.drawable.ic_logo, R.string.intro_welcome_title, listOf(
                IntroFeature(R.drawable.ic_elevated_pdf, R.string.intro_welcome_tagline),
                IntroFeature(R.drawable.ic_lock, R.string.intro_welcome_privacy),
                IntroFeature(R.drawable.ic_hide, R.string.intro_welcome_clean),
                IntroFeature(R.drawable.code_icon, R.string.intro_welcome_foss),
            ),
            isLogo = true,
            footerHtmlRes = R.string.intro_made_by,
        ),
        IntroPage(
            R.drawable.ic_home, R.string.intro_library_title, listOf(
                IntroFeature(R.drawable.ic_history, R.string.intro_library_recent),
                IntroFeature(R.drawable.ic_grid_view, R.string.intro_library_covers),
                IntroFeature(R.drawable.ic_folder, R.string.intro_library_folders),
                IntroFeature(R.drawable.search_icon, R.string.intro_library_search),
            )
        ),
        IntroPage(
            R.drawable.ic_night_light, R.string.intro_reading_title, listOf(
                IntroFeature(R.drawable.ic_dark_mode, R.string.intro_reading_dark),
                IntroFeature(R.drawable.ic_crop_margins, R.string.intro_reading_crop),
                IntroFeature(R.drawable.ic_auto_scroll, R.string.intro_reading_scroll),
                IntroFeature(R.drawable.ic_text, R.string.intro_reading_text_mode),
            )
        ),
        IntroPage(
            R.drawable.ic_edit, R.string.intro_annotate_title, listOf(
                IntroFeature(R.drawable.ic_highlight, R.string.intro_annotate_highlight),
                IntroFeature(R.drawable.ic_signature, R.string.intro_annotate_sign),
                IntroFeature(R.drawable.ic_bookmarks, R.string.intro_annotate_navigate),
            )
        ),
        IntroPage(
            R.drawable.ic_display_settings, R.string.intro_custom_title, listOf(
                IntroFeature(R.drawable.ic_shortcut, R.string.intro_custom_actions),
                IntroFeature(R.drawable.ic_reverse_direction, R.string.intro_custom_direction),
                IntroFeature(R.drawable.ic_brightness, R.string.intro_custom_controls),
                IntroFeature(R.drawable.ic_color_palate, R.string.intro_custom_colors),
            )
        ),
    )

    private lateinit var binding: ActivityIntroBinding
    private val dots = mutableListOf<ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        supportActionBar?.hide()

        binding.introPager.adapter = IntroPagerAdapter(pages)
        createDots()
        binding.introPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateControls(position)
            }
        })

        binding.skipButton.setOnClickListener { finish() }
        binding.nextButton.setOnClickListener {
            if (binding.introPager.currentItem == pages.lastIndex) finish()
            else binding.introPager.currentItem += 1
        }
    }

    private fun createDots() {
        val margin = resources.getDimensionPixelSize(R.dimen.intro_dot_margin)
        repeat(pages.size) {
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.marginStart = margin
            params.marginEnd = margin
            binding.dotsLayout.addView(dot, params)
            dots.add(dot)
        }
        updateControls(0)
    }

    private fun updateControls(position: Int) {
        dots.forEachIndexed { index, dot ->
            dot.setImageResource(
                if (index == position) R.drawable.intro_dot_active
                else R.drawable.intro_dot_inactive
            )
        }
        val isLastPage = position == pages.lastIndex
        binding.skipButton.visibility = if (isLastPage) View.INVISIBLE else View.VISIBLE
        binding.nextButton.setText(if (isLastPage) R.string.intro_get_started else R.string.intro_next)
        updateDebugDescriptions(position)
    }

    private fun updateDebugDescriptions(position: Int) {
        if (!BuildConfig.DEBUG) {
            return
        }
        binding.introPager.contentDescription = "intro-page:$position:${pages.size}"
        dots.forEachIndexed { index, dot ->
            dot.contentDescription = if (index == position) "intro-dot-active" else "intro-dot-inactive"
        }
    }
}
