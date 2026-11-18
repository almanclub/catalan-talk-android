/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.blockedusers

import com.nextcloud.talk.chat.data.model.ChatMessage

const val ACTOR_TYPE_USERS = "users"
const val ACTOR_TYPE_FEDERATED_USERS = "federated_users"

private const val NOTIFICATION_USER_TYPE = "user"

data class BlockedActor(val actorType: String, val actorId: String)

data class BlockedUser(val actor: BlockedActor, val displayName: String?, val blockedAt: Long)

/**
 * Whether the actor may be blocked by the user [ownUserId]: only other (federated) users, never guests, bots or the
 * user themselves.
 */
fun isBlockableActor(actorType: String?, actorId: String?, ownUserId: String?): Boolean =
    (actorType == ACTOR_TYPE_USERS || actorType == ACTOR_TYPE_FEDERATED_USERS) &&
        !actorId.isNullOrEmpty() &&
        !(actorType == ACTOR_TYPE_USERS && actorId == ownUserId)

/**
 * Whether the user [ownUserId] may block the author of [message].
 */
fun canBlockAuthor(message: ChatMessage, ownUserId: String?): Boolean =
    !message.isTemporary &&
        message.isRegularMessage() &&
        isBlockableActor(message.actorType, message.actorId, ownUserId)

fun ChatMessage.isFromBlockedAuthor(blockedActors: Set<BlockedActor>): Boolean {
    val type = actorType
    val id = actorId
    return type != null && id != null && isRegularMessage() && BlockedActor(type, id) in blockedActors
}

/**
 * Removes the messages written by [blockedActors]. System messages are kept, the conversation itself stays usable.
 */
fun List<ChatMessage>.withoutBlockedAuthors(blockedActors: Set<BlockedActor>): List<ChatMessage> =
    if (blockedActors.isEmpty()) this else filterNot { it.isFromBlockedAuthor(blockedActors) }

/**
 * The actor of a notification sender, from the "user" rich object of a notification. Federated users have their
 * server set and are identified by their cloud id.
 */
fun notificationSenderActor(type: String?, id: String?, server: String?): BlockedActor? {
    if (type != NOTIFICATION_USER_TYPE || id.isNullOrEmpty()) {
        return null
    }
    val host = server?.substringAfter("://")?.trimEnd('/')
    return if (host.isNullOrEmpty()) {
        BlockedActor(ACTOR_TYPE_USERS, id)
    } else {
        BlockedActor(ACTOR_TYPE_FEDERATED_USERS, "$id@$host")
    }
}

private fun ChatMessage.isRegularMessage(): Boolean =
    systemMessageType.let { it == null || it == ChatMessage.SystemMessageType.DUMMY }
