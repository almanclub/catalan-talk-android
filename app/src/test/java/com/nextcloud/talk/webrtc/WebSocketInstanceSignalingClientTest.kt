/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc

import com.nextcloud.talk.models.json.signaling.NCSignalingMessageDto
import com.nextcloud.talk.models.json.websocket.ActorWebSocketMessageDto
import com.nextcloud.talk.models.json.websocket.CallWebSocketMessageDto
import com.nextcloud.talk.utils.RetryBackoff
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.greenrobot.eventbus.EventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tests for the signaling WebSocket client configuration ([WebSocketInstance.createSignalingHttpClient]).
 *
 * Without a ping interval OkHttp sends no protocol-level pings on WebSockets and uses an infinite read timeout,
 * so a half-open connection (e.g. after a WiFi to cellular switch without a TCP reset) is never detected: the
 * reconnect path via "onFailure" never runs and the call goes silently mute/deaf. These tests pin the ping
 * configuration that makes dead connections fail fast.
 */
class WebSocketInstanceSignalingClientTest {

    @Test
    fun closingSocketReconnectsInsteadOfSendingRoomJoinToIt() {
        val socket = mock<WebSocket>()
        val instance = socketInstance(socket)
        Mockito.doNothing().`when`(instance).restartWebSocket()
        try {
            instance.onClosing(socket, 1000, "")
            assertFalse(instance.isConnected)
            assertTrue(field(instance, "pendingReconnect") is ScheduledFuture<*>)

            instance.joinRoomWithRoomTokenAndSession("room", "session")

            verify(socket, never()).send(any<String>())
            verify(instance).restartWebSocket()
        } finally {
            (field(instance, "pendingReconnect") as? ScheduledFuture<*>)?.cancel(false)
        }
    }

    @Test
    fun duplicateCloseAndFailureDoNotDelayReconnect() {
        val socket = mock<WebSocket>()
        val instance = socketInstance(socket)
        Mockito.doNothing().`when`(instance).restartWebSocket()
        try {
            instance.onClosing(socket, 1000, "")
            val pending = field(instance, "pendingReconnect")
            assertTrue(pending is ScheduledFuture<*>)
            instance.joinRoomWithRoomTokenAndSession("room", "session")

            instance.onFailure(socket, IOException("closed"), null)

            assertSame(pending, field(instance, "pendingReconnect"))
            assertEquals(1, (field(instance, "messagesQueue") as List<*>).size)
        } finally {
            (field(instance, "pendingReconnect") as? ScheduledFuture<*>)?.cancel(false)
        }
    }

    @Test
    fun closedOldSocketDoesNotDisconnectCurrentSocket() {
        val instance = socketInstance(mock())

        instance.onClosing(mock(), 1000, "")
        instance.onClosed(mock(), 1000, "")

        assertTrue(instance.isConnected)
        assertNull(field(instance, "pendingReconnect"))
    }

    @Test
    fun disconnectedSocketDoesNotReportCachedRoomAsJoined() {
        val instance = socketInstance(mock())
        val events = mock<EventBus>()
        instance.eventBus = events
        setField(instance, "isConnected", false)
        setField(instance, "currentRoomToken", "room")
        setField(instance, "currentNormalBackendSession", "session")
        Mockito.doNothing().`when`(instance).restartWebSocket()

        instance.joinRoomWithRoomTokenAndSession("room", "session")

        verify(events, never()).post(any())
        verify(instance).restartWebSocket()
        assertEquals(1, (field(instance, "messagesQueue") as List<*>).size)
    }

    private fun socketInstance(socket: WebSocket): WebSocketInstance =
        Mockito.mock(WebSocketInstance::class.java, Mockito.CALLS_REAL_METHODS).apply {
            setField(this, "internalWebSocket", socket)
            setField(this, "isConnected", true)
            setField(this, "reconnectBackoff", RetryBackoff())
            setField(this, "messagesQueue", ArrayList<String>())
            setField(
                this,
                "webSocketConnectionHelper",
                Mockito.mock(WebSocketConnectionHelper::class.java, Mockito.CALLS_REAL_METHODS)
            )
        }

    private fun field(instance: WebSocketInstance, name: String): Any? =
        WebSocketInstance::class.java.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun setField(instance: WebSocketInstance, name: String, value: Any) {
        WebSocketInstance::class.java.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    @Test
    fun signalingClientHasPingIntervalConfigured() {
        val signalingClient = WebSocketInstance.createSignalingHttpClient(OkHttpClient())

        // hardcoded on purpose: fails if the ping interval in WebSocketInstance changes or is removed
        assertEquals(
            "signaling WebSocket client must send pings to detect half-open connections",
            30_000,
            signalingClient.pingIntervalMillis
        )
    }

    @Test
    fun senderIsTakenFromTheEnvelope() {
        val message = CallWebSocketMessageDto(
            senderWebSocketMessage = ActorWebSocketMessageDto(type = "session", sessionId = "real-sender"),
            ncSignalingMessage = NCSignalingMessageDto(from = "claimed-sender")
        )

        WebSocketInstance.takeSenderFromEnvelope(message)

        assertEquals("real-sender", message.ncSignalingMessage!!.from)
    }

    @Test
    fun claimedSenderIsDroppedWithoutEnvelope() {
        val message = CallWebSocketMessageDto(ncSignalingMessage = NCSignalingMessageDto(from = "claimed-sender"))

        WebSocketInstance.takeSenderFromEnvelope(message)

        assertNull(message.ncSignalingMessage!!.from)
    }

    @Test
    fun signalingClientIsDerivedFromBaseClientWithoutMutatingIt() {
        val baseClient = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        val signalingClient = WebSocketInstance.createSignalingHttpClient(baseClient)

        assertNotSame("a dedicated client instance must be used", baseClient, signalingClient)
        assertEquals(
            "base client (shared with regular HTTP calls) must stay unchanged",
            0,
            baseClient.pingIntervalMillis
        )
        assertEquals(baseClient.connectTimeoutMillis, signalingClient.connectTimeoutMillis)
        assertEquals(baseClient.readTimeoutMillis, signalingClient.readTimeoutMillis)
    }
}
