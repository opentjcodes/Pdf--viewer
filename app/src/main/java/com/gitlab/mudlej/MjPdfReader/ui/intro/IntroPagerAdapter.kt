// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.intro

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.databinding.IntroFeatureRowItemBinding
import com.gitlab.mudlej.MjPdfReader.databinding.IntroPageItemBinding
import com.google.android.material.color.MaterialColors

class IntroPage(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    val features: List<IntroFeature>,
    val isLogo: Boolean = false,
    @StringRes val footerHtmlRes: Int = 0,
)

class IntroFeature(
    @DrawableRes val iconRes: Int,
    @StringRes val textRes: Int,
)

class IntroPagerAdapter(
    private val pages: List<IntroPage>,
) : RecyclerView.Adapter<IntroPagerAdapter.PageViewHolder>() {

    class PageViewHolder(val binding: IntroPageItemBinding) : RecyclerView.ViewHolder(binding.root) {

        @MainThread
        fun debugSnapshot(position: Int): IntroPageSnapshot? {
            if (!BuildConfig.DEBUG) {
                return null
            }
            val footer = binding.footerText
            val footerContent = footer.text
            val links = (footerContent as? Spanned)
                ?.getSpans(0, footerContent.length, URLSpan::class.java)
                ?.map { span -> span.url }
                .orEmpty()
            return IntroPageSnapshot(
                position = position,
                title = binding.pageTitle.text,
                featureTexts = featureTexts(),
                footerVisible = footer.visibility == View.VISIBLE,
                footerText = footerContent,
                footerLinkUrls = links,
            )
        }

        private fun featureTexts(): List<CharSequence> {
            val rows = binding.featuresLayout
            return (0 until rows.childCount).mapNotNull { index ->
                rows.getChildAt(index).findViewById<TextView>(R.id.featureText)?.text
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return PageViewHolder(IntroPageItemBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount(): Int = pages.size

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        bindIcon(holder.binding, page)
        holder.binding.pageTitle.setText(page.titleRes)
        bindFeatures(holder.binding, page)
        bindFooter(holder.binding, page)
    }

    private fun bindIcon(binding: IntroPageItemBinding, page: IntroPage) {
        val icon = binding.pageIcon
        val card = binding.iconCard
        icon.setImageResource(page.iconRes)
        val iconSize: Int
        if (page.isLogo) {
            card.setCardBackgroundColor(Color.TRANSPARENT)
            icon.imageTintList = null
            iconSize = card.layoutParams.width
        } else {
            card.setCardBackgroundColor(
                MaterialColors.getColor(card, com.google.android.material.R.attr.colorSecondaryContainer)
            )
            icon.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(icon, com.google.android.material.R.attr.colorOnSecondaryContainer)
            )
            iconSize = card.layoutParams.width / 2
        }
        icon.layoutParams = icon.layoutParams.apply {
            width = iconSize
            height = iconSize
        }
    }

    private fun bindFeatures(binding: IntroPageItemBinding, page: IntroPage) {
        val featuresLayout = binding.featuresLayout
        featuresLayout.removeAllViews()
        val inflater = LayoutInflater.from(featuresLayout.context)
        for (feature in page.features) {
            val row = IntroFeatureRowItemBinding.inflate(inflater, featuresLayout, true)
            row.featureText.setText(feature.textRes)
            row.featureIcon.setImageResource(feature.iconRes)
        }
    }

    private fun bindFooter(binding: IntroPageItemBinding, page: IntroPage) {
        val footer = binding.footerText
        if (page.footerHtmlRes == 0) {
            footer.visibility = View.GONE
            return
        }
        footer.visibility = View.VISIBLE
        val html = footer.context.getString(page.footerHtmlRes)
        footer.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        footer.movementMethod = LinkMovementMethod.getInstance()
    }
}
