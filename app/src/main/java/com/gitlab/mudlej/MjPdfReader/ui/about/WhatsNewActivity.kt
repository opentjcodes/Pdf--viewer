// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.about

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityWhatsNewBinding
import com.gitlab.mudlej.MjPdfReader.databinding.WhatsNewRowItemBinding
import com.gitlab.mudlej.MjPdfReader.databinding.WhatsNewSectionBinding

class WhatsNewActivity : AppCompatActivity() {

    private data class Change(
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
    )

    private data class Section(
        @StringRes val titleRes: Int,
        val changes: List<Change>,
    )

    private lateinit var binding: ActivityWhatsNewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhatsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()

        val release = requestedRelease()
        binding.versionChip.text = when (release) {
            WhatsNewRelease.VERSION_3 -> "Version 3.0.0"
            WhatsNewRelease.CURRENT -> "Version ${BuildConfig.VERSION_NAME}"
        }
        binding.introText.setText(
            when (release) {
                WhatsNewRelease.VERSION_3 -> R.string.whats_new_v3_intro
                WhatsNewRelease.CURRENT -> R.string.whats_new_intro
            }
        )
        bindSections(release)
    }

    private fun bindSections(release: WhatsNewRelease) {
        val sections = when (release) {
            WhatsNewRelease.VERSION_3 -> version3Sections()
            WhatsNewRelease.CURRENT -> currentSections()
        }
        for (section in sections) {
            val sectionBinding =
                WhatsNewSectionBinding.inflate(layoutInflater, binding.sectionsContainer, true)
            sectionBinding.sectionTitle.setText(section.titleRes)
            for (change in section.changes) {
                val rowBinding =
                    WhatsNewRowItemBinding.inflate(layoutInflater, sectionBinding.sectionRows, true)
                rowBinding.rowIcon.setImageResource(change.iconRes)
                rowBinding.rowTitle.setText(change.titleRes)
                rowBinding.rowBody.setText(change.bodyRes)
            }
        }
    }

    private fun currentSections(): List<Section> = listOf(
        Section(
            R.string.whats_new_section_reading,
            listOf(
                Change(R.drawable.ic_dual_page, R.string.whats_new_single_page_title, R.string.whats_new_single_page_body),
                Change(R.drawable.ic_fullscreen_grey, R.string.whats_new_fit_policy_title, R.string.whats_new_fit_policy_body),
                Change(R.drawable.ic_settings, R.string.whats_new_hide_delay_title, R.string.whats_new_hide_delay_body),
                Change(R.drawable.ic_dark_mode, R.string.whats_new_theme_toggle_title, R.string.whats_new_theme_toggle_body),
            ),
        ),
        Section(
            R.string.whats_new_section_search,
            listOf(
                Change(R.drawable.search_icon, R.string.whats_new_inline_search_title, R.string.whats_new_inline_search_body),
                Change(R.drawable.ic_history, R.string.whats_new_search_wrap_title, R.string.whats_new_search_wrap_body),
            ),
        ),
        Section(
            R.string.whats_new_section_saving,
            listOf(
                Change(R.drawable.ic_save, R.string.whats_new_safer_saving_title, R.string.whats_new_safer_saving_body),
                Change(R.drawable.ic_copy, R.string.whats_new_shared_copies_title, R.string.whats_new_shared_copies_body),
                Change(R.drawable.ic_folder, R.string.whats_new_library_records_title, R.string.whats_new_library_records_body),
            ),
        ),
        Section(
            R.string.whats_new_section_performance,
            listOf(
                Change(R.drawable.info_icon, R.string.whats_new_fixes_title, R.string.whats_new_fixes_body),
                Change(R.drawable.ic_translate, R.string.whats_new_translations_title, R.string.whats_new_translations_body),
            ),
        ),
    )

    private fun version3Sections(): List<Section> = listOf(
        Section(
            R.string.features_topic_home,
            listOf(
                Change(R.drawable.ic_home, R.string.features_home_tabs_title, R.string.features_home_tabs_body),
                Change(R.drawable.ic_folder, R.string.features_home_folders_title, R.string.features_home_folders_body),
                Change(R.drawable.search_icon, R.string.features_home_search_title, R.string.features_home_search_body),
                Change(R.drawable.ic_refresh, R.string.features_home_scan_title, R.string.features_home_scan_body),
                Change(R.drawable.ic_stats, R.string.features_home_stats_title, R.string.features_home_stats_body),
            ),
        ),
        Section(
            R.string.features_topic_reading,
            listOf(
                Change(R.drawable.ic_dual_page, R.string.features_reading_dual_page_title, R.string.features_reading_dual_page_body),
                Change(R.drawable.ic_auto_scroll, R.string.features_reading_auto_scroll_title, R.string.features_reading_auto_scroll_body),
                Change(R.drawable.ic_text, R.string.features_reading_text_mode_title, R.string.features_reading_text_mode_body),
                Change(R.drawable.ic_crop_margins, R.string.features_reading_margins_title, R.string.features_reading_margins_body),
            ),
        ),
        Section(
            R.string.features_topic_annotation,
            listOf(
                Change(R.drawable.ic_text, R.string.features_annotation_select_title, R.string.features_annotation_select_body),
                Change(R.drawable.ic_highlight, R.string.features_annotation_highlight_title, R.string.features_annotation_highlight_body),
                Change(R.drawable.ic_signature, R.string.features_annotation_signature_title, R.string.features_annotation_signature_body),
                Change(R.drawable.ic_edit, R.string.features_annotation_forms_title, R.string.features_annotation_forms_body),
                Change(R.drawable.ic_save, R.string.features_annotation_saving_title, R.string.features_annotation_saving_body),
            ),
        ),
        Section(
            R.string.features_topic_navigation,
            listOf(
                Change(R.drawable.ic_history, R.string.features_navigation_history_title, R.string.features_navigation_history_body),
                Change(R.drawable.ic_bookmarks, R.string.features_navigation_bookmarks_title, R.string.features_navigation_bookmarks_body),
                Change(R.drawable.ic_locate_me, R.string.features_navigation_toc_title, R.string.features_navigation_toc_body),
                Change(R.drawable.search_icon, R.string.features_navigation_search_title, R.string.features_navigation_search_body),
            ),
        ),
        Section(
            R.string.features_topic_privacy,
            listOf(
                Change(R.drawable.ic_incognito, R.string.features_privacy_incognito_title, R.string.features_privacy_incognito_body),
                Change(R.drawable.privacy_icon, R.string.features_privacy_history_switch_title, R.string.features_privacy_history_switch_body),
                Change(R.drawable.ic_copy, R.string.features_privacy_backup_title, R.string.features_privacy_backup_body),
            ),
        ),
        Section(
            R.string.features_topic_custom,
            listOf(
                Change(R.drawable.ic_color_palate, R.string.features_custom_colors_title, R.string.features_custom_colors_body),
                Change(R.drawable.ic_settings, R.string.features_custom_shortcut_title, R.string.features_custom_shortcut_body),
                Change(R.drawable.ic_translate, R.string.features_custom_languages_title, R.string.features_custom_languages_body),
            ),
        ),
    )

    private fun requestedRelease(): WhatsNewRelease {
        val requestedName = intent.getStringExtra(EXTRA_RELEASE)
        return WhatsNewRelease.entries.firstOrNull { it.name == requestedName }
            ?: WhatsNewRelease.CURRENT
    }

    companion object {
        private const val EXTRA_RELEASE = "whats_new_release"

        internal fun createIntent(context: Context, release: WhatsNewRelease): Intent {
            return Intent(context, WhatsNewActivity::class.java)
                .putExtra(EXTRA_RELEASE, release.name)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
