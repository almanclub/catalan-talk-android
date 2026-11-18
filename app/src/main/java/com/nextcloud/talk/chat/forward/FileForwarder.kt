/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.forward

import com.nextcloud.talk.utils.message.SendMessageUtils
import java.util.UUID

/**
 * The server calls of a forward to one conversation. Paths are relative to the user's files and start with "/".
 * Calls returning nothing throw if they fail.
 */
interface ForwardApi {
    suspend fun roomMembers(): List<RoomMember>

    suspend fun probeAttachmentFolder(fileName: String): AttachmentDraft

    suspend fun postAttachment(draftPath: String, fileName: String, referenceId: String, talkMetaData: String)

    /** [path], or the path with a " (n)" suffix if a file exists there already. */
    suspend fun freePath(path: String): String

    /** The HTTP status of copying [sourcePath] to [destinationPath] without overwriting. */
    suspend fun copy(sourcePath: String, destinationPath: String): Int

    /** The HTTP status of creating the folder [path]. */
    suspend fun createFolder(path: String): Int

    suspend fun shareWithConversation(path: String, referenceId: String, talkMetaData: String)

    suspend fun shareReadOnly(path: String, recipient: ReadOnlyRecipient)

    /** Sends [message], with [referenceId] or a new one, as reply to the message [replyTo] unless it is 0. */
    suspend fun sendMessage(message: String, silent: Boolean, referenceId: String? = null, replyTo: Int = 0)
}

/** The draft [folder] of a conversation and the [fileName] a file gets in the folder of the conversation. */
data class AttachmentDraft(val folder: String, val fileName: String)

data class ForwardSettings(
    val ownUserId: String,
    val ownCloudId: String,
    val isHostedElsewhere: Boolean,
    val usesConversationSubfolders: Boolean,
    val attachmentFolder: String
)

data class ForwardResult(val failedFiles: List<String>, val unreachedRecipients: List<String>)

/**
 * Forwards files of the user's storage to a conversation by copying them on the server, as one upload batch. The files
 * are forwarded one after the other, a failing file does not stop the others.
 */
class FileForwarder(
    private val api: ForwardApi,
    private val settings: ForwardSettings,
    private val readOnlyNotice: (fileName: String) -> String,
    private val newUuid: () -> String = { UUID.randomUUID().toString() }
) {
    suspend fun forward(files: List<ForwardedFile>, silent: Boolean): ForwardResult {
        val recipients = attempt(TAG, "Could not load the members of the conversation") {
            forwardRecipients(api.roomMembers(), settings.ownUserId, settings.ownCloudId, settings.isHostedElsewhere)
        } ?: return ForwardResult(files.map { it.name }, emptyList())

        val uploadId = newUuid()
        val failedFiles = mutableListOf<String>()
        val unreachedRecipients = linkedSetOf<String>()
        files.forEachIndexed { index, file ->
            val referenceId = SendMessageUtils().generateGroupedReferenceId(uploadId, index + 1)
            val isForwarded = attempt(TAG, "Could not forward a file") {
                forwardFile(file, referenceId, recipients, silent, unreachedRecipients)
            }
            if (isForwarded != true) {
                failedFiles += file.name
            }
        }
        return ForwardResult(failedFiles, unreachedRecipients.toList())
    }

    private suspend fun forwardFile(
        file: ForwardedFile,
        referenceId: String,
        recipients: ForwardRecipients,
        silent: Boolean,
        unreachedRecipients: MutableSet<String>
    ): Boolean {
        if (recipients.usesAttachment) {
            attach(file, referenceId, forwardTalkMetaData(file, silent))
        }
        if (recipients.readOnly.isEmpty()) {
            return recipients.usesAttachment
        }
        val isSharedWithAnyone = shareReadOnly(file, recipients.readOnly, unreachedRecipients)
        if (!recipients.usesAttachment && isSharedWithAnyone) {
            api.sendMessage(readOnlyShareMessage(file.caption, readOnlyNotice(file.name)), silent)
        }
        return recipients.usesAttachment || isSharedWithAnyone
    }

    private suspend fun attach(file: ForwardedFile, referenceId: String, talkMetaData: String) {
        if (settings.usesConversationSubfolders) {
            val draft = api.probeAttachmentFolder(file.name)
            val draftPath = "/${draft.folder.trim('/')}/${newUuid()}-${file.name}"
            requireSuccess(api.copy(file.path, draftPath))
            api.postAttachment(draftPath, draft.fileName, referenceId, talkMetaData)
        } else {
            val folder = "/" + settings.attachmentFolder.trim('/')
            val path = api.freePath("${folder.trimEnd('/')}/${file.name}")
            var status = api.copy(file.path, path)
            if (status == HTTP_NOT_FOUND || status == HTTP_CONFLICT) {
                api.createFolder(folder)
                status = api.copy(file.path, path)
            }
            requireSuccess(status)
            api.shareWithConversation(path, referenceId, talkMetaData)
        }
    }

    private suspend fun shareReadOnly(
        file: ForwardedFile,
        recipients: List<ReadOnlyRecipient>,
        unreachedRecipients: MutableSet<String>
    ): Boolean {
        val unreached = attempt(TAG, "Could not copy a file to share it read-only") {
            ReadOnlySharer(api, newUuid).share(file.name, recipients) { requireSuccess(api.copy(file.path, it)) }
        } ?: recipients.map { it.shareWith }
        unreachedRecipients += unreached
        return unreached.size < recipients.size
    }

    companion object {
        private val TAG = FileForwarder::class.simpleName
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
    }
}
