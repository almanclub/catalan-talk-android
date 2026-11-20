/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CallAttemptTest {

    @Suppress("LongParameterList")
    private fun json(
        state: String = "ringing",
        version: Long = 1,
        sequence: Long = 7,
        outcome: String? = null,
        callId: String = CALL_ID,
        serverTime: String = "2026-11-09T08:30:20Z",
        changes: Map<String, JsonElement> = emptyMap()
    ): String {
        val fields = mutableMapOf<String, JsonElement>(
            "callId" to JsonPrimitive(callId),
            "origin" to JsonPrimitive("https://cloud.catalan.vn"),
            "sequence" to JsonPrimitive(sequence),
            "version" to JsonPrimitive(version),
            "state" to JsonPrimitive(state),
            "mediaType" to JsonPrimitive("video"),
            "caller" to participant("alice@cloud.catalan.vn"),
            "callee" to participant("alice@cloud.danco.vn"),
            "ringingStartedAt" to JsonPrimitive("2026-11-09T08:30:00Z"),
            "ringingDeadline" to JsonPrimitive("2026-11-09T08:30:30Z"),
            "serverTime" to JsonPrimitive(serverTime)
        )
        outcome?.let { fields["outcome"] = JsonPrimitive(it) }
        if (state == "connecting" || state == "connected") {
            fields["answeredAt"] = JsonPrimitive("2026-11-09T08:30:19Z")
        }
        if (state == "connected") {
            fields["connectedAt"] = JsonPrimitive("2026-11-09T08:30:20Z")
        }
        fields.putAll(changes)
        return JsonObject(fields).toString()
    }

    private fun participant(cloudId: String) = JsonObject(mapOf("cloudId" to JsonPrimitive(cloudId)))

    @Suppress("LongParameterList")
    private fun attempt(
        state: String = "ringing",
        version: Long = 1,
        sequence: Long = 7,
        outcome: String? = null,
        callId: String = CALL_ID,
        serverTime: String = "2026-11-09T08:30:20Z"
    ): CallAttempt = requireNotNull(CallAttempt.parse(json(state, version, sequence, outcome, callId, serverTime)))

    @Test
    fun latePushHasOnlyRemainingServerTime() {
        assertEquals(Duration.ofSeconds(10), attempt().remainingTime)
        assertEquals(Duration.ZERO, attempt(serverTime = "2026-11-09T08:30:35Z").remainingTime)
    }

    @Test
    fun connectingHasSeparateTenSecondDeadline() {
        assertEquals(Duration.ofSeconds(9), attempt(state = "connecting").remainingTime)
        assertNull(attempt(state = "connected").remainingTime)
    }

    @Test
    fun connectingUsesServerDeadlineWhenPresent() {
        val snapshot = CallAttempt.parse(
            json(state = "connecting", changes = mapOf("deadline" to JsonPrimitive("2026-11-09T08:30:25Z")))
        )

        assertEquals(Duration.ofSeconds(5), snapshot?.remainingTime)
    }

    @Test
    fun versionsSequenceAndTerminalStateNeverRegress() {
        val ringing = attempt()
        val connecting = attempt(state = "connecting", version = 2)
        val ended = attempt(state = "ended", version = 3, outcome = "cancelled")

        assertTrue(connecting.supersedes(ringing))
        assertFalse(ringing.supersedes(connecting))
        assertFalse(connecting.supersedes(connecting))
        assertFalse(attempt(version = 3).supersedes(connecting))
        assertFalse(attempt(version = 4).supersedes(ended))
        assertFalse(attempt(state = "ended", version = 4, outcome = "no_answer").supersedes(ended))
        assertTrue(attempt(state = "ended", version = 4, outcome = "cancelled").supersedes(ended))
        assertFalse(attempt(sequence = 6, callId = OTHER_CALL_ID).supersedes(ringing))
        assertTrue(attempt(sequence = 8, callId = OTHER_CALL_ID).supersedes(ringing))
    }

    @Test
    fun attemptsOfAnotherOriginNeverSupersede() {
        val otherOrigin = CallAttempt.parse(
            json(sequence = 8, callId = OTHER_CALL_ID, changes = mapOf("origin" to JsonPrimitive("https://x.example")))
        )

        assertFalse(requireNotNull(otherOrigin).supersedes(attempt()))
    }

    @Test
    fun invalidSnapshotDoesNotBecomeSuccess() {
        assertNull(CallAttempt.parse(json(state = "ended")))
        assertNull(CallAttempt.parse(json(outcome = "cancelled")))
        assertNull(CallAttempt.parse(json(state = "ended", outcome = "missed")))
        assertNull(CallAttempt.parse(json(callId = "not-an-id")))
    }

    @Test
    fun wronglyTypedOrMissingFieldsAreRejected() {
        val invalidChanges = listOf(
            "sequence" to JsonPrimitive("7"),
            "version" to JsonPrimitive(0),
            "version" to JsonPrimitive(1.5),
            "mediaType" to JsonPrimitive("screen"),
            "origin" to JsonPrimitive("http://cloud.catalan.vn"),
            "caller" to JsonPrimitive("alice@cloud.catalan.vn"),
            "callee" to JsonObject(emptyMap()),
            "ringingDeadline" to JsonPrimitive("2026-11-09 08:30:30"),
            "serverTime" to JsonNull,
            "durationSeconds" to JsonPrimitive(-1),
            "endReason" to JsonArray(emptyList())
        )

        invalidChanges.forEach { change ->
            assertNull("$change must be rejected", CallAttempt.parse(json(changes = mapOf(change))))
        }
    }

    @Test
    fun nonObjectAndMalformedJsonAreIgnored() {
        listOf(null, "", "[]", "\"text\"", "{").forEach { value ->
            assertNull(CallAttempt.parse(value))
        }
    }

    @Test
    fun parsesEndedSnapshotWithOffsetTimestampsAndUnknownKeys() {
        val snapshot = CallAttempt.parse(
            json(
                state = "ended",
                version = 4,
                outcome = "completed",
                changes = mapOf(
                    "endReason" to JsonPrimitive("normal_hangup"),
                    "connectedAt" to JsonPrimitive("2026-11-09T15:30:20.500+07:00"),
                    "durationSeconds" to JsonPrimitive(65),
                    "chatMessageId" to JsonPrimitive(10),
                    "futureField" to JsonPrimitive(true)
                )
            )
        )

        requireNotNull(snapshot)
        assertTrue(snapshot.isTerminal)
        assertEquals(CallAttempt.Outcome.COMPLETED, snapshot.outcome)
        assertEquals(CallAttempt.MediaType.VIDEO, snapshot.mediaType)
        assertEquals("alice@cloud.catalan.vn", snapshot.callerCloudId)
        assertEquals("alice@cloud.danco.vn", snapshot.calleeCloudId)
        assertEquals(Instant.parse("2026-11-09T08:30:20.500Z"), snapshot.connectedAt)
        assertEquals(65L, snapshot.durationSeconds)
        assertEquals(10L, snapshot.chatMessageId)
        assertNull(snapshot.remainingTime)
    }

    companion object {
        private const val CALL_ID = "87654321-4321-4321-8321-cba987654321"
        private const val OTHER_CALL_ID = "12345678-1234-4234-8234-123456789abc"
    }
}
