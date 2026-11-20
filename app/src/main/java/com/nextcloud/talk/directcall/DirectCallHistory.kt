/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import android.util.Log
import androidx.annotation.StringRes
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.chat.data.model.ChatMessage.SystemMessageType
import com.nextcloud.talk.chat.ui.model.MessageTypeContent
import com.nextcloud.talk.data.database.model.ChatMessageEntity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.directcall.CallAttempt.MediaType
import com.nextcloud.talk.directcall.CallAttempt.Outcome
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.conversations.ConversationEnums
import com.nextcloud.talk.utils.CapabilitiesUtil
import com.nextcloud.talk.utils.CloudIdUtils
import com.nextcloud.talk.utils.ConversationUtils
import com.nextcloud.talk.utils.SpreedFeatures
import java.util.Locale

/**
 * Presentation rules for the call history of direct (1-1 and federated 1-1) conversations: call_result cards,
 * localized legacy call texts and the technical call events that are hidden. Stored history is never rewritten.
 */
@Suppress("TooManyFunctions")
object DirectCallHistory {
    private val TAG = DirectCallHistory::class.java.simpleName

    private const val COMMENT_DELETED = "comment_deleted"
    private const val LEGACY_UNANSWERED_CALL = "Unanswered call"
    private const val LEGACY_MISSED_CALL = "Missed call"
    private const val END_REASON_NOT_CONNECTED = "ended_before_connection"
    private const val END_REASON_CONNECTION_TIMEOUT = "connection_timeout"
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val MILLIS_PER_SECOND = 1000L

    private val HIDDEN_EVENT_TYPES = setOf(
        SystemMessageType.CALL_STARTED,
        SystemMessageType.CALL_JOINED,
        SystemMessageType.CALL_LEFT,
        SystemMessageType.CALL_ENDED,
        SystemMessageType.CALL_ENDED_EVERYONE
    )

    private val LEGACY_HISTORY_TYPES = setOf(
        SystemMessageType.CALL_MISSED,
        SystemMessageType.CALL_TRIED,
        SystemMessageType.CALL_RESULT
    )

    fun nowSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    fun isDirectRoom(conversation: ConversationModel?): Boolean =
        conversation?.type == ConversationEnums.ConversationType.ROOM_TYPE_ONE_TO_ONE_CALL ||
            ConversationUtils.isFederatedDirectMessage(conversation)

    fun isSupported(user: User?): Boolean =
        CapabilitiesUtil.hasSpreedFeatureCapability(
            user?.capabilities?.spreedCapability,
            SpreedFeatures.CALL_ATTEMPTS_V1
        )

    /**
     * Whether the technical call events (started, joined, left, ended) of [conversation] are hidden, as its calls
     * are summarized by call_result rows.
     */
    fun hidesCallEvents(conversation: ConversationModel?, user: User?): Boolean =
        isDirectRoom(conversation) && isSupported(user)

    fun isHiddenCallEvent(type: SystemMessageType?): Boolean = type in HIDDEN_EVENT_TYPES

    /**
     * The full federated cloud ID of [user], which is how call attempts identify caller and callee.
     */
    fun viewerCloudId(user: User?): String? {
        val userId = user?.userId ?: return null
        return user.baseUrl?.let { CloudIdUtils.ownCloudId(userId, it) }
    }

    /**
     * True when [viewerCloudId] placed the call, false when it received the call, null when it is neither.
     */
    fun isCaller(attempt: CallAttempt, viewerCloudId: String?): Boolean? =
        when {
            CloudIdUtils.isSameCloudId(viewerCloudId, attempt.callerCloudId) -> true
            CloudIdUtils.isSameCloudId(viewerCloudId, attempt.calleeCloudId) -> false
            else -> null
        }

    @StringRes
    fun resultTitleRes(attempt: CallAttempt, isCaller: Boolean): Int =
        when (attempt.outcome) {
            Outcome.NO_ANSWER -> if (isCaller) R.string.nc_call_history_no_answer else R.string.nc_call_history_missed
            Outcome.REJECTED -> if (isCaller) R.string.nc_call_history_rejected else R.string.nc_call_history_declined
            Outcome.CANCELLED -> R.string.nc_call_history_cancelled
            Outcome.COMPLETED -> completedTitleRes(attempt.mediaType == MediaType.VIDEO, isCaller)
            Outcome.FAILED -> failedTitleRes(attempt.endReason)
            null -> R.string.nc_call_history_unavailable
        }

