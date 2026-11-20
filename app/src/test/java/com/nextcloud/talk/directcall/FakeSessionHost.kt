/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

class FakeSessionHost(override val scope: CoroutineScope, private val clock: () -> Long) : DirectCallSession.Host {
    val requests = mutableListOf<Pair<String, JsonObject?>>()
    val enqueued = mutableListOf<Pair<String, JsonObject>>()
    var remembered = 0
    var handler: suspend (String) -> CallAttempt? = { null }

    override fun uptimeMillis(): Long = clock()

    override suspend fun request(session: DirectCallSession, path: String, body: JsonObject?): CallAttempt? {
        requests += path to body
        return handler(path)
    }

    override fun enqueue(session: DirectCallSession, path: String, body: JsonObject) {
        enqueued += path to body
    }

    override fun noteRevision(session: DirectCallSession) = Unit

    override fun remember(session: DirectCallSession) {
        remembered++
    }

    override fun changed(session: DirectCallSession) = Unit

    fun paths() = requests.map { it.first }
}
