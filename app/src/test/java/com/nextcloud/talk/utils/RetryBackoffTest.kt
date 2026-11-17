/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryBackoffTest {

    @Test
    fun doublesTheDelayUpToThirtySeconds() {
        val backoff = RetryBackoff()

        val delays = List(8) { backoff.nextDelayMillis() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun startsOverAfterReset() {
        val backoff = RetryBackoff()
        repeat(6) { backoff.nextDelayMillis() }

        backoff.reset()

        assertEquals(1_000L, backoff.nextDelayMillis())
        assertEquals(2_000L, backoff.nextDelayMillis())
    }
}
