/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class ApiUtilsUserAgentTest {

    @Test
    fun userAgentVersionIsNewEnoughForTheServerChecks() {
        val match = Regex("""^Mozilla/5\.0 \(Android\) Nextcloud-Talk v(\d+)\.""").find(ApiUtils.userAgent)

        assertTrue("user agent must carry a Talk version: ${ApiUtils.userAgent}", match != null)
        // the server requires 25.0.0 or newer for calls that may be end-to-end encrypted
        assertTrue(match!!.groupValues[1].toInt() >= MIN_SERVER_MAJOR_VERSION)
    }

    private companion object {
        const val MIN_SERVER_MAJOR_VERSION = 25
    }
}
