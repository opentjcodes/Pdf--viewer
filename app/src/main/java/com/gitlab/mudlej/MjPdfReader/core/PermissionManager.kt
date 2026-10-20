// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.StorageAccessOverride
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PermissionManager(
    private val activity: AppCompatActivity,
    private val onAccessChanged: (Boolean) -> Unit = {},
) {

    private var lastKnownAccess = hasFullAccess()
    private var legacyDeniedPermanently = false

    fun hasFullAccess(): Boolean = hasFullAccess(activity)

    fun recheck(): Boolean {
        val access = hasFullAccess()
        if (access == lastKnownAccess) {
            return false
        }
        lastKnownAccess = access
        onAccessChanged(access)
        return true
    }

    fun requestFullAccess() {
        if (hasFullAccess()) {
            recheck()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.storage_permission_rationale_title)
            .setMessage(R.string.storage_permission_rationale_message)
            .setPositiveButton(R.string.storage_permission_continue) { _, _ -> launchAccessRequest() }
            .setNegativeButton(R.string.storage_permission_not_now, null)
            .show()
    }

    private fun launchAccessRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchFirstWorkingIntent(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                ),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                appDetailsSettingsIntent(),
            )
        } else if (legacyDeniedPermanently) {
            launchFirstWorkingIntent(appDetailsSettingsIntent())
        } else {
            legacyPermissionLauncher.launch(legacyStoragePermissions())
        }
    }

    private fun legacyStoragePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun appDetailsSettingsIntent() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${BuildConfig.APPLICATION_ID}")
    )

    private fun launchFirstWorkingIntent(vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { settingsLauncher.launch(intent) }.isSuccess) {
                return
            }
        }
    }

    private val settingsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            recheck()
        }

    private val legacyPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val readGranted = results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            if (!readGranted && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.READ_EXTERNAL_STORAGE
                )
            ) {
                legacyDeniedPermanently = true
            }
            recheck()
        }

    companion object {

        fun hasFullAccess(context: Context): Boolean {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
            if (BuildConfig.DEBUG) {
                return StorageAccessOverride.apply(granted)
            }
            return granted
        }
    }
}
