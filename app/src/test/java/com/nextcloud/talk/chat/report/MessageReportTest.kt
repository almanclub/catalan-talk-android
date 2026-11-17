/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.report

import com.nextcloud.talk.chat.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReportTest {

    @Test
    fun `report text contains the reported message and nothing else of the conversation`() {
        val message = ChatMessage(
            jsonMessageId = 42,
            actorType = "users",
            actorId = "bob",
            actorDisplayName = "Bob",
            timestamp = 1_700_000_000L,
            message = "Buy cheap stuff"
        )

        val report = MessageReport.from(message, MessageReportReason.SPAM, "room1", "Team")

        assertEquals(
            """
            [Message report]
            Reason: spam_or_scam
            Conversation: Team (room1)
            Message ID: 42
            Sender: Bob (users/bob)
            Sent at: 2023-11-14T22:13:20Z
            Content:
            Buy cheap stuff
            """.trimIndent(),
            report.toText()
        )
    }

    @Test
    fun `long content is limited without splitting a surrogate pair`() {
        val emoji = "\uD83D\uDE00"
        val content = "a".repeat(MessageReport.MAX_CONTENT_LENGTH - 1) + emoji

        val limited = MessageReport.limitContent(content)

        assertEquals("a".repeat(MessageReport.MAX_CONTENT_LENGTH - 1), limited)
    }

    @Test
    fun `content at the limit is kept`() {
        val content = "a".repeat(MessageReport.MAX_CONTENT_LENGTH)

        assertEquals(content, MessageReport.limitContent(content))
        assertEquals(content, MessageReport.limitContent(content + "b"))
    }

    @Test
    fun `only messages of somebody else can be reported`() {
        assertFalse(isReportable(ChatMessage(actorType = "users", actorId = "alice"), "alice"))
        assertTrue(isReportable(ChatMessage(actorType = "users", actorId = "bob"), "alice"))
        assertTrue(isReportable(ChatMessage(actorType = "guests", actorId = "alice"), "alice"))
    }

    @Test
    fun `deleted, temporary and system messages cannot be reported`() {
        assertFalse(isReportable(ChatMessage(actorType = "users", actorId = "bob", isDeleted = true), "alice"))
        assertFalse(isReportable(ChatMessage(actorType = "users", actorId = "bob", isTemporary = true), "alice"))
        val systemMessage = ChatMessage(actorType = "users", actorId = "bob").apply {
            systemMessageType = ChatMessage.SystemMessageType.CONVERSATION_CREATED
        }
        assertFalse(isReportable(systemMessage, "alice"))
    }
}
