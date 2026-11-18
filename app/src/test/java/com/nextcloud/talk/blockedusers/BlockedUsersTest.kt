/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.blockedusers

import com.nextcloud.talk.chat.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedUsersTest {

    private val bob = BlockedActor(ACTOR_TYPE_USERS, "bob")

    @Test
    fun `messages of a blocked user are hidden, their system messages are kept`() {
        val fromBob = message(1, ACTOR_TYPE_USERS, "bob")
        val fromCarol = message(2, ACTOR_TYPE_USERS, "carol")
        val bobJoined = ChatMessage(
            jsonMessageId = 3,
            actorType = ACTOR_TYPE_USERS,
            actorId = "bob",
            systemMessageType = ChatMessage.SystemMessageType.USER_ADDED
        )

        val visible = listOf(fromBob, fromCarol, bobJoined).withoutBlockedAuthors(setOf(bob))

        assertEquals(listOf(fromCarol, bobJoined), visible)
    }

    @Test
    fun `a federated user with the same id as a blocked local user is not hidden`() {
        val federatedBob = message(1, ACTOR_TYPE_FEDERATED_USERS, "bob")

        assertEquals(listOf(federatedBob), listOf(federatedBob).withoutBlockedAuthors(setOf(bob)))
    }

    @Test
    fun `only other users and federated users can be blocked`() {
        assertTrue(canBlockAuthor(message(1, ACTOR_TYPE_USERS, "bob"), "alice"))
        assertTrue(canBlockAuthor(message(1, ACTOR_TYPE_FEDERATED_USERS, "alice@other.example.com"), "alice"))

        assertFalse(canBlockAuthor(message(1, ACTOR_TYPE_USERS, "alice"), "alice"))
        assertFalse(canBlockAuthor(message(1, "guests", "abc123"), "alice"))
        assertFalse(canBlockAuthor(message(1, "bots", "bot-1"), "alice"))
        assertFalse(
            canBlockAuthor(
                ChatMessage(
                    jsonMessageId = 1,
                    actorType = ACTOR_TYPE_USERS,
                    actorId = "bob",
                    systemMessageType = ChatMessage.SystemMessageType.USER_ADDED
                ),
                "alice"
            )
        )
    }

    @Test
    fun `the sender of a notification is a local user without a server and a federated user with one`() {
        assertEquals(BlockedActor(ACTOR_TYPE_USERS, "bob"), notificationSenderActor("user", "bob", null))
        assertEquals(BlockedActor(ACTOR_TYPE_USERS, "bob"), notificationSenderActor("user", "bob", ""))
        assertEquals(
            BlockedActor(ACTOR_TYPE_FEDERATED_USERS, "bob@other.example.com"),
            notificationSenderActor("user", "bob", "https://other.example.com/")
        )
        assertEquals(
            BlockedActor(ACTOR_TYPE_FEDERATED_USERS, "bob@other.example.com"),
            notificationSenderActor("user", "bob", "other.example.com")
        )
    }

    @Test
    fun `a notification sender that is not a user cannot be blocked`() {
        assertNull(notificationSenderActor("guest", "abc123", null))
        assertNull(notificationSenderActor("user", "", null))
        assertNull(notificationSenderActor(null, "bob", null))
    }

    private fun message(id: Int, actorType: String, actorId: String) =
        ChatMessage(jsonMessageId = id, actorType = actorType, actorId = actorId, message = "Hello")
}
