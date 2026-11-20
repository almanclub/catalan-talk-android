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
import com.nextcloud.talk.directcall.CallAttemptFixtures.attempt
import com.nextcloud.talk.directcall.CallAttemptFixtures.attemptJson
import com.nextcloud.talk.directcall.CallAttemptFixtures.ended
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class DirectCallManagerTest {

    private class FakeBackend : DirectCallBackend {
        val accounts = mutableSetOf(1L)
        val requests = mutableListOf<Pair<String, JsonObject?>>()
        var joins = 0
        var handler: suspend (String) -> CallAttempt? = { null }

        override suspend fun hasAccount(accountId: Long): Boolean = accountId in accounts

        override suspend fun request(accountId: Long, token: String, path: String, body: JsonObject?): CallAttempt? {
            requests += path to body
            return handler(path)
        }

        override suspend fun joinRoom(accountId: Long, token: String): String {
            joins++
            return "fresh"
        }

        fun paths() = requests.map { it.first }

        fun body(path: String) = requests.last { it.first == path }.second
    }

    private val values = mutableMapOf<String, String>()
    private val store = DirectCallStore({ values[it] }, { key, value -> values[key] = value })

    private fun TestScope.manager(backend: FakeBackend) =
        DirectCallManager(backend, store, backgroundScope) { testScheduler.currentTime }

    private fun TestScope.elapse(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.content

    @Test
    fun unfinishedCallsAreReconciledAfterRestartUntilTheServerEndsThem() =
        runTest {
            store.putUnfinishedCall(UnfinishedCall(1, "room", true, "request-1", CALL_ID))
            store.putUnfinishedCall(UnfinishedCall(2, "gone", false, "request-2", OTHER_CALL_ID))
            val backend = FakeBackend().apply { handler = { attempt(State.CONNECTED, version = 3) } }
            val manager = manager(backend)
            runCurrent()

            assertTrue(manager.hasUnresolvedCall(1, "room"))
            assertEquals(listOf("/$CALL_ID"), backend.paths())
            assertEquals(setOf("request-1"), store.unfinishedCalls().keys)

            backend.handler = { ended(Outcome.COMPLETED, version = 4) }
            elapse(POLL)

            assertFalse(manager.hasUnresolvedCall(1, "room"))
            assertTrue(store.unfinishedCalls().isEmpty())
        }

    @Test
    fun pendingActionsAreRetriedAfterRestartAndDroppedForRemovedAccounts() =
        runTest {
            val parameters = buildJsonObject { put("sessionId", "s1") }
            store.putPendingAction("end", PendingCallAction(1, "room", "/$CALL_ID/end", parameters))
            store.putPendingAction("gone", PendingCallAction(2, "room", "/$OTHER_CALL_ID/end", parameters))
            val backend = FakeBackend().apply { handler = { ended(Outcome.COMPLETED) } }
            manager(backend)

            elapse(RETRY)

            assertEquals(listOf("/$CALL_ID/end"), backend.paths())
            assertEquals("s1", backend.body("/$CALL_ID/end").text("sessionId"))
            assertTrue(store.pendingActions().isEmpty())
        }

    @Test
    fun rejectWithoutSessionJoinsTheRoomBeforeRejecting() =
        runTest {
            val backend = FakeBackend().apply {
                handler = { path -> if (path.endsWith("/reject")) ended(Outcome.REJECTED) else attempt() }
            }
            val manager = manager(backend)
            val session = manager.incoming(1, "room", CALL_ID)
            session.reconcile()
            runCurrent()

            session.finishByUser()
            runCurrent()

            assertEquals(1, backend.joins)
            assertEquals("fresh", backend.body("/$CALL_ID/reject").text("sessionId"))
            assertTrue(session.isTerminal)
            assertTrue(store.pendingActions().isEmpty())
        }

    @Test
    fun cancelThatLostAgainstAcceptEndsTheAnsweredCall() =
        runTest {
            val backend = FakeBackend().apply {
                handler = { path ->
                    when {
                        path.isEmpty() -> attempt()
                        path.endsWith("/cancel") ->
                            throw CallAttemptException(409, "not_ringing", attempt = attemptJson(State.CONNECTING, 2))
                        path.endsWith("/end") -> ended(Outcome.COMPLETED)
                        else -> attempt(State.CONNECTING, version = 2)
                    }
                }
            }
            val manager = manager(backend)
            val session = manager.attach(1, "room", initiator = true, enabled = true)!!
            session.prepare("s1", MediaType.VIDEO, false) { }
            runCurrent()

            session.finishByUser()
            runCurrent()

            assertEquals("s1", backend.body("/$CALL_ID/end").text("sessionId"))
            assertEquals(Outcome.COMPLETED, session.snapshot?.outcome)
            assertTrue(store.pendingActions().isEmpty())
            assertTrue(store.unfinishedCalls().isEmpty())
        }

    @Test
    fun actionsAreKeptOnNetworkFailureAndDroppedWhenTheServerRefusesThem() =
        runTest {
            var failure = CallAttemptException(0)
            val backend = FakeBackend().apply {
                handler = { path ->
                    if (path.endsWith("/end")) throw failure
                    attempt(State.CONNECTING, version = 2)
                }
            }
            val manager = manager(backend)
            val session = manager.attach(1, "room", initiator = true, enabled = true)!!
            session.apply(attempt(State.CONNECTING, version = 2))

            session.finishByUser()
            runCurrent()
            assertEquals(1, store.pendingActions().size)

            failure = CallAttemptException(404, "call_not_found")
            elapse(RETRY)

            assertEquals(2, backend.paths().count { it == "/$CALL_ID/end" })
            assertTrue(store.pendingActions().isEmpty())
        }

    @Test
    fun confirmedCancelRequestWithoutAttemptResolvesTheCall() =
        runTest {
            val created = CompletableDeferred<CallAttempt?>()
            val backend = FakeBackend().apply { handler = { path -> if (path.isEmpty()) created.await() else null } }
            val manager = manager(backend)
            val session = manager.attach(1, "room", initiator = true, enabled = true)!!
            session.prepare("s1", MediaType.AUDIO, false) { }
            runCurrent()
            assertTrue(manager.hasUnresolvedCall(1, "room"))

            session.finishByUser()
            runCurrent()

            val cancelBody = backend.body(CallAttemptResponses.CANCEL_REQUEST)
            assertEquals(session.clientRequestId, cancelBody.text("clientRequestId"))
            assertTrue(session.isTerminal)
            assertFalse(manager.hasUnresolvedCall(1, "room"))
        }

    @Test
    fun newerAttemptOfTheRoomSupersedesTheOlderOne() =
        runTest {
            val manager = manager(FakeBackend())
            val older = manager.incoming(1, "room", CALL_ID)
            val newer = manager.incoming(1, "room", OTHER_CALL_ID)

            older.apply(attempt())
            newer.apply(attempt(callId = OTHER_CALL_ID, sequence = 8))

            assertTrue(manager.isSuperseded(older))
            assertFalse(manager.isSuperseded(newer))
            assertSame(older, manager.incoming(1, "room", CALL_ID))
        }

    @Test
    fun attachReusesTheAcceptingSessionAndDetachReleasesUnusedSessions() =
        runTest {
            val manager = manager(FakeBackend())
            val accepting = manager.incoming(1, "room", CALL_ID).also { it.beginAccepting() }

            assertSame(accepting, manager.attach(1, "room", initiator = false, enabled = false))
            assertNull(manager.attach(1, "other", initiator = true, enabled = false))

            val fresh = manager.attach(1, "other", initiator = true, enabled = true)!!
            assertTrue(manager.hasUnresolvedCall(1, "other"))
            manager.detach(fresh)
            assertFalse(manager.hasUnresolvedCall(1, "other"))
            assertNull(manager.current(1, "other"))
        }

    @Test
    fun announcedCallRingsOnlyOnceTheServerConfirmedIt() =
        runTest {
            val backend = FakeBackend().apply { handler = { attempt() } }
            val manager = manager(backend)

            val session = manager.announceIncoming(1, "room", CALL_ID)!!

            assertEquals(listOf("/$CALL_ID"), backend.paths())
            assertTrue(manager.mayRing(session))
            assertNull(manager.announceIncoming(1, "room", CALL_ID))
            session.apply(attempt(State.CONNECTING, version = 2))
            assertFalse(manager.mayRing(session))
        }

    @Test
    fun announcedCallDoesNotRingWhenTheServerDoesNotAnswer() =
        runTest {
            val backend = FakeBackend().apply { handler = { CompletableDeferred<CallAttempt?>().await() } }
            val manager = manager(backend)

            val session = manager.announceIncoming(1, "room", CALL_ID)!!

            assertFalse(manager.mayRing(session))
        }

    @Test
    fun missedCallIsReportedOnceAndOnlyForAnUnansweredAnnouncedCall() =
        runTest {
            val manager = manager(FakeBackend())
            val missed = manager.announceIncoming(1, "room", CALL_ID)!!
            val rejected = manager.announceIncoming(1, "other", OTHER_CALL_ID)!!
            val unannounced = manager.incoming(1, "silent", CALL_ID)

            missed.apply(ended(Outcome.NO_ANSWER))
            rejected.apply(ended(Outcome.REJECTED, callId = OTHER_CALL_ID))
            unannounced.apply(ended(Outcome.NO_ANSWER))

            assertTrue(manager.takeMissedCall(missed))
            assertFalse(manager.takeMissedCall(missed))
            assertFalse(manager.takeMissedCall(rejected))
            assertFalse(manager.takeMissedCall(unannounced))
        }

    @Test
    fun watchingAnIncomingCallStopsOnceItNoLongerRings() =
        runTest {
            val manager = manager(FakeBackend())
            val session = manager.incoming(1, "room", CALL_ID)
            val other = manager.incoming(1, "other", OTHER_CALL_ID)
            session.apply(attempt())
            val seen = mutableListOf<State?>()
            val watch = launch { manager.watchIncoming(session) { seen += it.snapshot?.state } }
            runCurrent()

            other.apply(attempt(callId = OTHER_CALL_ID))
            session.apply(attempt(State.CONNECTING, version = 2))
            runCurrent()
            session.apply(ended(Outcome.COMPLETED))
            runCurrent()

            assertEquals(listOf(State.RINGING, State.CONNECTING), seen)
            assertTrue(watch.isCompleted)
        }

    @Test
    fun storeKeepsItsRecordsAndDropsCorruptData() {
        val action = PendingCallAction(1, "room", "/$CALL_ID/end", buildJsonObject { put("sessionId", "s1") })
        val call = UnfinishedCall(1, "room", true, "request-1")
        store.putPendingAction("end", action)
        store.putUnfinishedCall(call)
        val reloaded = DirectCallStore({ values[it] }, { _, _ -> })

        assertEquals(mapOf("end" to action), reloaded.pendingActions())
        assertEquals(mapOf("request-1" to call), reloaded.unfinishedCalls())

        values.replaceAll { _, _ -> "{broken" }
        assertTrue(reloaded.pendingActions().isEmpty())
        assertTrue(reloaded.unfinishedCalls().isEmpty())
    }

    companion object {
        private const val POLL = 2000L
        private const val RETRY = 5000L
    }
}
