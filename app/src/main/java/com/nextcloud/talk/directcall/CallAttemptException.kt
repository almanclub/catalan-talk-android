/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import kotlinx.serialization.json.JsonObject

/**
 * Failure of a call-attempt request.
 *
 * [isOcs] is true for answers of the server, including network failures with status 0. It is false when the client
 * could not use the answer or could not send the request at all; such failures are never retried by a session.
 * [attempt] is the raw snapshot the server attached to an error, if any.
 */
class CallAttemptException(
    val statusCode: Int,
    val errorKey: String? = null,
    val retryAfterSeconds: Long? = null,
    val attempt: JsonObject? = null,
    val isOcs: Boolean = true,
    cause: Throwable? = null
) : Exception("Call attempt request failed with status $statusCode ${errorKey.orEmpty()}".trim(), cause) {

    val isRecoverable: Boolean
        get() = isOcs && (statusCode == 0 || statusCode == HTTP_TOO_MANY_REQUESTS || statusCode >= HTTP_SERVER_ERROR)

    companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        const val ANSWERED_ELSEWHERE = "answered_elsewhere"
        const val UNAVAILABLE = "unavailable"

        fun invalidResponse(cause: Throwable? = null) = CallAttemptException(0, isOcs = false, cause = cause)

        fun unavailable() = CallAttemptException(0, errorKey = UNAVAILABLE, isOcs = false)
    }
}
