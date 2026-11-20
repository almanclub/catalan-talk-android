/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import com.nextcloud.talk.directcall.CallAttempt.MediaType
import com.nextcloud.talk.directcall.CallAttempt.Outcome
import com.nextcloud.talk.directcall.CallAttempt.State
import com.nextcloud.talk.directcall.CallAttemptFixtures.CALL_ID
import com.nextcloud.talk.directcall.CallAttemptFixtures.OTHER_CALL_ID
import com.nextcloud.talk.directcall.CallAttemptFixtures.T0
import com.nextcloud.talk.directcall.CallAttemptFixtures.attempt
import com.nextcloud.talk.directcall.CallAttemptFixtures.attemptJson
import com.nextcloud.talk.directcall.CallAttemptFixtures.ended
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class DirectCallSessionTest {

    private fun TestScope.host() = FakeSessionHost(backgroundScope) { testScheduler.currentTime }

    private fun session(host: FakeSessionHost, initiator: Boolean, callId: String? = null) =
        DirectCallSession(1, "room", initiator, callId, "request-1", host)

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content

    private fun TestScope.elapse(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun createIsSentOnceAndSucceedsWithItsOwnAttempt() =
        runTest {
            val host = host()
            host.handler = { path -> if (path.isEmpty()) attempt() else attempt(serverTime = T0.plusSeconds(21)) }
            val session = session(host, initiator = true)
            var result: Result<Unit>? = null

            session.prepare("s1", MediaType.VIDEO, false) { result = it }
            runCurrent()
            elapse(POLL)

            assertTrue(result!!.isSuccess)
            assertEquals(CALL_ID, session.callId)
            assertEquals(listOf("", "/$CALL_ID"), host.paths())
            val body = host.requests.first().second!!
            assertEquals("request-1", body.text("clientRequestId"))
            assertEquals("video", body.text("mediaType"))
            assertEquals("s1", body.text("sessionId"))
            assertEquals("false", body.text("recordingConsent"))
        }

    @Test
    fun ambiguousCreateIsRetriedWithItsRequestIdInsteadOfAdoptingActiveAttempt() =
        runTest {
            val host = host()
            var creates = 0
            host.handler = { path ->
                when {
                    path == CallAttemptResponses.ACTIVE -> attempt(callId = OTHER_CALL_ID)
                    creates++ == 0 -> throw CallAttemptException(503, "signaling_delivery_failed")
                    else -> attempt()
                }
            }
            val session = session(host, initiator = true)
            var result: Result<Unit>? = null

            session.prepare("s1", MediaType.AUDIO, true) { result = it }
            runCurrent()
            assertNull(result)
            elapse(POLL)

            assertEquals(listOf("", CallAttemptResponses.ACTIVE, ""), host.paths())
            assertEquals(host.requests[0].second, host.requests[2].second)
            assertEquals(CALL_ID, session.callId)
            assertTrue(result!!.isSuccess)
        }

    @Test
    fun endingBeforeCreateAnswerCancelsTheRequestAndThenTheLateAttempt() =
        runTest {
            val host = host()
            val created = CompletableDeferred<CallAttempt>()
            host.handler = { path -> if (path.isEmpty()) created.await() else null }
            val session = session(host, initiator = true)
            var result: Result<Unit>? = null
            session.prepare("s1", MediaType.VIDEO, false) { result = it }
            runCurrent()

            session.finishByUser()

            assertTrue(result!!.isFailure)
            assertEquals(CallAttemptResponses.CANCEL_REQUEST, host.enqueued.single().first)
            assertEquals("request-1", host.enqueued.single().second.text("clientRequestId"))

            created.complete(attempt())
            runCurrent()

            val paths = host.enqueued.map { it.first }
            assertEquals(listOf(CallAttemptResponses.CANCEL_REQUEST, "/$CALL_ID/cancel"), paths)
            assertEquals("s1", host.enqueued.last().second.text("sessionId"))
        }

    @Test
    fun calleeAcceptsOnlyAnAttemptThatIsRinging() =
        runTest {
            val host = host()
            host.handler = { path ->
                if (path.endsWith("/accept")) attempt(State.CONNECTING, version = 2) else attempt()
            }
            val session = session(host, initiator = false, callId = CALL_ID)
            var result: Result<Unit>? = null

            session.prepare("s2", MediaType.AUDIO, true) { result = it }
            runCurrent()

            assertTrue(result!!.isSuccess)
            assertEquals(listOf("/$CALL_ID", "/$CALL_ID/accept"), host.paths())
            assertEquals("s2", host.requests[1].second!!.text("sessionId"))
            assertEquals("true", host.requests[1].second!!.text("recordingConsent"))
            assertTrue(session.acceptedLocally)
            assertFalse(session.accepting)
        }

    @Test
    fun calleeDoesNotAcceptAnEndedAttempt() =
        runTest {
            val host = host()
            host.handler = { ended(Outcome.NO_ANSWER) }
            val session = session(host, initiator = false, callId = CALL_ID)
            var result: Result<Unit>? = null

            session.prepare("s2", MediaType.AUDIO, false) { result = it }
            runCurrent()

            assertTrue(result!!.isFailure)
            assertEquals(listOf("/$CALL_ID"), host.paths())
            assertTrue(session.isTerminal)
        }

    @Test
    fun answeredElsewhereStopsLocallyWithoutRejecting() =
        runTest {
            val host = host()
            host.handler = { path ->
                if (path.endsWith("/accept")) {
                    throw CallAttemptException(
                        409,
                        CallAttemptException.ANSWERED_ELSEWHERE,
                        attempt = attemptJson(State.CONNECTING, version = 2)
                    )
                }
                attempt()
            }
            val session = session(host, initiator = false, callId = CALL_ID)
            var result: Result<Unit>? = null

            session.prepare("s2", MediaType.VIDEO, false) { result = it }
            runCurrent()
            session.finishByUser()

            assertTrue(result!!.isFailure)
            assertTrue(session.stoppedLocally)
            assertEquals(State.CONNECTING, session.snapshot?.state)
            assertTrue(host.enqueued.isEmpty())
        }

    @Test
    fun mediaReadyIsReportedOnceAndOnlyWhileConnecting() =
        runTest {
            val host = host()
            var state = State.RINGING
            host.handler = { path ->
                when {
                    path.endsWith("/media-ready") -> attempt(State.CONNECTING, version = 3)
                    state == State.CONNECTING -> attempt(State.CONNECTING, version = 2)
                    else -> attempt()
                }
            }
            val session = session(host, initiator = true)
            session.prepare("s1", MediaType.VIDEO, false) { }
            runCurrent()

            session.mediaReady()
            runCurrent()
            assertFalse(host.paths().any { it.endsWith("/media-ready") })

            state = State.CONNECTING
            elapse(POLL)
            elapse(POLL)
            session.mediaReady()
            elapse(POLL)

            assertEquals(1, host.paths().count { it == "/$CALL_ID/media-ready" })
            assertEquals("s1", host.requests.first { it.first.endsWith("/media-ready") }.second!!.text("sessionId"))
            assertEquals(3L, session.snapshot?.version)
        }

    @Test
    fun localDeadlineOnlyStopsWaitingAndNeverSendsAnAction() =
        runTest {
            val host = host()
            host.handler = {
                val now = T0.plusSeconds(20).plusMillis(testScheduler.currentTime)
                attempt(serverTime = now, ringingDeadline = T0.plusSeconds(30))
            }
            val session = session(host, initiator = true)
            session.prepare("s1", MediaType.VIDEO, false) { }
            runCurrent()

            elapse(DEADLINE - 1)
            assertFalse(session.stoppedLocally)
            elapse(2)

            assertTrue(session.stoppedLocally)
            assertTrue(host.enqueued.isEmpty())
            assertFalse(host.paths().any { it.endsWith("/cancel") || it.endsWith("/end") })
        }

    @Test
    fun finishingRejectsWhileRingingAndEndsAnAnsweredCallOnce() =
        runTest {
            val host = host()
            val callee = session(host, initiator = false, callId = CALL_ID)
            callee.apply(attempt())
            callee.finishByUser()

            val caller = DirectCallSession(1, "room", true, null, "request-2", host)
            caller.apply(attempt(State.CONNECTING, version = 2))
            caller.finishByUser()
            caller.finishByUser()

            assertEquals(listOf("/$CALL_ID/reject", "/$CALL_ID/end"), host.enqueued.map { it.first })
            assertTrue(host.enqueued.first().second.isEmpty())
        }

    @Test
    fun cancelLosingAgainstAcceptEndsTheAnsweredCall() =
        runTest {
            val host = host()
            val caller = session(host, initiator = true)
            caller.apply(attempt())
            caller.finishByUser()

            val connecting = attemptJson(State.CONNECTING, version = 2)
            caller.handle(CallAttemptException(409, "not_ringing", attempt = connecting))
            caller.finishAfterCancelRace()

            assertEquals(listOf("/$CALL_ID/cancel", "/$CALL_ID/end"), host.enqueued.map { it.first })
        }

    @Test
    fun retryAfterAndBackoffDelayReconciliation() =
        runTest {
            val host = host()
            host.handler = { throw CallAttemptException(429, retryAfterSeconds = 7) }
            val session = session(host, initiator = false, callId = CALL_ID)

            session.reconcile()
            runCurrent()
            session.reconcile()
            elapse(6000)
            assertEquals(1, host.requests.size)

            elapse(POLL)
            assertEquals(2, host.requests.size)
        }

    @Test
    fun olderRevisionsAndOtherAttemptsAreIgnored() =
        runTest {
            val session = session(host(), initiator = false, callId = CALL_ID)
            session.apply(attempt(State.CONNECTING, version = 2))

            session.apply(attempt(version = 1))
            session.apply(attempt(State.CONNECTED, version = 3, callId = OTHER_CALL_ID))
            assertEquals(2L, session.snapshot?.version)
            assertEquals(State.CONNECTING, session.snapshot?.state)

            val refreshed = attempt(State.CONNECTING, version = 2, serverTime = T0.plusSeconds(25))
            session.apply(refreshed)
            assertEquals(refreshed, session.snapshot)
        }

    @Test
    fun unresolvedAttemptStopsLocallyAfterResolutionTimeout() =
        runTest {
            val host = host()
            val never = CompletableDeferred<CallAttempt?>()
            host.handler = { never.await() }
            val session = session(host, initiator = false, callId = CALL_ID)

            session.reconcile()
            elapse(RESOLUTION - 1)
            assertFalse(session.stoppedLocally)
            elapse(2)

            assertTrue(session.stoppedLocally)
            assertTrue(host.enqueued.isEmpty())
        }

    @Test
    fun recoveredSessionEndsWhenTheServerHasNoAttempt() =
        runTest {
            val host = host()
            host.handler = { null }
            val session = session(host, initiator = true)

            session.restoreForReconciliation()
            runCurrent()

            assertEquals(listOf(CallAttemptResponses.ACTIVE), host.paths())
            assertTrue(session.isTerminal)
            assertTrue(host.remembered > 0)
        }

    @Test
    fun missingAttemptEndsTheSessionAndStopsPolling() =
        runTest {
            val host = host()
            host.handler = { throw CallAttemptException(404, "call_not_found") }
            val session = session(host, initiator = false, callId = CALL_ID)

            session.reconcile()
            runCurrent()
            elapse(POLL * 3)

            assertTrue(session.isTerminal)
            assertEquals(1, host.requests.size)
            assertTrue(host.enqueued.isEmpty())
        }

    companion object {
        private const val POLL = 2000L
        private const val DEADLINE = 10000L
        private const val RESOLUTION = 10000L
    }
}
