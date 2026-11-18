/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.ui

import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import com.nextcloud.talk.models.json.conversations.ConversationDto
import com.nextcloud.talk.utils.DateUtils
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito

class MessageActionsStateTest {

    private val dateUtils = Mockito.mock(DateUtils::class.java)

    private val user = User().apply {
        id = 1L
        userId = "alice"
    }

    private fun buildStateFor(message: ChatMessage, conversation: ConversationModel? = null) =
        buildMessageActionsState(
            message = message,
            user = user,
            conversation = conversation,
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

    private fun fileMessage(path: String = "Talk/photo.jpg") =
        ChatMessage(
            jsonMessageId = 4,
            actorType = "users",
            actorId = "bob",
            message = "{file}",
            messageParameters = hashMapOf<String?, HashMap<String?, String?>>(
                "file" to hashMapOf("type" to "file", "name" to "photo.jpg", "path" to path)
            )
        )

    @Test
    fun showForward_trueForSentFile() {
        Assert.assertTrue(buildStateFor(fileMessage()).showForward)
    }

    @Test
    fun showForward_falseForFileStillUploading() {
        Assert.assertFalse(buildStateFor(fileMessage().apply { isTemporary = true }).showForward)
    }

    @Test
    fun showForward_falseForFileWithoutPath() {
        Assert.assertFalse(buildStateFor(fileMessage(path = "")).showForward)
    }

    @Test
    fun showForward_falseForFileOfConversationHostedElsewhere() {
        val conversation = ConversationModel.mapToConversationModel(
            ConversationDto(token = "abc", remoteServer = "https://other.example.com"),
            user
        )
        Assert.assertFalse(buildStateFor(fileMessage(), conversation).showForward)
    }
}
