/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.forward

import com.nextcloud.talk.chat.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedFileTest {

    private fun fileMessage(
        message: String = "{file}",
        path: String? = "Talk/photo.jpg",
        messageType: String = "comment"
    ) = ChatMessage(
        jsonMessageId = 7,
        actorType = "users",
        actorId = "bob",
        message = message,
        messageType = messageType,
        messageParameters = hashMapOf<String?, HashMap<String?, String?>>(
            "file" to hashMapOf(
                "type" to "file",
                "name" to "photo.jpg",
                "path" to path,
                "link" to "https://cloud.example.com/f/1"
            )
        )
    )

    @Test
    fun sentFileAndVoiceMessagesAreForwardable() {
        assertTrue(isForwardableFile(fileMessage()))
        assertTrue(isForwardableFile(fileMessage(messageType = "voice-message")))
    }

    @Test
    fun temporaryDeletedOrInaccessibleFilesAreNotForwardable() {
        assertFalse(isForwardableFile(fileMessage().apply { isTemporary = true }))
        assertFalse(isForwardableFile(fileMessage().apply { isDeleted = true }))
        assertFalse(isForwardableFile(fileMessage(path = null)))
        assertFalse(isForwardableFile(ChatMessage(jsonMessageId = 7, message = "Hello")))
    }

    @Test
    fun fileKeepsItsCaptionOnlyWhenCaptionsAreSupported() {
        val message = fileMessage(message = "Our trip")

        assertEquals(ForwardedFile("/Talk/photo.jpg", "photo.jpg", "Our trip", false), message.toForwardedFile(true))
        assertNull(message.toForwardedFile(false)!!.caption)
    }

    @Test
    fun fileWithoutCaptionHasNone() {
        assertNull(fileMessage().toForwardedFile(true)!!.caption)
    }

    @Test
    fun metaDataCarriesCaptionVoiceMessageAndSilent() {
        val voiceMessage = ForwardedFile("/Talk/a.mp3", "a.mp3", "Listen", true)

        assertEquals(
            """{"caption":"Listen","messageType":"voice-message","silent":true}""",
            forwardTalkMetaData(voiceMessage, true)
        )
        assertEquals("{}", forwardTalkMetaData(ForwardedFile("/a.txt", "a.txt", null, false), false))
    }

    @Test
    fun localConversationGetsTheFileAsAttachment() {
        val recipients = forwardRecipients(
            listOf(RoomMember("users", "alice"), RoomMember("users", "bob")),
            OWN_USER_ID,
            OWN_CLOUD_ID,
            false
        )

        assertEquals(ForwardRecipients(true, emptyList()), recipients)
    }

    @Test
    fun remoteUsersOfALocalConversationGetReadOnlySharesOnce() {
        val recipients = forwardRecipients(
            listOf(
                RoomMember("users", "alice"),
                RoomMember("federated_users", "carol@other.example.com"),
                RoomMember("federated_users", "Carol@Other.example.com")
            ),
            OWN_USER_ID,
            OWN_CLOUD_ID,
            false
        )

        assertEquals(
            ForwardRecipients(false, listOf(ReadOnlyRecipient(SHARE_TYPE_REMOTE, "carol@other.example.com"))),
            recipients
        )
    }

    @Test
    fun mixedConversationGetsAttachmentAndReadOnlyShares() {
        val recipients = forwardRecipients(
            listOf(
                RoomMember("users", "alice"),
                RoomMember("guests", "guest-1"),
                RoomMember("federated_users", "carol@other.example.com")
            ),
            OWN_USER_ID,
            OWN_CLOUD_ID,
            false
        )

        assertEquals(
            ForwardRecipients(true, listOf(ReadOnlyRecipient(SHARE_TYPE_REMOTE, "carol@other.example.com"))),
            recipients
        )
    }

    @Test
    fun conversationHostedElsewhereSharesWithEveryoneButOneself() {
        val recipients = forwardRecipients(
            listOf(
                RoomMember("users", "alice"),
                RoomMember("users", "dave"),
                RoomMember("federated_users", "ALICE@cloud.example.com"),
                RoomMember("federated_users", "carol@other.example.com")
            ),
            OWN_USER_ID,
            OWN_CLOUD_ID,
            true
        )

        assertEquals(
            ForwardRecipients(
                false,
                listOf(
                    ReadOnlyRecipient(SHARE_TYPE_REMOTE, "carol@other.example.com"),
                    ReadOnlyRecipient(SHARE_TYPE_USER, "dave")
                )
            ),
            recipients
        )
    }

    companion object {
        private const val OWN_USER_ID = "alice"
        private const val OWN_CLOUD_ID = "alice@cloud.example.com"
    }
}
