// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

fun StringBuilder.appendExportSection(label: String, text: String) {
    appendLine("--- $label Start")
    appendLine(text)
    appendLine("--- $label End")
}
