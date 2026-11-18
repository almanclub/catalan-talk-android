/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.ui

import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import com.nextcloud.talk.utils.DateUtils
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito

class MessageActionsStateTest {

    private val dateUtils = Mockito.mock(DateUtils::class.java)

    private fun buildStateFor(message: ChatMessage) =
        buildMessageActionsState(
            message = message,
            user = User().apply { userId = "alice" },
            conversation = null,
            hasChatPermission = true,
            hasReactPermission = true,
            spreedCapabilities = SpreedCapabilityDto(),
            isOnline = true,
            dateUtils = dateUtils,
            conversationThreadId = null
        )

    @Test
    fun showCopyMessageLink_trueForRegularMessage() {
        val msg = ChatMessage().apply {
            jsonMessageId = 4
            message = "Hello world"
        }
        Assert.assertTrue(buildStateFor(msg).showCopyMessageLink)
    }

    @Test
    fun showCopyMessageLink_falseForDeletedMessage() {
        val msg = ChatMessage().apply {
            jsonMessageId = 4
            message = "Hello world"
            isDeleted = true
        }
        Assert.assertFalse(buildStateFor(msg).showCopyMessageLink)
    }

    @Test
    fun showCopyMessageLink_falseForSystemMessage() {
        // isSystemMessage is computed at construction time, so the type must be set via the constructor
        val msg = ChatMessage(
            jsonMessageId = 4,
            systemMessageType = ChatMessage.SystemMessageType.CONVERSATION_CREATED
        )
        Assert.assertFalse(buildStateFor(msg).showCopyMessageLink)
    }

    @Test
    fun showReport_falseForOwnMessage() {
        val msg = ChatMessage(jsonMessageId = 4, actorType = "users", actorId = "alice", message = "Hello world")
        Assert.assertFalse(buildStateFor(msg).showReport)
    }

    @Test
    fun showReport_trueForMessageOfSomebodyElse() {
        val msg = ChatMessage(jsonMessageId = 4, actorType = "users", actorId = "bob", message = "Hello world")
        Assert.assertTrue(buildStateFor(msg).showReport)
    }

    @Test
    fun showBlock_trueForMessageOfAnotherUser() {
        val msg = ChatMessage(jsonMessageId = 4, actorType = "users", actorId = "bob", message = "Hello world")
        Assert.assertTrue(buildStateFor(msg).showBlock)
    }

    @Test
    fun showBlock_falseForOwnMessage() {
        val msg = ChatMessage(jsonMessageId = 4, actorType = "users", actorId = "alice", message = "Hello world")
        Assert.assertFalse(buildStateFor(msg).showBlock)
    }

    @Test
    fun showBlock_falseForGuestMessage() {
        val msg = ChatMessage(jsonMessageId = 4, actorType = "guests", actorId = "abc123", message = "Hello world")
        Assert.assertFalse(buildStateFor(msg).showBlock)
    }
}