    @StringRes
    private fun completedTitleRes(isVideo: Boolean, isCaller: Boolean): Int =
        when {
            isVideo && isCaller -> R.string.nc_call_history_outgoing_video
            isVideo -> R.string.nc_call_history_incoming_video
            isCaller -> R.string.nc_call_history_outgoing_voice
            else -> R.string.nc_call_history_incoming_voice
        }

    @StringRes
    private fun failedTitleRes(endReason: String?): Int =
        when (endReason) {
            END_REASON_NOT_CONNECTED -> R.string.nc_call_history_not_connected
            END_REASON_CONNECTION_TIMEOUT -> R.string.nc_call_history_connection_timeout
            else -> R.string.nc_call_history_failed
        }

    /**
     * The connected duration of [attempt]; ringing and setup time are never shown as call duration.
     */
    fun durationText(attempt: CallAttempt): String? {
        val counts = attempt.connectedAt != null &&
            (attempt.outcome == Outcome.COMPLETED || attempt.outcome == Outcome.FAILED)
        return attempt.durationSeconds?.takeIf { counts }?.let(::formatDuration)
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    private fun isExpired(expirationTimestamp: Int, now: Long): Boolean =
        expirationTimestamp > 0 && expirationTimestamp <= now

    private fun isRemoved(message: ChatMessage, now: Long): Boolean =
        message.isDeleted || message.isDeletedCommentMessage || isExpired(message.expirationTimestamp, now)

    private fun isDeleted(entity: ChatMessageEntity): Boolean = entity.deleted || entity.messageType == COMMENT_DELETED

    /**
     * The ended attempt of a call_result row that is neither deleted nor expired.
     */
    fun finalAttempt(message: ChatMessage, now: Long): CallAttempt? =
        if (message.systemMessageType == SystemMessageType.CALL_RESULT && !isRemoved(message, now)) {
            message.callAttemptSnapshot?.takeIf { it.isTerminal && it.outcome != null }
        } else {
            null
        }

    /**
     * Localized text of call history rows stored before call attempts carried metadata. Only the known server texts
     * are translated, any other text stays as sent.
     */
    @StringRes
    fun legacyTitleRes(message: ChatMessage, now: Long): Int? {
        val isLegacyRow = message.isSystemMessage &&
            message.systemMessageType in LEGACY_HISTORY_TYPES &&
            message.callAttempt.isNullOrEmpty() &&
            !isRemoved(message, now)
        return when (message.message?.trim()?.takeIf { isLegacyRow }) {
            LEGACY_UNANSWERED_CALL -> R.string.nc_call_history_no_answer
            LEGACY_MISSED_CALL -> R.string.nc_call_history_missed
            else -> null
        }
    }

    /**
     * Localized text of a call history row, e.g. for the conversation list, or null to show the server text.
     */
    @StringRes
    fun historyTextRes(message: ChatMessage, viewerCloudId: String?, now: Long): Int? {
        val attempt = finalAttempt(message, now)
        val isCaller = attempt?.let { isCaller(it, viewerCloudId) }
        if (attempt != null && isCaller == null) {
            Log.w(TAG, "Viewer is neither caller nor callee of call_result ${message.jsonMessageId}")
        }
        return if (attempt != null && isCaller != null) {
            resultTitleRes(attempt, isCaller)
        } else {
            legacyTitleRes(message, now) ?: unavailableTextRes(message, now)
        }
    }

    @StringRes
    private fun unavailableTextRes(message: ChatMessage, now: Long): Int? =
        R.string.nc_call_history_unavailable.takeIf {
            message.systemMessageType == SystemMessageType.CALL_RESULT &&
                !isRemoved(message, now) &&
                message.message.isNullOrEmpty()
        }

    /**
     * The chat content of a call history system message, or null when it is an ordinary system message. Direct
     * conversations show a call card, other conversations the localized text.
     */
    fun systemMessageContent(
        message: ChatMessage,
        viewerCloudId: String?,
        isDirectRoom: Boolean,
        now: Long
    ): MessageTypeContent? {
        val attempt = finalAttempt(message, now)?.takeIf { isDirectRoom }
        val isCaller = attempt?.let { isCaller(it, viewerCloudId) }
        return if (attempt != null && isCaller != null) {
            MessageTypeContent.CallResult(
                titleRes = resultTitleRes(attempt, isCaller),
                isOutgoing = isCaller,
                isMissed = !isCaller && attempt.outcome == Outcome.NO_ANSWER,
                isVideo = attempt.mediaType == MediaType.VIDEO,
                durationText = durationText(attempt)
            )
        } else {
            historyTextRes(message, viewerCloudId, now)?.let { MessageTypeContent.CallHistoryText(it) }
        }
    }

    /**
     * The rows of a conversation that are shown. In direct conversations technical call events are dropped when
     * [hidesCallEvents], and of the call_result rows of one attempt only the newest revision remains; a deleted or
     * expired row of an attempt hides all its results so that an older revision never resurfaces.
     */
    fun visibleMessages(
        messages: List<ChatMessage>,
        isDirectRoom: Boolean,
        hidesCallEvents: Boolean,
        now: Long
    ): List<ChatMessage> {
        if (!isDirectRoom) {
            return messages
        }
        val hiddenResultIds = hiddenResultIds(messages, now)
        return messages.filterNot { message ->
            (hidesCallEvents && isHiddenCallEvent(message.systemMessageType)) ||
                message.jsonMessageId in hiddenResultIds
        }
    }

    private class AttemptRow(val message: ChatMessage, val attempt: CallAttempt) {
        val isResult: Boolean
            get() = message.systemMessageType == SystemMessageType.CALL_RESULT
    }

    private fun hiddenResultIds(messages: List<ChatMessage>, now: Long): Set<Int> =
        messages
            .mapNotNull { message -> message.callAttemptSnapshot?.let { AttemptRow(message, it) } }
            .groupBy { it.attempt.origin to it.attempt.callId }
            .values
            .flatMap { rows -> rows.filter { row -> row.isResult && isHiddenResult(row, rows, now) } }
            .mapTo(HashSet()) { it.message.jsonMessageId }

    private fun isHiddenResult(row: AttemptRow, rows: List<AttemptRow>, now: Long): Boolean =
        isRemoved(row.message, now) || rows.any { other -> other !== row && replaces(other, row, now) }

    private fun replaces(other: AttemptRow, row: AttemptRow, now: Long): Boolean {
        val isNewerResult = other.isResult &&
            compareValuesBy(other, row, { it.attempt.version }, { it.message.jsonMessageId }) > 0
        return isRemoved(other.message, now) || isNewerResult
    }

    /**
     * Whether a stored call history row must stay unchanged when [incoming] arrives for it: a deleted or expired
     * result never comes back, and a stale revision never replaces a newer one of the same attempt.
     */
    fun keepsStored(stored: ChatMessageEntity, incoming: ChatMessageEntity, now: Long): Boolean =
        incoming.systemMessageType == SystemMessageType.CALL_RESULT &&
            (
                isDeleted(stored) ||
                    isExpired(stored.expirationTimestamp, now) ||
                    isNewerRevision(stored.callAttempt, incoming.callAttempt)
                )

    private fun isNewerRevision(stored: String?, incoming: String?): Boolean {
        val storedAttempt = CallAttempt.parse(stored)
        val incomingAttempt = CallAttempt.parse(incoming)
        return storedAttempt != null &&
            incomingAttempt != null &&
            storedAttempt.callId == incomingAttempt.callId &&
            storedAttempt.origin == incomingAttempt.origin &&
            storedAttempt.version > incomingAttempt.version
    }

    /**
     * Whether persisting [incoming] depends on the stored row of the same message, see [merge].
     */
    fun needsStoredRow(incoming: ChatMessageEntity): Boolean =
        incoming.systemMessageType == SystemMessageType.CALL_RESULT ||
            (incoming.callAttempt == null && isDeleted(incoming))

    /**
     * The row to persist when [incoming] arrives for a message stored as [stored]. A deletion tombstone or a result
     * without metadata keeps the stored attempt, so the attempt identity survives for deduplication.
     */
    fun merge(stored: ChatMessageEntity?, incoming: ChatMessageEntity, now: Long): ChatMessageEntity =
        when {
            stored == null -> incoming
            keepsStored(stored, incoming, now) -> stored
            incoming.callAttempt == null && stored.callAttempt != null && needsStoredRow(incoming) ->
                incoming.copy(callAttempt = stored.callAttempt)
            else -> incoming
        }
}
