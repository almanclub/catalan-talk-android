/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.forward

import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.api.NcApiCoroutines
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.json.chatpostattachment.PostConversationAttachmentRequestDto
import com.nextcloud.talk.models.json.chatprobeattachmentfolder.ProbeConversationAttachmentRequestDto
import com.nextcloud.talk.models.json.converters.EnumActorTypeConverter
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.RemoteFileUtils
import com.nextcloud.talk.utils.message.SendMessageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class NcForwardApi(
    private val ncApi: NcApi,
    private val ncApiCoroutines: NcApiCoroutines,
    private val user: User,
    private val roomToken: String
) : ForwardApi {
    private val credentials = ApiUtils.getCredentials(user.username, user.token).orEmpty()
    private val baseUrl = user.baseUrl!!

    override suspend fun roomMembers(): List<RoomMember> {
        val apiVersion = ApiUtils.getConversationApiVersion(user, intArrayOf(ApiUtils.API_V4, 1))
        val participants = ncApiCoroutines.getParticipants(
            credentials,
            ApiUtils.getUrlForParticipants(apiVersion, baseUrl, roomToken)
        ).ocs?.data ?: throw IOException("No participants in response")
        val actorTypeConverter = EnumActorTypeConverter()
        return participants.map {
            RoomMember(actorTypeConverter.convertToString(it.calculatedActorType), it.calculatedActorId.orEmpty())
        }
    }

    override suspend fun probeAttachmentFolder(fileName: String): AttachmentDraft {
        val request = ProbeConversationAttachmentRequestDto().apply { fileNames = listOf(fileName) }
        val data = ncApiCoroutines.probeConversationAttachmentFolder(
            credentials,
            ApiUtils.getUrlForChatAttachmentFolder(ApiUtils.API_V1, baseUrl, roomToken),
            request
        ).ocs?.data
        val folder = data?.folder
        if (data == null || folder.isNullOrEmpty()) {
            throw IOException("Draft folder missing in probe response")
        }
        return AttachmentDraft(folder, data.renames?.get(fileName) ?: fileName)
    }

    override suspend fun postAttachment(
        draftPath: String,
        fileName: String,
        referenceId: String,
        talkMetaData: String
    ) {
        val request = PostConversationAttachmentRequestDto().apply {
            filePath = draftPath
            this.fileName = fileName
            this.referenceId = referenceId
            this.talkMetaData = talkMetaData
        }
        ncApiCoroutines.postConversationAttachment(
            credentials,
            ApiUtils.getUrlForChatAttachment(ApiUtils.API_V1, baseUrl, roomToken),
            request
        )
    }

    override suspend fun freePath(path: String): String =
        withContext(Dispatchers.IO) { RemoteFileUtils.getNewPathIfFileExists(ncApi, user, path) }

    override suspend fun copy(sourcePath: String, destinationPath: String): Int =
        ncApiCoroutines.copyFile(
            credentials,
            webDavUrl(sourcePath),
            webDavUrl(destinationPath),
            DO_NOT_OVERWRITE
        ).code()

    override suspend fun createFolder(path: String): Int =
        ncApiCoroutines.createFolder(credentials, webDavUrl(path)).code()

    override suspend fun shareWithConversation(path: String, referenceId: String, talkMetaData: String) {
        ncApiCoroutines.createShare(
            credentials,
            ApiUtils.getSharingUrl(baseUrl),
            path,
            SHARE_TYPE_ROOM,
            roomToken,
            null,
            talkMetaData,
            referenceId
        )
    }

    override suspend fun shareReadOnly(path: String, recipient: ReadOnlyRecipient) {
        ncApiCoroutines.createShare(
            credentials,
            ApiUtils.getSharingUrl(baseUrl),
            path,
            recipient.shareType,
            recipient.shareWith,
            SHARE_PERMISSION_READ,
            null,
            null
        )
    }

    override suspend fun sendMessage(message: String, silent: Boolean) {
        val chatApiVersion = ApiUtils.getChatApiVersion(user.capabilities!!.spreedCapability!!, intArrayOf(1))
        ncApiCoroutines.sendChatMessage(
            credentials,
            ApiUtils.getUrlForChat(chatApiVersion, baseUrl, roomToken),
            message,
            user.displayName.orEmpty(),
            0,
            silent,
            SendMessageUtils().generateReferenceId(),
            null
        )
    }

    private fun webDavUrl(path: String) = ApiUtils.getUrlForFileUpload(baseUrl, user.userId!!, path)

    companion object {
        private const val DO_NOT_OVERWRITE = "F"
    }
}
