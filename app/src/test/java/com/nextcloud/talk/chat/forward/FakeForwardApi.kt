/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.forward

import java.io.IOException

internal class FakeForwardApi(
    private val members: List<RoomMember>?,
    private val copyStatuses: MutableMap<String, MutableList<Int>> = mutableMapOf(),
    private val failingRecipients: Set<String> = emptySet(),
    private val folderStatuses: Map<String, Int> = emptyMap(),
    private val messageFails: Boolean = false
) : ForwardApi {
    val calls = mutableListOf<String>()
    val referenceIds = mutableListOf<String>()

    override suspend fun roomMembers(): List<RoomMember> = members ?: throw IOException("offline")

    override suspend fun probeAttachmentFolder(fileName: String): AttachmentDraft {
        calls += "probe $fileName"
        return AttachmentDraft("Talk/Room-abc/Draft", "renamed-$fileName")
    }

    override suspend fun postAttachment(
        draftPath: String,
        fileName: String,
        referenceId: String,
        talkMetaData: String
    ) {
        calls += "post $draftPath as $fileName $talkMetaData"
        referenceIds += referenceId
    }

    override suspend fun freePath(path: String): String = path

    override suspend fun copy(sourcePath: String, destinationPath: String): Int {
        calls += "copy $sourcePath to $destinationPath"
        return copyStatuses[sourcePath]?.removeFirstOrNull() ?: HTTP_CREATED
    }

    override suspend fun createFolder(path: String): Int {
        calls += "mkcol $path"
        return folderStatuses[path] ?: HTTP_CREATED
    }

    override suspend fun shareWithConversation(path: String, referenceId: String, talkMetaData: String) {
        calls += "share $path with conversation $talkMetaData"
        referenceIds += referenceId
    }

    override suspend fun shareReadOnly(path: String, recipient: ReadOnlyRecipient) {
        if (recipient.shareWith in failingRecipients) {
            throw IOException("share failed")
        }
        calls += "share $path read-only with ${recipient.shareType}:${recipient.shareWith}"
    }

    override suspend fun sendMessage(message: String, silent: Boolean, referenceId: String?, replyTo: Int) {
        if (messageFails) {
            throw IOException("message failed")
        }
        calls += "message \"$message\" silent=$silent" +
            referenceId?.let { " ref=$it" }.orEmpty() +
            " replyTo=$replyTo".takeIf { replyTo != 0 }.orEmpty()
    }

    companion object {
        private const val HTTP_CREATED = 201
    }
}
