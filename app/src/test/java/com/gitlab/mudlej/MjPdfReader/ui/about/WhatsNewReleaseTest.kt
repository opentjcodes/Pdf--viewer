// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewReleaseTest {

    @Test
    fun missingLegacyPreferenceRoutesToVersion3() {
        assertEquals(WhatsNewRelease.VERSION_3, whatsNewReleaseForUpgrade(0, 58))
    }

    @Test
    fun version2UpgradeRoutesToVersion3() {
        assertEquals(WhatsNewRelease.VERSION_3, whatsNewReleaseForUpgrade(56, 58))
    }

    @Test
    fun version3UpgradeRoutesToCurrentRelease() {
        assertEquals(WhatsNewRelease.CURRENT, whatsNewReleaseForUpgrade(57, 58))
    }

    @Test
    fun futureMinorUpgradeRoutesToCurrentRelease() {
        assertEquals(WhatsNewRelease.CURRENT, whatsNewReleaseForUpgrade(58, 59))
    }

    @Test
    fun currentOrNewerVersionDoesNotShowWhatsNew() {
        assertNull(whatsNewReleaseForUpgrade(58, 58))
        assertNull(whatsNewReleaseForUpgrade(59, 58))
    }
}
