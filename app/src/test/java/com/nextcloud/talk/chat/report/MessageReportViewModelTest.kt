/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.report

import com.nextcloud.talk.api.NcApiCoroutines
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.json.capabilities.CapabilitiesDto
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import com.nextcloud.talk.models.json.chat.ChatOverallSingleMessage
import com.nextcloud.talk.models.json.conversations.ConversationDto
import com.nextcloud.talk.models.json.conversations.RoomOCS
import com.nextcloud.talk.models.json.conversations.RoomOverall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MessageReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val ncApiCoroutines: NcApiCoroutines = mock()
    private val viewModel = MessageReportViewModel(ncApiCoroutines)

    private val user = User(
        userId = "alice",
        username = "alice",
        token = "token",
        displayName = "Alice",
        baseUrl = "https://cloud.example.com",
        capabilities = CapabilitiesDto().apply {
            spreedCapability = SpreedCapabilityDto().apply {
                features = listOf("conversation-v4", "chat-v2")
            }
        }
    )

    private val report = MessageReport(
        reason = MessageReportReason.HARASSMENT,
        roomToken = "room1",
        roomName = "Team",
        messageId = 42,
        senderDisplayName = "Bob",
        senderActorType = "users",
        senderActorId = "bob",
        sentAt = "2023-11-14T22:13:20Z",
        content = "Rude message"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        wheneverBlocking {
            ncApiCoroutines.sendChatMessage(any(), any(), any(), any(), any(), any(), any(), anyOrNull())
        }.thenReturn(ChatOverallSingleMessage())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `report is sent to the one-to-one conversation with the moderation account`() =
        runTest(dispatcher) {
            wheneverBlocking { ncApiCoroutines.createRoom(any(), any(), any()) }.thenReturn(room(MODERATION_ROOM))

            assertTrue(viewModel.sendToModeration(user, MODERATION, CHAT_API_VERSION, report))
            advanceUntilIdle()

            assertEquals(MessageReportViewModel.Result.Sent, viewModel.results.first())
            verifyBlocking(ncApiCoroutines) {
                createRoom(
                    any(),
                    eq("https://cloud.example.com/ocs/v2.php/apps/spreed/api/v4/room"),
                    eq(mapOf("roomType" to "1", "invite" to MODERATION))
                )
            }
            verifyBlocking(ncApiCoroutines) {
                sendChatMessage(
                    any(),
                    eq("https://cloud.example.com/ocs/v2.php/apps/spreed/api/v1/chat/$MODERATION_ROOM"),
                    eq(report.toText()),
                    eq("Alice"),
                    eq(0),
                    eq(false),
                    any(),
                    anyOrNull()
                )
            }
        }

    @Test
    fun `a second report is not sent while the first one is still being sent`() =
        runTest(dispatcher) {
            val roomCreated = CompletableDeferred<RoomOverall>()
            wheneverBlocking { ncApiCoroutines.createRoom(any(), any(), any()) }
                .doSuspendableAnswer { roomCreated.await() }

            assertTrue(viewModel.sendToModeration(user, MODERATION, CHAT_API_VERSION, report))
            advanceUntilIdle()
            assertFalse(viewModel.sendToModeration(user, MODERATION, CHAT_API_VERSION, report))

            roomCreated.complete(room(MODERATION_ROOM))
            advanceUntilIdle()

            assertEquals(MessageReportViewModel.Result.Sent, viewModel.results.first())
            verifyBlocking(ncApiCoroutines, times(1)) { createRoom(any(), any(), any()) }
            assertTrue(viewModel.sendToModeration(user, MODERATION, CHAT_API_VERSION, report))
        }

    @Test
    fun `report falls back to email when the moderation account cannot be reached`() =
        runTest(dispatcher) {
            wheneverBlocking { ncApiCoroutines.createRoom(any(), any(), any()) }
                .thenThrow(HttpException(Response.error<Any>(HTTP_NOT_FOUND, "".toResponseBody())))

            viewModel.sendToModeration(user, MODERATION, CHAT_API_VERSION, report)
            advanceUntilIdle()

            assertEquals(MessageReportViewModel.Result.Failed(report), viewModel.results.first())
            verifyBlocking(ncApiCoroutines, never()) {
                sendChatMessage(any(), any(), any(), any(), any(), any(), any(), anyOrNull())
            }
        }

    private fun room(token: String) = RoomOverall(RoomOCS(null, ConversationDto(token = token)))

    private companion object {
        const val MODERATION = "moderation"
        const val MODERATION_ROOM = "modroom"
        const val CHAT_API_VERSION = 1
        const val HTTP_NOT_FOUND = 404
    }
}
