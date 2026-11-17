/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.report

import androidx.annotation.StringRes
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.data.model.ChatMessage
import java.time.Instant

enum class MessageReportReason(val code: String, @StringRes val labelRes: Int) {
    SPAM("spam_or_scam", R.string.nc_report_reason_spam),
    HARASSMENT("harassment_or_abuse", R.string.nc_report_reason_harassment),
    INAPPROPRIATE("inappropriate_or_illegal", R.string.nc_report_reason_inappropriate),
    OTHER("other", R.string.nc_report_reason_other)
}

/**
 * The summary of a reported message that is sent to the moderators. It only contains the reported message itself,
 * never the rest of the conversation or attached files.
 */
data class MessageReport(
    val reason: MessageReportReason,
    val roomToken: String,
    val roomName: String?,
    val messageId: Int,
    val senderDisplayName: String?,
    val senderActorType: String?,
    val senderActorId: String?,
    val sentAt: String,
    val content: String
) {
    fun toText(): String =
        buildString {
            appendLine("[Message report]")
            appendLine("Reason: ${reason.code}")
            appendLine("Conversation: ${roomName.orEmpty()} ($roomToken)")
            appendLine("Message ID: $messageId")
            appendLine("Sender: ${senderDisplayName.orEmpty()} ($senderActorType/$senderActorId)")
            appendLine("Sent at: $sentAt")
            appendLine("Content:")
            append(content)
        }

    companion object {
        const val MAX_CONTENT_LENGTH = 4000

        fun from(message: ChatMessage, reason: MessageReportReason, roomToken: String, roomName: String?) =
            MessageReport(
                reason = reason,
                roomToken = roomToken,
                roomName = roomName,
                messageId = message.jsonMessageId,
                senderDisplayName = message.actorDisplayName,
                senderActorType = message.actorType,
                senderActorId = message.actorId,
                sentAt = Instant.ofEpochSecond(message.timestamp).toString(),
                content = limitContent(message.getRichText())
            )

        internal fun limitContent(content: String): String {
            if (content.length <= MAX_CONTENT_LENGTH) {
                return content
            }
            // Do not cut a character that is encoded as a surrogate pair in half
            val end = if (Character.isHighSurrogate(content[MAX_CONTENT_LENGTH - 1])) {
                MAX_CONTENT_LENGTH - 1
            } else {
                MAX_CONTENT_LENGTH
            }
            return content.substring(0, end)
        }
    }
}

/**
 * Whether [message] may be reported by the user [userId]: only real messages of somebody else can be reported.
 */
fun isReportable(message: ChatMessage, userId: String?): Boolean =
    !message.isDeleted &&
        !message.isTemporary &&
        message.systemMessageType.let { it == null || it == ChatMessage.SystemMessageType.DUMMY } &&
        !(message.actorType == ACTOR_TYPE_USERS && message.actorId == userId)

/**
 * Whether [message] was sent by the moderation account, which therefore cannot handle the report itself.
 */
fun isSentByModeration(message: ChatMessage, moderationUserId: String): Boolean =
    message.actorType == ACTOR_TYPE_USERS && message.actorId == moderationUserId

private const val ACTOR_TYPE_USERS = "users"
