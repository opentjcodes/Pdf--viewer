// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.DebugRootDescription
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoNightModeFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.applyIncognitoOverlayFromIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.databinding.ActivitySettingsBinding
import com.gitlab.mudlej.MjPdfReader.core.ui.tintIconsForChrome


class SettingsActivity : AppCompatActivity(), SettingsFragment.Navigation {

    private lateinit var binding: ActivitySettingsBinding
    private var currentPage: SettingsPage? = null
    private var searchQuery = ""
    private var searchItem: MenuItem? = null
    private var searchView: SearchView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyIncognitoNightModeFromIntent()
        super.onCreate(savedInstanceState)
        applyIncognitoOverlayFromIntent()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DebugRootDescription.attachLayoutDirection(binding.root)
        setupScreenChrome()

        currentPage = savedInstanceState?.getString(CURRENT_PAGE_KEY)?.let(SettingsPage::valueOf)
            ?: intent.getStringExtra(EXTRA_PAGE)?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        searchQuery = savedInstanceState?.getString(SEARCH_QUERY_KEY).orEmpty()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentPage != null -> showRoot(reverse = true)
                    searchQuery.isNotBlank() -> clearSearch()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null) {
            currentPage?.let { showPage(it, animate = false) } ?: showRoot(animate = false)
        } else {
            title = currentPage?.let { getString(it.titleRes) } ?: getString(R.string.settings)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(CURRENT_PAGE_KEY, currentPage?.name)
        outState.putString(SEARCH_QUERY_KEY, searchQuery)
        super.onSaveInstanceState(outState)
    }

    override fun onSettingsPageSelected(page: SettingsPage) {
        showPage(page)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        menu.tintIconsForChrome(this)
        searchItem = menu.findItem(R.id.searchSettingsOption)
        configureSearch(searchItem)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.searchSettingsOption)
        item.isVisible = currentPage == null
        val queryToRestore = searchQuery
        if (currentPage == null && queryToRestore.isNotBlank()) {
            item.expandActionView()
            (item.actionView as? SearchView)?.setQuery(queryToRestore, false)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> if (currentPage == null) finish() else showRoot(reverse = true)
            else -> super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun configureSearch(item: MenuItem?) {
        val view = item?.actionView as? SearchView ?: return
        searchView = view
        view.queryHint = getString(R.string.settings_search_hint)
        view.maxWidth = Int.MAX_VALUE
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                searchQuery = newText
                currentRootFragment()?.setSearchQuery(newText)
                return true
            }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (searchQuery.isNotBlank()) {
                    searchQuery = ""
                    currentRootFragment()?.setSearchQuery("")
                }
                return true
            }
        })
        val queryToRestore = searchQuery
        if (queryToRestore.isNotBlank()) {
            item.expandActionView()
            view.setQuery(queryToRestore, false)
        }
    }

    private fun showRoot(animate: Boolean = true, reverse: Boolean = false) {
        currentPage = null
        title = getString(R.string.settings)
        invalidateOptionsMenu()
        val transaction = supportFragmentManager.beginTransaction()
        if (animate && reverse) {
            transaction.setCustomAnimations(R.anim.settings_enter_from_left, R.anim.settings_exit_to_right)
        }
        transaction
            .replace(R.id.fragment_container_view, SettingsFragment.root(searchQuery), ROOT_FRAGMENT_TAG)
            .commit()
    }

    private fun showPage(page: SettingsPage, animate: Boolean = true) {
        currentPage = page
        title = getString(page.titleRes)
        searchItem?.collapseActionView()
        searchView?.clearFocus()
        invalidateOptionsMenu()
        val transaction = supportFragmentManager.beginTransaction()
        if (animate) {
            transaction.setCustomAnimations(R.anim.settings_enter_from_right, R.anim.settings_exit_to_left)
        }
        transaction
            .replace(R.id.fragment_container_view, SettingsFragment.page(page))
            .commit()
    }

    private fun clearSearch() {
        searchView?.setQuery("", false)
        searchItem?.collapseActionView()
        searchQuery = ""
        currentRootFragment()?.setSearchQuery("")
    }

    private fun currentRootFragment(): SettingsFragment? {
        return supportFragmentManager.findFragmentByTag(ROOT_FRAGMENT_TAG) as? SettingsFragment
    }

    companion object {
        const val EXTRA_PAGE = "settingsPageExtra"
        private const val CURRENT_PAGE_KEY = "currentSettingsPage"
        private const val SEARCH_QUERY_KEY = "settingsSearchQuery"
        private const val ROOT_FRAGMENT_TAG = "rootSettingsFragment"
    }
}
