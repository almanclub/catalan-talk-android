/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Unwraps the OCS responses of the call-attempt API.
 */
object CallAttemptResponses {
    const val ACTIVE = "/active"
    const val CANCEL_REQUEST = "/cancel-request"

    private const val HTTP_OK = 200
    private const val HTTP_MULTIPLE_CHOICES = 300

    /**
     * Returns the snapshot of the response to [path], or null when the server confirmed that there is none.
     *
     * @throws CallAttemptException for error statuses and for responses that are not a valid answer
     */
    fun parse(path: String, statusCode: Int, retryAfter: String?, body: String?): CallAttempt? {
        val data = ocsData(body)
        if (statusCode !in HTTP_OK until HTTP_MULTIPLE_CHOICES) {
            throw CallAttemptException(
                statusCode = statusCode,
                errorKey = data?.stringOrNull("error"),
                retryAfterSeconds = retryAfter?.trim()?.toLongOrNull(),
                attempt = data?.get("callAttempt") as? JsonObject
            )
        }
        return try {
            unwrap(path, data)
        } catch (e: IllegalArgumentException) {
            throw CallAttemptException.invalidResponse(e)
        }
    }

    private fun unwrap(path: String, data: JsonObject?): CallAttempt? {
        val attempt = data?.get("callAttempt")
        return when {
            data == null -> throw CallAttempt.InvalidCallAttemptException("missing ocs data")
            path != ACTIVE && path != CANCEL_REQUEST -> CallAttempt.fromJson(data)
            attempt is JsonObject -> CallAttempt.fromJson(attempt)
            attempt is JsonNull && (path == ACTIVE || data.booleanOrNull("cancelled") == true) -> null
            else -> throw CallAttempt.InvalidCallAttemptException("unexpected callAttempt in $path")
        }
    }

    private fun ocsData(body: String?): JsonObject? =
        try {
            body?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?.let { it["ocs"] as? JsonObject }
                ?.let { it["data"] as? JsonObject }
        } catch (_: SerializationException) {
            null
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
