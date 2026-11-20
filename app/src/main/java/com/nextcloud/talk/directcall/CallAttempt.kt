/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Server snapshot of a 1-1 call attempt (catalan-call-attempts-v1). Instances only exist after strict validation,
 * see [parse].
 */
data class CallAttempt(
    val callId: String,
    val sequence: Long,
    val origin: String,
    val version: Long,
    val state: State,
    val outcome: Outcome?,
    val endReason: String?,
    val mediaType: MediaType,
    val callerCloudId: String,
    val calleeCloudId: String,
    val ringingStartedAt: Instant,
    val ringingDeadline: Instant,
    val answeredAt: Instant?,
    val connectedAt: Instant?,
    val endedAt: Instant?,
    val deadline: Instant?,
    val serverTime: Instant,
    val durationSeconds: Long?,
    val chatMessageId: Long?
) {
    /** Declared in lifecycle order, so a later state compares greater. */
    enum class State(val wireValue: String) {
        RINGING("ringing"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        ENDED("ended")
    }

    enum class Outcome(val wireValue: String) {
        NO_ANSWER("no_answer"),
        REJECTED("rejected"),
        CANCELLED("cancelled"),
        COMPLETED("completed"),
        FAILED("failed")
    }

    enum class MediaType(val wireValue: String) {
        AUDIO("audio"),
        VIDEO("video")
    }

    val isTerminal: Boolean
        get() = state == State.ENDED

    /**
     * Whether this snapshot replaces [previous]: a newer attempt of the same origin, or a newer revision of the same
     * attempt that never moves backwards and never changes a final outcome.
     */
    fun supersedes(previous: CallAttempt): Boolean {
        if (origin != previous.origin) {
            return false
        }
        return when {
            callId != previous.callId -> sequence > previous.sequence
            sequence != previous.sequence || version <= previous.version -> false
            previous.state == State.ENDED -> state == State.ENDED && outcome == previous.outcome
            else -> state >= previous.state
        }
    }

    /**
     * Time left until the server deadline of the current phase, measured against the server clock of this snapshot.
     */
    val remainingTime: Duration?
        get() = when (state) {
            State.RINGING -> maxOf(Duration.between(serverTime, ringingDeadline), Duration.ZERO)
            State.CONNECTING -> (deadline ?: answeredAt?.plus(DEFAULT_CONNECTING_TIMEOUT))
                ?.let { maxOf(Duration.between(serverTime, it), Duration.ZERO) }
            State.CONNECTED, State.ENDED -> null
        }

    class InvalidCallAttemptException(message: String) : IllegalArgumentException(message)

    companion object {
        private val TAG = CallAttempt::class.java.simpleName
        private val DEFAULT_CONNECTING_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val TIMESTAMP_REGEX =
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$")

        fun isCallId(value: String?): Boolean = value != null && UUID_REGEX.matches(value)

        /**
         * Parses the raw callAttempt JSON of a chat message or API response. Returns null, and logs, when the value is
         * missing or invalid; an invalid snapshot must never be rendered or acted upon.
         */
        fun parse(json: String?): CallAttempt? {
            if (json.isNullOrBlank()) {
                return null
            }
            return try {
                val element = Json.parseToJsonElement(json)
                fromJson(element as? JsonObject ?: invalid("callAttempt is not an object"))
            } catch (e: SerializationException) {
                Log.w(TAG, "Invalid callAttempt JSON", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Invalid callAttempt", e)
                null
            }
        }

        /**
         * Strictly validates [json]. Unknown keys are ignored, unknown enum values and wrongly typed fields are
         * rejected.
         */
        fun fromJson(json: JsonObject): CallAttempt {
            val attempt = CallAttempt(
                callId = json.requiredString("callId"),
                sequence = json.requiredLong("sequence"),
                origin = json.requiredString("origin"),
                version = json.requiredLong("version"),
                state = json.requiredEnum("state", State.entries) { it.wireValue },
                outcome = json.optionalEnum("outcome", Outcome.entries) { it.wireValue },
                endReason = json.optionalString("endReason"),
                mediaType = json.requiredEnum("mediaType", MediaType.entries) { it.wireValue },
                callerCloudId = json.requiredParticipant("caller"),
                calleeCloudId = json.requiredParticipant("callee"),
                ringingStartedAt = json.requiredInstant("ringingStartedAt"),
                ringingDeadline = json.requiredInstant("ringingDeadline"),
                answeredAt = json.optionalInstant("answeredAt"),
                connectedAt = json.optionalInstant("connectedAt"),
                endedAt = json.optionalInstant("endedAt"),
                deadline = json.optionalInstant("deadline"),
                serverTime = json.requiredInstant("serverTime"),
                durationSeconds = json.optionalLong("durationSeconds"),
                chatMessageId = json.optionalLong("chatMessageId")
            )
            validate(attempt)
            return attempt
        }

        private fun validate(attempt: CallAttempt) {
            when {
                !isCallId(attempt.callId) -> invalid("callId is not a UUID")
                !isHttpsOrigin(attempt.origin) -> invalid("origin is not an https URL")
                attempt.version <= 0 || attempt.sequence <= 0 -> invalid("version and sequence must be positive")
                (attempt.durationSeconds ?: 0) < 0 -> invalid("durationSeconds must not be negative")
                (attempt.state == State.ENDED) != (attempt.outcome != null) -> invalid("outcome does not match state")
            }
        }

        private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeUnless { it is JsonNull }

        private fun JsonObject.requiredString(key: String): String = optionalString(key) ?: invalid("$key is missing")

        private fun JsonObject.optionalString(key: String): String? {
            val value = present(key) ?: return null
            return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$key is not a string")
        }

        private fun JsonObject.requiredLong(key: String): Long = optionalLong(key) ?: invalid("$key is missing")

        private fun JsonObject.optionalLong(key: String): Long? {
            val value = present(key) ?: return null
            return (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: invalid("$key is not an integer")
        }

        private fun <T> JsonObject.requiredEnum(key: String, values: List<T>, wireValue: (T) -> String): T =
            optionalEnum(key, values, wireValue) ?: invalid("$key is missing")

        private fun <T> JsonObject.optionalEnum(key: String, values: List<T>, wireValue: (T) -> String): T? {
            val value = optionalString(key) ?: return null
            return values.firstOrNull { wireValue(it) == value } ?: invalid("unknown $key")
        }

        private fun JsonObject.requiredParticipant(key: String): String {
            val participant = present(key) as? JsonObject ?: invalid("$key is not an object")
            return participant.requiredString("cloudId")
        }

        private fun JsonObject.requiredInstant(key: String): Instant =
            optionalInstant(key) ?: invalid("$key is missing")

        private fun JsonObject.optionalInstant(key: String): Instant? {
            val value = optionalString(key) ?: return null
            if (!TIMESTAMP_REGEX.matches(value)) invalid("$key is not an RFC 3339 timestamp")
            return try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: DateTimeParseException) {
                invalid("$key is not an RFC 3339 timestamp")
            }
        }
    }
}

private fun invalid(reason: String): Nothing = throw CallAttempt.InvalidCallAttemptException(reason)

private fun isHttpsOrigin(origin: String): Boolean =
    try {
        val uri = URI(origin)
        uri.scheme == "https" && !uri.host.isNullOrEmpty()
    } catch (_: URISyntaxException) {
        false
    }
