/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import com.nextcloud.talk.R
import com.nextcloud.talk.directcall.CallAttempt.Outcome
import com.nextcloud.talk.directcall.CallAttempt.State
import com.nextcloud.talk.directcall.CallAttemptFixtures.CALL_ID
import com.nextcloud.talk.directcall.CallAttemptFixtures.attempt
import com.nextcloud.talk.directcall.CallAttemptFixtures.ended
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallScreenTest {

    private fun TestScope.screen(
        initiator: Boolean,
        handler: suspend (String) -> CallAttempt? = { attempt() }
    ): Pair<DirectCallScreen, FakeSessionHost> {
        val host = FakeSessionHost(backgroundScope) { testScheduler.currentTime }
        host.handler = handler
        val session = DirectCallSession(1, "room", initiator, CALL_ID.takeUnless { initiator }, "request-1", host)
        return DirectCallScreen(session, backgroundScope) to host
    }

    private fun TestScope.preparedCaller(
        handler: suspend (String) -> CallAttempt? = { attempt() }
    ): Pair<DirectCallScreen, FakeSessionHost> {
        val prepared = screen(initiator = true, handler)
        prepared.first.prepare("s1", audioOnly = false, consent = false) {}
        runCurrent()
        return prepared
    }

    @Test
    fun attemptIsPreparedOnceAndMediaIsJoinedWithItsCallId() =
        runTest {
            val (screen, host) = screen(initiator = true)
            val joined = mutableListOf<String>()

            assertTrue(screen.requestRoomJoin())
            assertFalse(screen.requestRoomJoin())
            screen.prepare("s1", audioOnly = true, consent = false) { joined += it }
            screen.prepare("s2", audioOnly = false, consent = false) { joined += it }
            runCurrent()

            assertTrue(screen.isBound)
            assertEquals(listOf(CALL_ID), joined)
            assertEquals(listOf(""), host.paths())
            assertEquals("audio", (host.requests.single().second?.get("mediaType") as JsonPrimitive).content)
        }

    @Test
    fun failedAcceptStopsTheCallWithoutBusinessAction() =
        runTest {
            val (screen, host) = screen(initiator = false) { path ->
                if (path.endsWith("/accept")) throw CallAttemptException(409, "call_session_already_used")
                attempt()
            }
            val joined = mutableListOf<String>()

            screen.prepare("s1", audioOnly = false, consent = true) { joined += it }
            runCurrent()

            assertEquals(listOf("/$CALL_ID", "/$CALL_ID/accept"), host.paths())
            assertTrue(joined.isEmpty())
            assertTrue(screen.isOver)
            assertTrue(host.enqueued.isEmpty())
            assertEquals(R.string.nc_call_join_failed, screen.outcomeRes())
        }

    @Test
    fun mediaIsReadyWhenTheRemoteAndWithMcuAlsoThePublisherConnected() =
        runTest {
            val (mcu, mcuHost) = preparedCaller()
            val (mesh, meshHost) = preparedCaller()
            mcu.session.apply(attempt(State.CONNECTING, version = 2))
            mesh.session.apply(attempt(State.CONNECTING, version = 2))

            mcu.mediaConnected(publisher = false, hasMcu = true)
            mesh.mediaConnected(publisher = false, hasMcu = false)
            runCurrent()

            assertFalse("/$CALL_ID/media-ready" in mcuHost.paths())
            assertEquals("/$CALL_ID/media-ready", meshHost.paths().last())

            mcu.mediaConnected(publisher = true, hasMcu = true)
            runCurrent()

            assertEquals("/$CALL_ID/media-ready", mcuHost.paths().last())
        }

    @Test
    fun lostConnectionEndsAnAnsweredCallButOnlyStopsRinging() =
        runTest {
            val (answered, answeredHost) = preparedCaller()
            val (ringing, ringingHost) = preparedCaller()
            answered.session.apply(attempt(State.CONNECTED, version = 3))

            answered.connectionLost()
            ringing.connectionLost()
            runCurrent()

            assertEquals(listOf("/$CALL_ID/end"), answeredHost.enqueued.map { it.first })
            assertTrue(ringingHost.enqueued.isEmpty())
            assertTrue(answered.isOver)
            assertTrue(ringing.isOver)
            assertFalse(ringing.mayLeaveMedia)
            assertEquals(R.string.nc_call_no_response, ringing.outcomeRes())
        }

    @Test
    fun leavingTheCallEndsItOnlyWhenTheServerDidNot() =
        runTest {
            val (running, runningHost) = preparedCaller { path ->
                if (path.isEmpty()) attempt() else attempt(State.CONNECTED, version = 3)
            }
            val (finished, finishedHost) = preparedCaller { path ->
                if (path.isEmpty()) attempt() else ended(Outcome.COMPLETED)
            }

            running.leftCall()
            finished.leftCall()
            runCurrent()

            assertEquals(listOf("/$CALL_ID/end"), runningHost.enqueued.map { it.first })
            assertTrue(finishedHost.enqueued.isEmpty())
            assertTrue(finished.mayLeaveMedia)
            assertEquals(R.string.nc_call_history_outgoing_video, finished.outcomeRes())
        }

    @Test
    fun hangUpEndsOnlyAnAttemptThatIsStillRunning() =
        runTest {
            val (ringing, ringingHost) = preparedCaller()
            val (stopped, stoppedHost) = screen(initiator = false)

            stopped.fail(CallAttemptException(409, CallAttemptException.ANSWERED_ELSEWHERE))
            ringing.hangUp()
            stopped.hangUp()
            runCurrent()

            assertEquals(listOf("/$CALL_ID/cancel"), ringingHost.enqueued.map { it.first })
            assertTrue(stoppedHost.enqueued.isEmpty())
            assertNull(ringing.outcomeRes())
            assertEquals(R.string.nc_call_answered_elsewhere, stopped.outcomeRes())
        }
}
