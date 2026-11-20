/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

object CallAttemptFixtures {
    const val CALL_ID = "6f1c2a8e-4b7d-4e2a-9c3f-1a2b3c4d5e6f"
    const val OTHER_CALL_ID = "0d9e8f7a-6b5c-4d3e-8f2a-9b8c7d6e5f4a"
    val T0: Instant = Instant.parse("2026-11-09T08:30:00Z")
    private const val ANSWERED_SECONDS = 19L
    private const val SERVER_TIME_SECONDS = 20L
    private const val DEADLINE_SECONDS = 80L

    @Suppress("LongParameterList")
    fun attemptJson(
        state: CallAttempt.State = CallAttempt.State.RINGING,
        version: Long = 1,
        sequence: Long = 7,
        outcome: CallAttempt.Outcome? = null,
        callId: String = CALL_ID,
        serverTime: Instant = T0.plusSeconds(SERVER_TIME_SECONDS),
        ringingDeadline: Instant = T0.plusSeconds(DEADLINE_SECONDS)
    ): JsonObject {
        val fields = mutableMapOf<String, JsonElement>(
            "callId" to JsonPrimitive(callId),
            "origin" to JsonPrimitive("https://cloud.catalan.vn"),
            "sequence" to JsonPrimitive(sequence),
            "version" to JsonPrimitive(version),
            "state" to JsonPrimitive(state.wireValue),
            "mediaType" to JsonPrimitive("video"),
            "caller" to participant("alice@cloud.catalan.vn"),
            "callee" to participant("bob@cloud.catalan.vn"),
            "ringingStartedAt" to JsonPrimitive(T0.toString()),
            "ringingDeadline" to JsonPrimitive(ringingDeadline.toString()),
            "serverTime" to JsonPrimitive(serverTime.toString())
        )
        outcome?.let { fields["outcome"] = JsonPrimitive(it.wireValue) }
        if (state == CallAttempt.State.CONNECTING || state == CallAttempt.State.CONNECTED) {
            fields["answeredAt"] = JsonPrimitive(T0.plusSeconds(ANSWERED_SECONDS).toString())
            fields["deadline"] = JsonPrimitive(T0.plusSeconds(DEADLINE_SECONDS).toString())
        }
        if (state == CallAttempt.State.CONNECTED) {
            fields["connectedAt"] = JsonPrimitive(T0.plusSeconds(SERVER_TIME_SECONDS).toString())
        }
        return JsonObject(fields)
    }

    @Suppress("LongParameterList")
    fun attempt(
        state: CallAttempt.State = CallAttempt.State.RINGING,
        version: Long = 1,
        sequence: Long = 7,
        outcome: CallAttempt.Outcome? = null,
        callId: String = CALL_ID,
        serverTime: Instant = T0.plusSeconds(SERVER_TIME_SECONDS),
        ringingDeadline: Instant = T0.plusSeconds(DEADLINE_SECONDS)
    ): CallAttempt =
        CallAttempt.fromJson(attemptJson(state, version, sequence, outcome, callId, serverTime, ringingDeadline))

    fun ended(outcome: CallAttempt.Outcome, version: Long = 3, callId: String = CALL_ID): CallAttempt =
        attempt(CallAttempt.State.ENDED, version = version, outcome = outcome, callId = callId)

    fun ocs(data: JsonElement): String = JsonObject(mapOf("ocs" to JsonObject(mapOf("data" to data)))).toString()

    private fun participant(cloudId: String) = JsonObject(mapOf("cloudId" to JsonPrimitive(cloudId)))
}
