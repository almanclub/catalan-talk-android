/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.api.NcApiCoroutines
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.chat.forward.FileForwarder
import com.nextcloud.talk.chat.forward.ForwardResult
import com.nextcloud.talk.chat.forward.ForwardSettings
import com.nextcloud.talk.chat.forward.ForwardedFile
import com.nextcloud.talk.chat.forward.NcForwardApi
import com.nextcloud.talk.chat.forward.toForwardedFile
import com.nextcloud.talk.data.database.dao.ChatMessagesDao
import com.nextcloud.talk.data.database.mappers.toDomainModel
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.CapabilitiesUtil
import com.nextcloud.talk.utils.CloudIdUtils
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.SpreedFeatures
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.setExpeditedIfSupported
import javax.inject.Inject

/**
 * Forwards file messages of one conversation to another one by copying the files on the server. The work is not
 * retried, as a retry could forward files twice; failures are reported with a notification.
 */
@AutoInjector(NextcloudTalkApplication::class)
class ForwardFilesWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var chatDao: ChatMessagesDao

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var ncApiCoroutines: NcApiCoroutines

    override suspend fun doWork(): Result {
        if (!::chatDao.isInitialized) {
            sharedApplication!!.componentApplication.inject(this)
        }
        val messageIds = inputData.getLongArray(KEY_MESSAGE_IDS) ?: LongArray(0)
        val fileNames = inputData.getNullableStringArray(KEY_FILE_NAMES)?.map { it.orEmpty() }.orEmpty()
        val sourceToken = inputData.getString(KEY_SOURCE_ROOM_TOKEN).orEmpty()
        val targetToken = inputData.getString(KEY_TARGET_ROOM_TOKEN).orEmpty()
        val targetName = inputData.getString(KEY_TARGET_ROOM_NAME).orEmpty()
        val user = userManager.getUserWithId(inputData.getLong(KEY_INTERNAL_USER_ID, -1))
        val spreedCapability = user?.capabilities?.spreedCapability

        if (user?.userId == null || user.baseUrl == null || spreedCapability == null || targetToken.isEmpty()) {
            Log.e(TAG, "Missing user or conversation, files are not forwarded")
            showFailureNotification(targetName, ForwardResult(fileNames.toList(), emptyList()))
            return Result.failure()
        }

        val withCaption = CapabilitiesUtil.hasSpreedFeatureCapability(spreedCapability, SpreedFeatures.MEDIA_CAPTION)
        val files = mutableListOf<ForwardedFile>()
        val missingFiles = mutableListOf<String>()
        messageIds.forEachIndexed { index, id ->
            val file = chatDao.getChatMessageEntity("${user.id}@$sourceToken", id)
                ?.toDomainModel()
                ?.toForwardedFile(withCaption)
            if (file != null) files += file else missingFiles += fileNames.getOrElse(index) { id.toString() }
        }

        val forwarder = FileForwarder(
            NcForwardApi(ncApi, ncApiCoroutines, user, targetToken),
            forwardSettings(user, CapabilitiesUtil.getAttachmentFolder(spreedCapability)),
            readOnlyNotice = { applicationContext.getString(R.string.nc_forward_file_read_only_notice, it) }
        )
        val result = forwarder.forward(files, inputData.getBoolean(KEY_SILENT, false)).let {
            it.copy(failedFiles = missingFiles + it.failedFiles)
        }

        return if (result.failedFiles.isEmpty() && result.unreachedRecipients.isEmpty()) {
            Result.success()
        } else {
            showFailureNotification(targetName, result)
            Result.failure()
        }
    }

    private fun forwardSettings(user: User, attachmentFolder: String) =
        ForwardSettings(
            ownUserId = user.userId!!,
            ownCloudId = CloudIdUtils.ownCloudId(user.userId!!, user.baseUrl!!),
            isHostedElsewhere = inputData.getBoolean(KEY_TARGET_HOSTED_ELSEWHERE, false),
            usesConversationSubfolders = CapabilitiesUtil.hasConversationSubfoldersForAttachments(
                user.capabilities!!.spreedCapability!!
            ),
            attachmentFolder = attachmentFolder
        )

    private fun showFailureNotification(targetName: String, result: ForwardResult) {
        val details = listOfNotNull(
            result.failedFiles.takeIf { it.isNotEmpty() }?.let {
                applicationContext.getString(R.string.nc_forward_files_failed_text, it.joinToString())
            },
            result.unreachedRecipients.takeIf { it.isNotEmpty() }?.let {
                applicationContext.getString(R.string.nc_forward_files_not_shared_with, it.joinToString())
            }
        ).joinToString("\n").ifEmpty { applicationContext.getString(R.string.nc_common_error_sorry) }

        val notification = NotificationCompat.Builder(
            applicationContext,
            NotificationUtils.NotificationChannels.NOTIFICATION_CHANNEL_UPLOADS.name
        )
            .setContentTitle(applicationContext.getString(R.string.nc_forward_files_failed_title, targetName))
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setSmallIcon(R.drawable.baseline_error_24)
            .setGroup(NotificationUtils.KEY_UPLOAD_GROUP)
            .build()
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(SystemClock.uptimeMillis().toInt(), notification)
    }

    companion object {
        private val TAG = ForwardFilesWorker::class.simpleName
        private const val KEY_SOURCE_ROOM_TOKEN = "SOURCE_ROOM_TOKEN"
        private const val KEY_MESSAGE_IDS = "MESSAGE_IDS"
        private const val KEY_FILE_NAMES = "FILE_NAMES"
        private const val KEY_TARGET_ROOM_TOKEN = "TARGET_ROOM_TOKEN"
        private const val KEY_TARGET_ROOM_NAME = "TARGET_ROOM_NAME"
        private const val KEY_TARGET_HOSTED_ELSEWHERE = "TARGET_HOSTED_ELSEWHERE"
        private const val KEY_SILENT = "SILENT"

        @Suppress("LongParameterList")
        fun enqueue(
            context: Context,
            userId: Long,
            sourceRoomToken: String,
            messageIds: LongArray,
            fileNames: List<String>,
            targetRoomToken: String,
            targetRoomName: String,
            isTargetHostedElsewhere: Boolean,
            silent: Boolean
        ) {
            val data = Data.Builder()
                .putLong(KEY_INTERNAL_USER_ID, userId)
                .putString(KEY_SOURCE_ROOM_TOKEN, sourceRoomToken)
                .putLongArray(KEY_MESSAGE_IDS, messageIds)
                .putStringArray(KEY_FILE_NAMES, fileNames.toTypedArray())
                .putString(KEY_TARGET_ROOM_TOKEN, targetRoomToken)
                .putString(KEY_TARGET_ROOM_NAME, targetRoomName)
                .putBoolean(KEY_TARGET_HOSTED_ELSEWHERE, isTargetHostedElsewhere)
                .putBoolean(KEY_SILENT, silent)
                .build()
            val request = OneTimeWorkRequest.Builder(ForwardFilesWorker::class.java)
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpeditedIfSupported()
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
