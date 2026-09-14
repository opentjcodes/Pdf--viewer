// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.text_mode

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import com.shockwave.pdfium.PdfiumCore
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object StructuredTextFormatter {

    private const val ROTATION_ANGLE_THRESHOLD = 0.0873f
    private const val ROTATED_FRACTION_LIMIT = 0.20f
    private const val HARD_BREAK_BASELINE_FACTOR = 0.75f
    private const val SOFT_BREAK_BASELINE_FACTOR = 0.30f
    private const val FALLBACK_SIZE_HEIGHT_FACTOR = 0.85f
    private const val BODY_SIZE_TOLERANCE = 0.2f
    private const val MEDIAN_GAP_FALLBACK_FACTOR = 1.3f
    private const val GAP_MERGE_FACTOR = 1.45f
    private const val SIZE_MERGE_TOLERANCE_FACTOR = 0.15f
    private const val SIZE_MERGE_TOLERANCE_MIN = 0.75f
    private const val FULL_WIDTH_FACTOR = 0.70f
    private const val LOOSE_WIDTH_FACTOR = 0.55f
    private const val INDENT_START_FACTOR = 0.6f
    private const val INDENT_BASE_FACTOR = 0.3f
    private const val SHORT_RUN_MIN_LINES = 3
    private const val HEADING_MAX_CHARS = 120
    private const val HEADING_MAX_LINES = 2
    private const val BOLD_WEIGHT = 600
    private const val BOLD_FRACTION_THRESHOLD = 0.8f
    private const val H1_RATIO = 1.35f
    private const val H2_RATIO = 1.18f
    private const val H3_RATIO = 1.08f
    private const val H3_BOLD_RATIO = 0.98f
    private const val FIXED_PITCH_FLAG = 1
    private const val CODE_LINE_MONO_FRACTION = 0.6f
    private const val CODE_INDENT_MAX_CELLS = 40
    private const val SCRIPT_SIZE_FACTOR = 0.85f
    private const val SCRIPT_HEIGHT_FACTOR = 0.8f
    private const val SCRIPT_OFFSET_FACTOR = 0.2f
    private const val SCRIPT_SPAN_SIZE = 0.75f
    private const val SCRIPT_MAX_FRACTION = 0.3f
    private const val DETRACK_MIN_SINGLES = 5
    private const val DETRACK_GAP_FACTOR = 1.6f
    private const val DROP_CAP_SIZE_RATIO = 1.8f
    private val HEADING_SPAN_SIZES = floatArrayOf(1.35f, 1.2f, 1.1f)
    private val NUMBERED_HEADING_START = Regex("^\\d+(\\.\\d+)*[.)]?\\s")
    private const val HEADING_END_DISQUALIFIERS = ",;،؛"

    fun format(
        metrics: PageCharMetrics,
        joinParagraphs: Boolean,
        detectHeadings: Boolean,
        detectCodeBlocks: Boolean,
    ): CharSequence? {
        if (metrics.count == 0 || isRotated(metrics)) return null
        val lines = clusterLines(metrics)
        if (lines.isEmpty()) return null
        val stats = computePageStats(lines)
        val levels = IntArray(lines.size) { index ->
            val line = lines[index]
            if (!detectHeadings || (detectCodeBlocks && line.isCode)) 0 else headingLevel(line, stats.bodySize)
        }
        val paragraphs = mergeParagraphs(lines, levels, stats, joinParagraphs, detectCodeBlocks)
        return renderSpannable(paragraphs, if (joinParagraphs) "\n\n" else "\n")
    }

    private class Cell(
        val text: String,
        val isValid: Boolean,
        val isSpace: Boolean,
        val bottom: Float,
        val height: Float,
        val width: Float,
        val fontSize: Float,
        var gap: Float,
        val left: Float = 0f,
    )

    private class CellGroup(val isSpace: Boolean, val cells: MutableList<Cell>)

    private class InlineMark(val start: Int, val end: Int, val superscript: Boolean)

    private class LineAccumulator {
        val cells = ArrayList<Cell>()
        val unresolvedSpaces = ArrayList<Cell>()
        var rightEdgeAtSpace = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var sumBottom = 0f
        var sumHeight = 0f
        var validCount = 0
        var lastValidLeft = 0f
        var lastValidRight = 0f
        var maxWeight = 0
        var boldCount = 0
        var monoCount = 0
        var rtlLetters = 0
        var ltrLetters = 0
    }

    private class TextLine(
        val text: String,
        val marks: List<InlineMark>,
        val minX: Float,
        val maxX: Float,
        val baseline: Float,
        val fontSize: Float,
        val maxWeight: Int,
        val boldFraction: Float,
        val validCharCount: Int,
        val avgCharWidth: Float,
        val isCode: Boolean,
        val rtlDominant: Boolean,
        val gutter: ClosedFloatingPointRange<Float>?,
    )

    private class PageStats(
        val bodySize: Float,
        val medianGap: Float,
        val bodyMinX: Float,
        val bodyMaxX: Float,
        val typicalWidth: Float,
    )

    private class Paragraph(
        val lines: MutableList<TextLine>,
        val level: Int,
        val isCode: Boolean,
        var lastIndex: Int,
        var dropCapPrefix: String = "",
    )

    private fun isRotated(metrics: PageCharMetrics): Boolean {
        var printable = 0
        var angled = 0
        for (i in 0 until metrics.count) {
            if (metrics.codePoint[i] < 0x21) continue
            if (metrics.right[i] - metrics.left[i] <= 0f || metrics.top[i] - metrics.bottom[i] <= 0f) continue
            printable++
            if (abs(metrics.angleRadians[i]) > ROTATION_ANGLE_THRESHOLD) angled++
        }
        return printable == 0 || angled > ROTATED_FRACTION_LIMIT * printable
    }

    private fun clusterLines(metrics: PageCharMetrics): List<TextLine> {
        val lines = ArrayList<TextLine>()
        var accumulator = LineAccumulator()
        val pendingCells = ArrayList<Cell>()
        var softBreakPending = false
        var joinWrapPending = false

        fun appendNoStatsText(text: String) {
            val cell = Cell(text, false, false, 0f, 0f, 0f, 0f, -1f)
            if (softBreakPending) pendingCells.add(cell) else accumulator.cells.add(cell)
        }

        fun appendNoStats(codePoint: Int) {
            appendNoStatsText(expandCodePoint(codePoint))
        }

        fun appendSpace() {
            val cell = Cell(" ", false, true, 0f, 0f, 0f, 0f, -1f)
            if (accumulator.unresolvedSpaces.isEmpty()) {
                accumulator.rightEdgeAtSpace = accumulator.lastValidRight
            }
            accumulator.cells.add(cell)
            accumulator.unresolvedSpaces.add(cell)
        }

        fun finalizeLine() {
            toTextLine(accumulator)?.let(lines::add)
            accumulator = LineAccumulator()
        }

        for (i in 0 until metrics.count) {
            val codePoint = metrics.codePoint[i]
            if (codePoint == 0x0A || codePoint == 0x0D) {
                if (!joinWrapPending) softBreakPending = true
                continue
            }
            if (codePoint == 0xFFFE || codePoint == 0x02) {
                if (!joinWrapPending && accumulator.cells.isNotEmpty()) {
                    accumulator.cells.add(Cell("­", false, false, 0f, 0f, 0f, 0f, -1f))
                }
                joinWrapPending = true
                continue
            }
            joinWrapPending = false

            val width = metrics.right[i] - metrics.left[i]
            val height = metrics.top[i] - metrics.bottom[i]

            if (codePoint == 0) {
                if (width > 0f) appendNoStats(0xFFFD)
                continue
            }
            if (isWhitespace(codePoint)) {
                if (!softBreakPending && accumulator.cells.isNotEmpty()) appendSpace()
                continue
            }
            if (codePoint < 0x20 || codePoint == 0xFEFF) continue
            if (isBidiControl(codePoint)) {
                appendNoStats(codePoint)
                continue
            }
            if (isCombiningMark(codePoint)) {
                val markText = expandCodePoint(codePoint).replace("\u200B", "")
                if (markText.isNotEmpty()) appendNoStatsText(markText)
                continue
            }
            if (width <= 0f || height <= 0f) {
                appendNoStats(codePoint)
                continue
            }

            if (accumulator.validCount > 0) {
                val refHeight = accumulator.sumHeight / accumulator.validCount
                val lineBaseline = accumulator.sumBottom / accumulator.validCount
                val baselineShift = abs(metrics.bottom[i] - lineBaseline)
                val xReset = if (accumulator.rtlLetters > accumulator.ltrLetters) {
                    metrics.left[i] > accumulator.lastValidLeft + refHeight
                } else {
                    metrics.left[i] < accumulator.lastValidRight - refHeight
                }
                val breakLine = baselineShift > HARD_BREAK_BASELINE_FACTOR * refHeight ||
                    (xReset && (softBreakPending || baselineShift > SOFT_BREAK_BASELINE_FACTOR * refHeight))
                if (breakLine) {
                    finalizeLine()
                } else if (softBreakPending) {
                    appendSpace()
                }
            }
            if (pendingCells.isNotEmpty()) {
                accumulator.cells.addAll(pendingCells)
                pendingCells.clear()
            }
            appendValidChar(accumulator, metrics, i, width, height)
            softBreakPending = false
        }
        if (pendingCells.isNotEmpty()) accumulator.cells.addAll(pendingCells)
        finalizeLine()
        return lines
    }

    private fun appendValidChar(
        accumulator: LineAccumulator,
        metrics: PageCharMetrics,
        i: Int,
        width: Float,
        height: Float,
    ) {
        val codePoint = metrics.codePoint[i]
        if (accumulator.unresolvedSpaces.isNotEmpty()) {
            val gap = metrics.left[i] - accumulator.rightEdgeAtSpace
            for (space in accumulator.unresolvedSpaces) {
                space.gap = gap
            }
            accumulator.unresolvedSpaces.clear()
        }
        accumulator.cells.add(
            Cell(
                text = expandCodePoint(codePoint),
                isValid = true,
                isSpace = false,
                bottom = metrics.bottom[i],
                height = height,
                width = width,
                fontSize = metrics.fontSize[i],
                gap = -1f,
                left = metrics.left[i],
            )
        )
        accumulator.minX = min(accumulator.minX, metrics.left[i])
        accumulator.maxX = max(accumulator.maxX, metrics.right[i])
        accumulator.sumBottom += metrics.bottom[i]
        accumulator.sumHeight += height
        accumulator.validCount++
        accumulator.lastValidLeft = metrics.left[i]
        accumulator.lastValidRight = metrics.right[i]
        val weight = metrics.fontWeight[i]
        if (weight > 0) {
            accumulator.maxWeight = max(accumulator.maxWeight, weight)
            if (weight >= BOLD_WEIGHT) accumulator.boldCount++
        }
        val flags = metrics.fontFlags[i]
        if (flags >= 0 && flags and FIXED_PITCH_FLAG != 0) accumulator.monoCount++
        if (Character.isLetter(codePoint)) {
            if (isRtlCodePoint(codePoint)) accumulator.rtlLetters++ else accumulator.ltrLetters++
        }
    }

    private fun toTextLine(accumulator: LineAccumulator): TextLine? {
        if (accumulator.validCount == 0) return null
        val validCells = accumulator.cells.filter { it.isValid }
        val baseline = median(validCells.map { it.bottom })
        val refHeight = median(validCells.map { it.height })
        val avgCharWidth = median(validCells.map { it.width })
        val sizes = validCells.mapNotNull { cell -> cell.fontSize.takeIf { it > 0f } }
        val lineSize = if (sizes.isNotEmpty()) median(sizes) else FALLBACK_SIZE_HEIGHT_FACTOR * refHeight
        val isCode = accumulator.monoCount >= CODE_LINE_MONO_FRACTION * accumulator.validCount

        var gutter: ClosedFloatingPointRange<Float>? = null
        var rightEdge = accumulator.minX
        for (cell in validCells.sortedBy { it.left }) {
            val start = rightEdge + 1.5f * refHeight
            val end = cell.left - 1.5f * refHeight
            if (end > start && (gutter == null || end - start > gutter.endInclusive - gutter.start)) {
                gutter = start..end
            }
            rightEdge = max(rightEdge, cell.left + cell.width)
        }

        var cells: List<Cell> = accumulator.cells
        if (!isCode) {
            cells = collapseSpaces(detrack(cells))
        }
        cells = trimSpaces(cells)

        val textBuilder = StringBuilder()
        val rawMarks = ArrayList<InlineMark>()
        for (cell in cells) {
            val start = textBuilder.length
            textBuilder.append(cell.text)
            if (isCode || !cell.isValid || cell.text.isEmpty() || !cell.text[0].isLetterOrDigit()) continue
            val deviation = cell.bottom - baseline
            val smaller = (cell.fontSize > 0f && lineSize > 0f && cell.fontSize < SCRIPT_SIZE_FACTOR * lineSize) ||
                cell.height < SCRIPT_HEIGHT_FACTOR * refHeight
            if (smaller &&
                abs(deviation) > SCRIPT_OFFSET_FACTOR * refHeight &&
                abs(deviation) <= HARD_BREAK_BASELINE_FACTOR * refHeight
            ) {
                rawMarks.add(InlineMark(start, textBuilder.length, deviation > 0f))
            }
        }
        val text = textBuilder.toString()
        if (text.isBlank()) return null

        val marks = mergeMarks(rawMarks)
        val markedChars = marks.sumOf { it.end - it.start }
        val finalMarks = if (markedChars > SCRIPT_MAX_FRACTION * text.length) emptyList() else marks

        return TextLine(
            text = text,
            marks = finalMarks,
            minX = accumulator.minX,
            maxX = accumulator.maxX,
            baseline = baseline,
            fontSize = lineSize,
            maxWeight = accumulator.maxWeight,
            boldFraction = accumulator.boldCount.toFloat() / accumulator.validCount,
            validCharCount = accumulator.validCount,
            avgCharWidth = avgCharWidth,
            isCode = isCode,
            rtlDominant = accumulator.rtlLetters > accumulator.ltrLetters,
            gutter = gutter,
        )
    }

    private fun detrack(cells: List<Cell>): List<Cell> {
        val groups = ArrayList<CellGroup>()
        for (cell in cells) {
            val last = groups.lastOrNull()
            if (last != null && last.isSpace == cell.isSpace) {
                last.cells.add(cell)
            } else {
                groups.add(CellGroup(cell.isSpace, mutableListOf(cell)))
            }
        }

        fun isSingleLetterToken(group: CellGroup): Boolean {
            if (group.isSpace || group.cells.size != 1) return false
            val cell = group.cells[0]
            return cell.isValid && cell.text.length == 1 && cell.text[0].isLetterOrDigit()
        }

        val tokenCount = groups.count { !it.isSpace }
        val singles = groups.count { isSingleLetterToken(it) }
        if (singles < DETRACK_MIN_SINGLES || singles * 2 < tokenCount) return cells

        fun flanked(index: Int): Boolean {
            val before = groups.getOrNull(index - 1) ?: return false
            val after = groups.getOrNull(index + 1) ?: return false
            return isSingleLetterToken(before) && isSingleLetterToken(after)
        }

        val letterGaps = ArrayList<Float>()
        for (index in groups.indices) {
            val group = groups[index]
            if (group.isSpace && flanked(index)) {
                val gap = group.cells[0].gap
                if (gap >= 0f) letterGaps.add(gap)
            }
        }
        val gapThreshold = if (letterGaps.isEmpty()) -1f else DETRACK_GAP_FACTOR * median(letterGaps)

        val result = ArrayList<Cell>(cells.size)
        for (index in groups.indices) {
            val group = groups[index]
            if (group.isSpace && flanked(index)) {
                val gap = group.cells[0].gap
                val isLetterGap = if (gapThreshold >= 0f) {
                    gap in 0f..gapThreshold || (gap < 0f && group.cells.size == 1)
                } else {
                    group.cells.size == 1
                }
                if (isLetterGap) continue
            }
            result.addAll(group.cells)
        }
        return result
    }

    private fun collapseSpaces(cells: List<Cell>): List<Cell> {
        val result = ArrayList<Cell>(cells.size)
        for (cell in cells) {
            if (cell.isSpace && result.lastOrNull()?.isSpace == true) continue
            result.add(cell)
        }
        return result
    }

    private fun trimSpaces(cells: List<Cell>): List<Cell> {
        var start = 0
        var end = cells.size
        while (start < end && cells[start].isSpace) start++
        while (end > start && cells[end - 1].isSpace) end--
        return cells.subList(start, end)
    }

    private fun mergeMarks(marks: List<InlineMark>): List<InlineMark> {
        val merged = ArrayList<InlineMark>()
        for (mark in marks) {
            val last = merged.lastOrNull()
            if (last != null && last.superscript == mark.superscript && last.end == mark.start) {
                merged[merged.size - 1] = InlineMark(last.start, mark.end, last.superscript)
            } else {
                merged.add(mark)
            }
        }
        return merged
    }

    private fun computePageStats(lines: List<TextLine>): PageStats {
        val weightBySize = HashMap<Float, Int>()
        for (line in lines) {
            val bucket = (line.fontSize * 2).roundToInt() / 2f
            if (bucket > 0f) weightBySize[bucket] = (weightBySize[bucket] ?: 0) + line.validCharCount
        }
        var bodySize = 0f
        var bestWeight = -1
        for ((size, weight) in weightBySize) {
            if (weight > bestWeight || (weight == bestWeight && size < bodySize)) {
                bodySize = size
                bestWeight = weight
            }
        }
        val bodyLines = if (bodySize > 0f) {
            lines.filter { abs(it.fontSize - bodySize) <= BODY_SIZE_TOLERANCE * bodySize }.ifEmpty { lines }
        } else {
            lines
        }
        val gaps = ArrayList<Float>()
        for (k in 0 until bodyLines.size - 1) {
            val drop = bodyLines[k].baseline - bodyLines[k + 1].baseline
            if (drop > 0f) gaps.add(drop)
        }
        val medianGap = if (gaps.isNotEmpty()) median(gaps) else MEDIAN_GAP_FALLBACK_FACTOR * bodySize
        val bodyMinX = median(bodyLines.map { it.minX })
        val bodyMaxX = median(bodyLines.map { it.maxX })
        val widths = bodyLines.map { it.maxX - it.minX }.sorted()
        val typicalWidth = if (widths.isNotEmpty()) widths[((widths.size - 1) * 9) / 10] else bodyMaxX - bodyMinX
        return PageStats(bodySize, medianGap, bodyMinX, bodyMaxX, typicalWidth)
    }

    private fun headingLevel(line: TextLine, bodySize: Float): Int {
        if (bodySize <= 0f) return 0
        val ratio = line.fontSize / bodySize
        val level = when {
            ratio >= H1_RATIO -> 1
            ratio >= H2_RATIO -> 2
            ratio >= H3_RATIO -> 3
            ratio >= H3_BOLD_RATIO && line.boldFraction >= BOLD_FRACTION_THRESHOLD && line.maxWeight >= BOLD_WEIGHT -> 3
            else -> 0
        }
        if (level == 0) return 0
        val text = line.text
        if (text.length > HEADING_MAX_CHARS) return 0
        if (text.last() in HEADING_END_DISQUALIFIERS) return 0
        val numbered = NUMBERED_HEADING_START.containsMatchIn(text)
        if (text.last() == '.' && !numbered) return 0
        if (TextModeTextFormatter.isListStart(text) && !numbered) return 0
        return level
    }

    private fun mergeParagraphs(
        lines: List<TextLine>,
        levels: IntArray,
        stats: PageStats,
        joinParagraphs: Boolean,
        detectCodeBlocks: Boolean,
    ): List<Paragraph> {
        val shortRun = markShortRuns(lines, stats.typicalWidth)
        val tableRows = BooleanArray(lines.size)
        for (index in 0 until lines.size - 2) {
            val rows = lines.subList(index, index + 3)
            if (rows.any { it.isCode || it.gutter == null }) continue
            if (rows.zipWithNext().any { (a, b) ->
                    val gap = a.baseline - b.baseline
                    gap <= 0f || gap > GAP_MERGE_FACTOR * stats.medianGap
                }) continue
            if (rows.maxOf { it.gutter!!.start } < rows.minOf { it.gutter!!.endInclusive }) {
                for (row in index..index + 2) tableRows[row] = true
            }
        }
        val paragraphs = ArrayList<Paragraph>()
        for (index in lines.indices) {
            val line = lines[index]
            val current = paragraphs.lastOrNull()
            if (current != null && !tableRows[current.lastIndex] && !tableRows[index] &&
                canMerge(current, line, index, levels, shortRun, stats, joinParagraphs, detectCodeBlocks)
            ) {
                current.lines.add(line)
                current.lastIndex = index
            } else {
                paragraphs.add(
                    Paragraph(
                        lines = mutableListOf(line),
                        level = levels[index],
                        isCode = detectCodeBlocks && line.isCode,
                        lastIndex = index,
                    )
                )
            }
        }
        return absorbDropCaps(paragraphs, stats)
    }

    private fun absorbDropCaps(paragraphs: List<Paragraph>, stats: PageStats): List<Paragraph> {
        if (stats.bodySize <= 0f) return paragraphs
        val result = ArrayList<Paragraph>(paragraphs.size)
        var index = 0
        while (index < paragraphs.size) {
            val paragraph = paragraphs[index]
            val next = paragraphs.getOrNull(index + 1)
            if (next != null && !next.isCode && next.level == 0 && isDropCap(paragraph, stats)) {
                next.dropCapPrefix = paragraph.lines.first().text
                index++
                continue
            }
            result.add(paragraph)
            index++
        }
        return result
    }

    private fun isDropCap(paragraph: Paragraph, stats: PageStats): Boolean {
        if (paragraph.isCode || paragraph.lines.size != 1 || paragraph.dropCapPrefix.isNotEmpty()) return false
        val line = paragraph.lines.first()
        return line.text.length == 1 &&
            line.text[0].isLetter() &&
            line.marks.isEmpty() &&
            line.fontSize >= DROP_CAP_SIZE_RATIO * stats.bodySize
    }

    private fun canMerge(
        current: Paragraph,
        next: TextLine,
        nextIndex: Int,
        levels: IntArray,
        shortRun: BooleanArray,
        stats: PageStats,
        joinParagraphs: Boolean,
        detectCodeBlocks: Boolean,
    ): Boolean {
        val last = current.lines.last()
        val nextIsCode = detectCodeBlocks && next.isCode
        if (current.isCode || nextIsCode) {
            if (!current.isCode || !nextIsCode) return false
            val gap = last.baseline - next.baseline
            return gap > 0f && gap <= GAP_MERGE_FACTOR * stats.medianGap
        }
        if (levels[nextIndex] != current.level) return false
        val gap = last.baseline - next.baseline
        if (gap <= 0f || gap > GAP_MERGE_FACTOR * stats.medianGap) return false
        if (stats.bodySize > 0f) {
            val tolerance = max(SIZE_MERGE_TOLERANCE_FACTOR * stats.bodySize, SIZE_MERGE_TOLERANCE_MIN)
            if (abs(next.fontSize - last.fontSize) > tolerance) return false
        }
        if (current.level > 0) return true
        if (!joinParagraphs) return false
        val lastWidth = last.maxX - last.minX
        val fullEnough = lastWidth >= FULL_WIDTH_FACTOR * stats.typicalWidth ||
            TextModeTextFormatter.endsHyphenAfterLetter(last.text) ||
            (lastWidth >= LOOSE_WIDTH_FACTOR * stats.typicalWidth && !TextModeTextFormatter.endsWithTerminal(last.text))
        if (!fullEnough) return false
        if (TextModeTextFormatter.isListStart(next.text)) return false
        if (!TextModeTextFormatter.isListStart(last.text)) {
            val indentStep = stats.bodySize
            if (last.rtlDominant || next.rtlDominant) {
                if (next.maxX < stats.bodyMaxX - INDENT_START_FACTOR * indentStep &&
                    last.maxX >= stats.bodyMaxX - INDENT_BASE_FACTOR * indentStep
                ) return false
            } else {
                if (next.minX > stats.bodyMinX + INDENT_START_FACTOR * indentStep &&
                    last.minX <= stats.bodyMinX + INDENT_BASE_FACTOR * indentStep
                ) return false
            }
        }
        if (shortRun[current.lastIndex] && shortRun[nextIndex]) return false
        return true
    }

    private fun markShortRuns(lines: List<TextLine>, typicalWidth: Float): BooleanArray {
        val narrow = BooleanArray(lines.size) {
            lines[it].maxX - lines[it].minX < FULL_WIDTH_FACTOR * typicalWidth &&
                !TextModeTextFormatter.endsHyphenAfterLetter(lines[it].text)
        }
        val result = BooleanArray(lines.size)
        var runStart = -1
        for (i in 0..lines.size) {
            if (i < lines.size && narrow[i]) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0 && i - runStart >= SHORT_RUN_MIN_LINES) {
                    for (j in runStart until i) result[j] = true
                }
                runStart = -1
            }
        }
        return result
    }

    private fun renderSpannable(paragraphs: List<Paragraph>, separator: String): CharSequence {
        val builder = SpannableStringBuilder()
        for (paragraph in paragraphs) {
            if (builder.isNotEmpty()) builder.append(separator)
            val start = builder.length
            if (paragraph.isCode) {
                builder.append(codeBlockText(paragraph.lines))
                builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            val bases = IntArray(paragraph.lines.size)
            var joined = paragraph.dropCapPrefix + paragraph.lines.first().text
            bases[0] = paragraph.dropCapPrefix.length
            for (k in 1 until paragraph.lines.size) {
                val lineText = paragraph.lines[k].text
                joined = TextModeTextFormatter.joinFragments(joined, lineText)
                bases[k] = joined.length - lineText.length
            }
            if (joined.endsWith('­')) {
                joined = joined.dropLast(1)
            }
            builder.append(joined)

            for (k in paragraph.lines.indices) {
                for (mark in paragraph.lines[k].marks) {
                    val markStart = start + bases[k] + mark.start
                    val markEnd = start + bases[k] + mark.end
                    if (markStart < start || markEnd > builder.length || markStart >= markEnd) continue
                    val positionSpan = if (mark.superscript) SuperscriptSpan() else SubscriptSpan()
                    builder.setSpan(positionSpan, markStart, markEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(SCRIPT_SPAN_SIZE), markStart, markEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            val isHeading = paragraph.level > 0 &&
                paragraph.lines.size <= HEADING_MAX_LINES &&
                joined.length <= HEADING_MAX_CHARS
            if (isHeading) {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(
                    RelativeSizeSpan(HEADING_SPAN_SIZES[paragraph.level - 1]),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return builder
    }

    private fun codeBlockText(lines: List<TextLine>): String {
        val blockMinX = lines.minOf { it.minX }
        val cellWidths = lines.mapNotNull { line -> line.avgCharWidth.takeIf { it > 0f } }
        val cellWidth = if (cellWidths.isEmpty()) 0f else median(cellWidths)
        return lines.joinToString("\n") { line ->
            val indentCells = if (cellWidth > 0f) {
                ((line.minX - blockMinX) / cellWidth).roundToInt().coerceIn(0, CODE_INDENT_MAX_CELLS)
            } else {
                0
            }
            " ".repeat(indentCells) + line.text
        }
    }

    private fun expandCodePoint(codePoint: Int): String {
        return when (codePoint) {
            0xFB00 -> "ff"
            0xFB01 -> "fi"
            0xFB02 -> "fl"
            0xFB03 -> "ffi"
            0xFB04 -> "ffl"
            0xFB05 -> "ft"
            0xFB06 -> "st"
            else -> {
                val raw = String(Character.toChars(codePoint))
                if (codePoint in 0xFB50..0xFEFF) {
                    Normalizer.normalize(PdfiumCore.mapPresentationFormMarks(raw), Normalizer.Form.NFKC)
                } else {
                    raw
                }
            }
        }
    }

    private fun isCombiningMark(codePoint: Int): Boolean {
        if (codePoint in 0xFE70..0xFE7F) return true
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun isWhitespace(codePoint: Int): Boolean {
        return codePoint == 0x09 || codePoint == 0x20 || codePoint == 0xA0 || codePoint == 0x1680 ||
            codePoint in 0x2000..0x200A || codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000
    }

    private fun isBidiControl(codePoint: Int): Boolean {
        return codePoint == 0x200E || codePoint == 0x200F ||
            codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069
    }

    private fun isRtlCodePoint(codePoint: Int): Boolean {
        return codePoint in 0x0590..0x08FF || codePoint in 0xFB1D..0xFDFF || codePoint in 0xFE70..0xFEFF
    }
}
