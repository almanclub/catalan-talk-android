/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import autodagger.AutoInjector
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.directcall.CallAttempt
import com.nextcloud.talk.directcall.DirectCallManager
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CALL_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_NOTIFICATION_TIMESTAMP
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class DeclineCallReceiver : BroadcastReceiver() {

    @Inject
    lateinit var directCallManager: DirectCallManager

    init {
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(KEY_NOTIFICATION_TIMESTAMP, 0)
        NotificationManagerCompat.from(context).cancel(notificationId)
        val callId = intent.getStringExtra(KEY_CALL_ID)?.takeIf { CallAttempt.isCallId(it) }
        val token = intent.getStringExtra(KEY_ROOM_TOKEN)
        val accountId = intent.getLongExtra(KEY_INTERNAL_USER_ID, 0L)
        if (callId != null && token != null) {
            rejectDirectCall(accountId, token, callId)
        }
    }

    /**
     * Rejects the call attempt; the reject is retried until the server answered it, so the process is only kept
     * alive for a while to send it right away.
     */
    private fun rejectDirectCall(accountId: Long, token: String, callId: String) {
        val pendingResult = goAsync()
        directCallManager.scope.launch {
            try {
                val session = directCallManager.incoming(accountId, token, callId)
                session.finishByUser()
                withTimeoutOrNull(REJECT_TIMEOUT_MS) {
                    directCallManager.changes
                        .onStart { emit(session) }
                        .filter { it === session && it.isTerminal }
                        .first()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val REJECT_TIMEOUT_MS = 8000L
    }
}
