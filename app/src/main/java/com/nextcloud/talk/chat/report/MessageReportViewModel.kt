/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.report

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.talk.api.NcApiCoroutines
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.message.SendMessageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sends message reports to the one-to-one conversation with the moderation account on the server of the reporting
 * user. Lives longer than the chat screen, so that a report is neither lost nor sent twice when it is recreated.
 */
class MessageReportViewModel @Inject constructor(private val ncApiCoroutines: NcApiCoroutines) : ViewModel() {

    sealed interface Result {
        data object Sent : Result
        data class Failed(val report: MessageReport) : Result
    }

    private val resultChannel = Channel<Result>(Channel.BUFFERED)
    val results: Flow<Result> = resultChannel.receiveAsFlow()

    private var reportJob: Job? = null

    /**
     * Returns false, without sending anything, while a previous report is still being sent.
     */
    fun sendToModeration(user: User, moderationUserId: String, chatApiVersion: Int, report: MessageReport): Boolean {
        if (reportJob?.isActive == true) {
            return false
        }
        reportJob = viewModelScope.launch {
            val sent = trySendToModeration(user, moderationUserId, chatApiVersion, report)
            resultChannel.send(if (sent) Result.Sent else Result.Failed(report))
        }
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun trySendToModeration(
        user: User,
        moderationUserId: String,
        chatApiVersion: Int,
        report: MessageReport
    ): Boolean {
        val credentials = ApiUtils.getCredentials(user.username, user.token) ?: return false
        return try {
            val conversationApiVersion =
                ApiUtils.getConversationApiVersion(user, intArrayOf(ApiUtils.API_V4, ApiUtils.API_V1))
            val retrofitBucket = ApiUtils.getRetrofitBucketForCreateRoom(
                version = conversationApiVersion,
                roomType = ROOM_TYPE_ONE_TO_ONE,
                baseUrl = user.baseUrl,
                invite = moderationUserId
            )
            val moderationRoomToken = ncApiCoroutines
                .createRoom(credentials, retrofitBucket.url, retrofitBucket.queryMap)
                .ocs?.data?.token
            if (moderationRoomToken.isNullOrEmpty()) {
                false
            } else {
                ncApiCoroutines.sendChatMessage(
                    credentials,
                    ApiUtils.getUrlForChat(chatApiVersion, user.baseUrl, moderationRoomToken),
                    report.toText(),
                    user.displayName.orEmpty(),
                    0,
                    false,
                    SendMessageUtils().generateReferenceId(),
                    null
                )
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The report itself and the conversation token must not end up in the logs
            Log.w(TAG, "Sending a message report to the moderators failed: ${e.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private val TAG = MessageReportViewModel::class.java.simpleName
        private const val ROOM_TYPE_ONE_TO_ONE = "1"
    }
}
