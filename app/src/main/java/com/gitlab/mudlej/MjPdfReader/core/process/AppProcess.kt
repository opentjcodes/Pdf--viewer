// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.process

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

object AppProcess {
    fun currentName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val pid = Process.myPid()
        val activityName = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
        if (activityName != null) return activityName
        return runCatching {
            File("/proc/self/cmdline").readText().trimEnd('\u0000')
        }.getOrNull()
    }

    fun isPdfMetadata(context: Context): Boolean =
        currentName(context)?.endsWith(":pdf_metadata") == true
}
