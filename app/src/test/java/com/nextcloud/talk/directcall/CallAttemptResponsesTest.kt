/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import com.nextcloud.talk.directcall.CallAttemptFixtures.CALL_ID
import com.nextcloud.talk.directcall.CallAttemptFixtures.attemptJson
import com.nextcloud.talk.directcall.CallAttemptFixtures.ocs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAttemptResponsesTest {

    private fun parse(path: String, data: Map<String, Any?>, status: Int = 200, retryAfter: String? = null) =
        CallAttemptResponses.parse(path, status, retryAfter, ocs(JsonObject(data.mapValues { element(it.value) })))

    private fun element(value: Any?) =
        when (value) {
            null -> JsonNull
            is JsonObject -> value
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    private fun failure(block: () -> Unit): CallAttemptException = assertThrows(CallAttemptException::class.java, block)

    @Test
    fun activeWithoutAttemptMeansNoAttempt() {
        assertNull(parse(CallAttemptResponses.ACTIVE, mapOf("callAttempt" to null)))
        assertEquals(CALL_ID, parse(CallAttemptResponses.ACTIVE, mapOf("callAttempt" to attemptJson()))?.callId)
    }

    @Test
    fun cancelRequestWithoutAttemptNeedsConfirmedCancellation() {
        val path = CallAttemptResponses.CANCEL_REQUEST
        assertNull(parse(path, mapOf("cancelled" to true, "callAttempt" to null)))
        assertEquals(CALL_ID, parse(path, mapOf("cancelled" to true, "callAttempt" to attemptJson()))?.callId)

        assertFalse(failure { parse(path, mapOf("cancelled" to false, "callAttempt" to null)) }.isOcs)
        assertFalse(failure { parse(path, mapOf("cancelled" to "true", "callAttempt" to null)) }.isOcs)
        assertFalse(failure { parse(path, mapOf("cancelled" to true)) }.isOcs)
    }

    @Test
    fun errorKeepsStatusKeyRetryAfterAndAttachedAttempt() {
        val conflict = failure {
            parse("/$CALL_ID/accept", mapOf("error" to "not_ringing", "callAttempt" to attemptJson()), status = 409)
        }
        assertEquals(409, conflict.statusCode)
        assertEquals("not_ringing", conflict.errorKey)
        assertNotNull(conflict.attempt)
        assertTrue(conflict.isOcs)
        assertFalse(conflict.isRecoverable)

        val limited = failure { parse("", mapOf("error" to "rate_limited"), status = 429, retryAfter = " 7 ") }
        assertEquals(7L, limited.retryAfterSeconds)
        assertTrue(limited.isRecoverable)
    }

    @Test
    fun errorPageWithoutOcsBodyIsStillRecoverableServerError() {
        val error = failure { CallAttemptResponses.parse("/active", 502, "soon", "<html>Bad gateway</html>") }

        assertEquals(502, error.statusCode)
        assertNull(error.errorKey)
        assertNull(error.retryAfterSeconds)
        assertTrue(error.isRecoverable)
    }

    @Test
    fun invalidSuccessIsNeverAnAttemptOrAbsence() {
        assertFalse(failure { CallAttemptResponses.parse("/$CALL_ID", 200, null, "{\"ocs\":{}}") }.isOcs)
        assertFalse(failure { CallAttemptResponses.parse("/active", 200, null, "not json") }.isOcs)
        assertFalse(failure { parse(CallAttemptResponses.ACTIVE, emptyMap()) }.isOcs)
        assertFalse(failure { parse(CallAttemptResponses.ACTIVE, mapOf("callAttempt" to "x")) }.isOcs)

        val invalid = JsonObject(attemptJson() + ("callId" to JsonPrimitive("not-a-uuid")))
        val error = failure { CallAttemptResponses.parse("/$CALL_ID", 200, null, ocs(invalid)) }
        assertFalse(error.isOcs)
        assertFalse(error.isRecoverable)
    }

    @Test
    fun actionsReturnTheSnapshotDirectly() {
        val attempt = CallAttemptResponses.parse("/$CALL_ID/accept", 200, null, ocs(attemptJson()))

        assertEquals(CALL_ID, attempt?.callId)
        assertEquals(CallAttempt.State.RINGING, attempt?.state)
    }
}
