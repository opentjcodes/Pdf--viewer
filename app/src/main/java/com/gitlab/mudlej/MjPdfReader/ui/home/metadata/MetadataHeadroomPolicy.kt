// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import kotlin.math.max

data class MetadataMemorySample(
    val availableBytes: Long,
    val thresholdBytes: Long,
    val totalBytes: Long,
    val lowMemory: Boolean,
    val baselineNativePssBytes: Long,
    val nativePssBytes: Long,
)

enum class MetadataRecycleReason {
    CONTINUE,
    LOW_MEMORY,
    HEADROOM_EXHAUSTED,
    HEADROOM_FALLING,
    RETAINED_GROWTH,
    PREDICTED_GROWTH,
}

data class MetadataHeadroomDecision(
    val reason: MetadataRecycleReason,
    val rawHeadroom: Long,
    val safetyReserve: Long,
    val usableHeadroom: Long,
    val workerGrowth: Long,
    val workerGrowthBudget: Long,
    val predictedGrowth: Long,
    val recentMedianGrowth: Long,
    val lastPositiveGrowth: Long,
) {
    val recycle: Boolean get() = reason != MetadataRecycleReason.CONTINUE
}

class MetadataHeadroomPolicy {
    private var previousRawHeadroom: Long? = null
    private var previousNativePssBytes: Long? = null
    private val recentPositiveGrowth = ArrayDeque<Long>()

    fun reset() {
        previousRawHeadroom = null
        previousNativePssBytes = null
        recentPositiveGrowth.clear()
    }

    fun evaluate(sample: MetadataMemorySample): MetadataHeadroomDecision {
        val headroom = Headroom(sample)
        val growth = measureGrowth(sample)
        val reason = recycleReason(sample.lowMemory, headroom, growth)

        previousRawHeadroom = headroom.raw
        previousNativePssBytes = sample.nativePssBytes
        return decision(reason, headroom, growth)
    }

    private fun measureGrowth(sample: MetadataMemorySample): Growth {
        val nativePssDelta = previousNativePssBytes?.let { sample.nativePssBytes - it } ?: 0L
        if (nativePssDelta > 0L) {
            recentPositiveGrowth.addLast(nativePssDelta)
            while (recentPositiveGrowth.size > TREND_WINDOW) recentPositiveGrowth.removeFirst()
        }
        return Growth(
            retained = max(sample.nativePssBytes - sample.baselineNativePssBytes, 0L),
            medianIncrease = medianGrowth(),
            lastIncrease = recentPositiveGrowth.lastOrNull() ?: 0L,
        )
    }

    private fun medianGrowth(): Long {
        if (recentPositiveGrowth.isEmpty()) return 0L
        return recentPositiveGrowth.sorted()[recentPositiveGrowth.size / 2]
    }

    private fun isHeadroomFalling(rawHeadroom: Long): Boolean {
        val previous = previousRawHeadroom ?: return false
        return previous > 0L && rawHeadroom <= previous - percent(previous, 25)
    }

    private fun recycleReason(lowMemory: Boolean, headroom: Headroom, growth: Growth): MetadataRecycleReason =
        when {
            lowMemory -> MetadataRecycleReason.LOW_MEMORY
            headroom.usable <= 0L -> MetadataRecycleReason.HEADROOM_EXHAUSTED
            isHeadroomFalling(headroom.raw) -> MetadataRecycleReason.HEADROOM_FALLING
            growth.retained >= headroom.growthBudget && growth.retained > 0L -> MetadataRecycleReason.RETAINED_GROWTH
            growth.predicted >= headroom.growthBudget && growth.predicted > 0L -> MetadataRecycleReason.PREDICTED_GROWTH
            else -> MetadataRecycleReason.CONTINUE
        }

    private fun decision(reason: MetadataRecycleReason, headroom: Headroom, growth: Growth) =
        MetadataHeadroomDecision(
            reason = reason,
            rawHeadroom = headroom.raw,
            safetyReserve = headroom.reserve,
            usableHeadroom = headroom.usable,
            workerGrowth = growth.retained,
            workerGrowthBudget = headroom.growthBudget,
            predictedGrowth = growth.predicted,
            recentMedianGrowth = growth.medianIncrease,
            lastPositiveGrowth = growth.lastIncrease,
        )

    private class Headroom(sample: MetadataMemorySample) {
        val raw = max(sample.availableBytes - sample.thresholdBytes, 0L)
        val reserve = max(percent(raw, 20), percent(sample.totalBytes, 3))
        val usable = max(raw - reserve, 0L)
        val growthBudget = percent(usable, 25)
    }

    private class Growth(val retained: Long, val medianIncrease: Long, val lastIncrease: Long) {
        val predicted = retained + max(medianIncrease, lastIncrease)
    }

    companion object {
        private const val TREND_WINDOW = 5

        private fun percent(value: Long, percent: Int): Long =
            value / 100L * percent + value % 100L * percent / 100L
    }
}
