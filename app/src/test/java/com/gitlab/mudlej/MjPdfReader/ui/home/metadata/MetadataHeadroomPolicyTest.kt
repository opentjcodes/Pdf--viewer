// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataHeadroomPolicyTest {
    @Test fun lowMemoryRecycles() {
        assertEquals(MetadataRecycleReason.LOW_MEMORY, policy().evaluate(sample(low = true)).reason)
    }

    @Test fun exhaustedHeadroomRecycles() {
        assertEquals(
            MetadataRecycleReason.HEADROOM_EXHAUSTED,
            policy().evaluate(sample(avail = 100, threshold = 100)).reason,
        )
    }

    @Test fun rapidHeadroomFallRecycles() {
        val policy = policy()
        policy.evaluate(sample(avail = 1_000))
        assertEquals(
            MetadataRecycleReason.HEADROOM_FALLING,
            policy.evaluate(sample(avail = 700)).reason,
        )
    }

    @Test fun retainedGrowthUsesProportionalBudget() {
        val decision = policy().evaluate(sample(native = 190, baseline = 0))
        assertEquals(MetadataRecycleReason.RETAINED_GROWTH, decision.reason)
    }

    @Test fun predictedGrowthRecyclesBeforeCrossingBudget() {
        val policy = policy()
        assertFalse(policy.evaluate(sample(native = 100, baseline = 0)).recycle)
        assertEquals(
            MetadataRecycleReason.PREDICTED_GROWTH,
            policy.evaluate(sample(native = 150, baseline = 0)).reason,
        )
    }

    @Test fun policyIsScaleInvariant() {
        val small = policy().evaluate(sample(native = 190, baseline = 0))
        val large = policy().evaluate(
            sample(avail = 10_000, threshold = 1_000, total = 20_000, native = 1_900, baseline = 0)
        )
        assertEquals(small.reason, large.reason)
    }

    @Test fun resetDropsBaselineTrend() {
        val policy = policy()
        policy.evaluate(sample(native = 100, baseline = 0))
        policy.evaluate(sample(native = 150, baseline = 0))
        policy.reset()
        val fresh = policy.evaluate(sample(native = 1_000, baseline = 1_000))
        assertEquals(MetadataRecycleReason.CONTINUE, fresh.reason)
        assertEquals(0L, fresh.predictedGrowth)
    }

    @Test fun documentCountNeverTriggersRecycleWithoutGrowth() {
        val policy = policy()
        repeat(10_000) {
            assertFalse(policy.evaluate(sample(native = 10, baseline = 10)).recycle)
        }
    }

    @Test fun budgetScalesWithCurrentHeadroom() {
        val decision = policy().evaluate(sample())
        assertTrue(decision.workerGrowthBudget > 0)
        assertEquals(decision.usableHeadroom / 4, decision.workerGrowthBudget)
    }

    @Test fun decisionReportsCalculatedValues() {
        assertEquals(
            MetadataHeadroomDecision(
                reason = MetadataRecycleReason.CONTINUE,
                rawHeadroom = 900,
                safetyReserve = 180,
                usableHeadroom = 720,
                workerGrowth = 100,
                workerGrowthBudget = 180,
                predictedGrowth = 100,
                recentMedianGrowth = 0,
                lastPositiveGrowth = 0,
            ),
            policy().evaluate(sample(native = 100)),
        )
    }

    @Test fun growthHistoryKeepsLastFivePositiveIncreases() {
        val policy = policy()
        policy.evaluate(sample())
        for (native in listOf(300L, 301L, 303L, 306L, 310L)) {
            policy.evaluate(sample(native = native))
        }
        val decision = policy.evaluate(sample(native = 315))
        assertEquals(3L, decision.recentMedianGrowth)
        assertEquals(5L, decision.lastPositiveGrowth)
        assertEquals(320L, decision.predictedGrowth)
    }

    @Test fun zeroAndNegativeChangesPreservePositiveTrend() {
        val policy = policy()
        for (native in listOf(100L, 130L, 130L, 100L)) {
            policy.evaluate(sample(native = native))
        }
        val decision = policy.evaluate(sample(native = 120))
        assertEquals(30L, decision.recentMedianGrowth)
        assertEquals(20L, decision.lastPositiveGrowth)
        assertEquals(150L, decision.predictedGrowth)
    }

    @Test fun recycleReasonsKeepTheirPriority() {
        val cases = listOf(
            sample(avail = 100, native = 1_000, low = true) to MetadataRecycleReason.LOW_MEMORY,
            sample(avail = 100, native = 1_000) to MetadataRecycleReason.HEADROOM_EXHAUSTED,
            sample(avail = 700, native = 1_000) to MetadataRecycleReason.HEADROOM_FALLING,
            sample(native = 190) to MetadataRecycleReason.RETAINED_GROWTH,
            sample(native = 100) to MetadataRecycleReason.PREDICTED_GROWTH,
            sample() to MetadataRecycleReason.CONTINUE,
        )
        for ((nextSample, expectedReason) in cases) {
            val policy = policy()
            policy.evaluate(sample())
            assertEquals(expectedReason, policy.evaluate(nextSample).reason)
        }
    }

    private fun policy() = MetadataHeadroomPolicy()

    private fun sample(
        avail: Long = 1_000,
        threshold: Long = 100,
        total: Long = 2_000,
        low: Boolean = false,
        baseline: Long = 0,
        native: Long = 0,
    ) = MetadataMemorySample(avail, threshold, total, low, baseline, native)
}
