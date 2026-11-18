/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.forward

import com.google.gson.Gson
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.utils.CloudIdUtils

private const val FILE_PLACEHOLDER = "{file}"
private const val VOICE_MESSAGE_TYPE = "voice-message"
private const val ACTOR_TYPE_USERS = "users"
private const val ACTOR_TYPE_FEDERATED_USERS = "federated_users"
private val otherLocalActorTypes = setOf("guests", "emails")

const val SHARE_TYPE_USER = 0
const val SHARE_TYPE_REMOTE = 6
const val SHARE_TYPE_ROOM = 10
const val SHARE_PERMISSION_READ = 1

/**
 * A file of the user's own storage to forward, [path] relative to the user's files and starting with "/".
 */
data class ForwardedFile(val path: String, val name: String, val caption: String?, val isVoiceMessage: Boolean)

/**
 * Whether [message] is a sent single file or voice message whose file can be copied to forward it.
 */
fun isForwardableFile(message: ChatMessage): Boolean {
    val type = message.getCalculateMessageType()
    return (
        type == ChatMessage.MessageType.SINGLE_NC_ATTACHMENT_MESSAGE ||
            type == ChatMessage.MessageType.VOICE_MESSAGE
        ) &&
        !message.isTemporary &&
        !message.isDeleted &&
        !message.isDeletedCommentMessage &&
        message.fileParameters.path.trim('/').isNotEmpty() &&
        message.fileParameters.name.isNotEmpty()
}

/**
 * The file of [this] message to forward, or null if it has none. The caption is only kept if [withCaption].
 */
fun ChatMessage.toForwardedFile(withCaption: Boolean): ForwardedFile? {
    val path = fileParameters.path.trim('/')
    val name = fileParameters.name
    if (path.isEmpty() || name.isEmpty()) {
        return null
    }
    val caption = if (withCaption) {
        getRichText().replace(FILE_PLACEHOLDER, "").trim().takeIf { it.isNotEmpty() && it != name }
    } else {
        null
    }
    return ForwardedFile("/$path", name, caption, isVoiceMessage)
}

/**
 * The talkMetaData of a forwarded [file]: its caption, whether it is a voice message and whether it is sent silently.
 */
fun forwardTalkMetaData(file: ForwardedFile, silent: Boolean): String {
    val metaData = linkedMapOf<String, Any>()
    file.caption?.let { metaData["caption"] = it }
    if (file.isVoiceMessage) {
        metaData["messageType"] = VOICE_MESSAGE_TYPE
    }
    if (silent) {
        metaData["silent"] = true
    }
    return Gson().toJson(metaData)
}

data class RoomMember(val actorType: String, val actorId: String)

data class ReadOnlyRecipient(val shareType: Int, val shareWith: String)

/**
 * How a file reaches the members of a conversation: as attachment of the conversation and/or as read-only share for
 * each member who can not open the files of the conversation.
 */
data class ForwardRecipients(val usesAttachment: Boolean, val readOnly: List<ReadOnlyRecipient>)

/**
 * The recipients of a forward to a conversation of [members], as listed by this server: "users" are the users of this
 * server, "federated_users" the users of other servers. Files can not be attached to a conversation hosted on another
 * server ([isHostedElsewhere]), and remote users can not open attachments, so they get read-only shares.
 */
fun forwardRecipients(
    members: List<RoomMember>,
    ownUserId: String,
    ownCloudId: String,
    isHostedElsewhere: Boolean
): ForwardRecipients {
    val remoteUsers = members
        .filter { it.actorType == ACTOR_TYPE_FEDERATED_USERS && !CloudIdUtils.isSameCloudId(it.actorId, ownCloudId) }
        .map { it.actorId }
        .distinctBy { it.lowercase() }
    val localUsers = members
        .filter { it.actorType == ACTOR_TYPE_USERS && it.actorId != ownUserId }
        .map { it.actorId }
        .distinct()
    val hasOtherLocalMembers = localUsers.isNotEmpty() || members.any { it.actorType in otherLocalActorTypes }

    val usesAttachment = !isHostedElsewhere && (hasOtherLocalMembers || remoteUsers.isEmpty())
    val readOnly = remoteUsers.map { ReadOnlyRecipient(SHARE_TYPE_REMOTE, it) } +
        if (isHostedElsewhere) localUsers.map { ReadOnlyRecipient(SHARE_TYPE_USER, it) } else emptyList()
    return ForwardRecipients(usesAttachment, readOnly)
}
