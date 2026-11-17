/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import kotlin.math.min

/**
 * Delays between the retries of a repeatedly failing request: 1, 2, 4, 8 and 16 seconds, then 30 seconds for every
 * further retry until [reset] is called after a successful request.
 */
class RetryBackoff(
    private val initialDelayMillis: Long = INITIAL_DELAY_MILLIS,
    private val maxDelayMillis: Long = MAX_DELAY_MILLIS
) {
    private var nextDelayMillis = initialDelayMillis

    @Synchronized
    fun nextDelayMillis(): Long {
        val delayMillis = nextDelayMillis
        nextDelayMillis = min(delayMillis * 2, maxDelayMillis)
        return delayMillis
    }

    @Synchronized
    fun reset() {
        nextDelayMillis = initialDelayMillis
    }

    companion object {
        const val INITIAL_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 30_000L
    }
}
