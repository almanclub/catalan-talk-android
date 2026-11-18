/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.forward

import android.util.Log
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID

private const val TAG = "ReadOnlySharer"

/**
 * A file uploaded from the device to a conversation, which replaces the placeholder message [referenceId].
 *
 * @property notice the message telling about the read-only shared file
 * @property talkMetaData the caption and the message the upload replies to
 */
data class ReadOnlyUpload(val fileName: String, val notice: String, val talkMetaData: String?, val referenceId: String?)

/**
 * Shares files read-only with users who can not open the attachments of a conversation. Each file gets its own new
 * folder in [FORWARD_FOLDER]. The folders are kept: deleting them would remove the shares.
 */
class ReadOnlySharer(
    private val api: ForwardApi,
    private val newUuid: () -> String = { UUID.randomUUID().toString() }
) {

    /**
     * The members to share an upload with read-only, or null if it can be attached to the conversation because
     * another member can open its attachments, or because nobody else is in it.
     */
    suspend fun uploadRecipients(
        ownUserId: String,
        ownCloudId: String,
        isHostedElsewhere: Boolean
    ): List<ReadOnlyRecipient>? {
        val recipients = forwardRecipients(api.roomMembers(), ownUserId, ownCloudId, isHostedElsewhere)
        return recipients.readOnly.takeUnless { recipients.usesAttachment || it.isEmpty() }
    }

    /**
     * Shares the [upload] that [place] puts at the path it gets with [recipients] and tells about it in a message.
     * Throws if the file could not be placed.
     *
     * @return the recipients the file could not be shared with, or null if nobody got it or nobody was told about it
     */
    suspend fun shareUpload(
        upload: ReadOnlyUpload,
        recipients: List<ReadOnlyRecipient>,
        place: suspend (path: String) -> Unit
    ): List<String>? {
        val unreached = share(upload.fileName, recipients, place)
        if (unreached.size == recipients.size) {
            Log.e(TAG, "The file could not be shared with anyone")
            return null
        }
        val options = uploadMessageOptions(upload.talkMetaData)
        val told = attempt(TAG, "Could not tell about the read-only shared file") {
            api.sendMessage(
                readOnlyShareMessage(options.caption, upload.notice),
                silent = false,
                referenceId = upload.referenceId?.takeIf { it.isNotEmpty() },
                replyTo = options.replyTo
            )
        }
        return unreached.takeIf { told != null }
    }

    /**
     * Shares the file [fileName] with each of [recipients], once [place] put it at the path it gets. Throws if the
     * file could not be placed.
     *
     * @return the recipients the file could not be shared with
     */
    suspend fun share(
        fileName: String,
        recipients: List<ReadOnlyRecipient>,
        place: suspend (path: String) -> Unit
    ): List<String> {
        api.createFolder("/$FORWARD_FOLDER")
        val folder = "/$FORWARD_FOLDER/${newUuid()}"
        requireSuccess(api.createFolder(folder))
        val path = "$folder/$fileName"
        place(path)
        return recipients
            .filter { attempt(TAG, "Could not share a file read-only") { api.shareReadOnly(path, it) } == null }
            .map { it.shareWith }
    }

    companion object {
        const val FORWARD_FOLDER = "Talk-forwarded"
    }
}

/**
 * The message telling the recipients of a read-only shared file about it, below its [caption].
 */
fun readOnlyShareMessage(caption: String?, notice: String): String = listOfNotNull(caption, notice).joinToString("\n")

/**
 * The caption of an upload and the message it replies to, 0 for none.
 */
data class UploadMessageOptions(val caption: String?, val replyTo: Int)

/**
 * The [UploadMessageOptions] in the talkMetaData of an upload.
 */
fun uploadMessageOptions(talkMetaData: String?): UploadMessageOptions {
    val metaData = try {
        talkMetaData?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: JsonParseException) {
        Log.w(TAG, "Invalid talkMetaData", e)
        null
    }
    fun string(key: String) = metaData?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
    return UploadMessageOptions(
        caption = string("caption")?.takeIf { it.isNotEmpty() },
        replyTo = string("replyTo")?.toIntOrNull() ?: 0
    )
}

/**
 * The result of [block], or null if it failed.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> attempt(tag: String?, failure: String, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, failure, e)
        null
    }

internal fun requireSuccess(status: Int) {
    if (status !in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
        throw IOException("Server answered $status")
    }
}
