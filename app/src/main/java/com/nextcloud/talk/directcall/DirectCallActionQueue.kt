/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Persistent queue of the business actions of [DirectCallSession.finishByUser]. An action is retried until the
 * server answered it, also after the process was restarted.
 */
internal class DirectCallActionQueue(
    private val backend: DirectCallBackend,
    private val store: DirectCallStore,
    private val scope: CoroutineScope,
    private val sessions: () -> List<DirectCallSession>
) {
    private val sending = mutableSetOf<String>()

    fun start() {
        scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                retry()
            }
        }
    }

    fun enqueue(session: DirectCallSession, path: String, parameters: JsonObject) {
        val id = listOf(session.accountId, session.token, path, session.clientRequestId).joinToString("|")
        store.putPendingAction(id, PendingCallAction(session.accountId, session.token, path, parameters))
        retry()
    }

    fun retry() {
        store.pendingActions().filterKeys { it !in sending }.forEach { (id, action) -> send(id, action) }
    }

    private fun send(id: String, action: PendingCallAction) {
        val needsSession = action.path.endsWith("/reject") && SESSION_ID !in action.parameters
        // A reject never joins media; acquiring a session waits while another attempt owns the room.
        if (needsSession && isOwnedByOtherAttempt(action)) {
            return
        }
        sending += id
        scope.launch {
            try {
                when {
                    !backend.hasAccount(action.accountId) -> store.removePendingAction(id)
                    needsSession -> acquireSession(id, action)
                    else -> post(id, action)
                }
            } finally {
                sending -= id
            }
        }
    }

    private fun isOwnedByOtherAttempt(action: PendingCallAction): Boolean =
        sessions().firstOrNull { it.matches(action) && it.attachedToUI }
            ?.callId
            ?.let { !action.path.startsWith("/$it/") } == true

    private suspend fun acquireSession(id: String, action: PendingCallAction) {
        val sessionId = try {
            backend.joinRoom(action.accountId, action.token)
        } catch (e: CallAttemptException) {
            Log.w(TAG, "Could not acquire rejection session; status ${e.statusCode}", e)
            if (e.statusCode in DROPPED_SESSION_STATUSES) store.removePendingAction(id)
            return
        }
        val updated = action.copy(parameters = buildJsonObject { put(SESSION_ID, sessionId) })
        store.putPendingAction(id, updated)
        post(id, updated)
    }

    private suspend fun post(id: String, action: PendingCallAction) {
        val attempt = try {
            backend.request(action.accountId, action.token, action.path, action.parameters)
        } catch (e: CallAttemptException) {
            failed(id, action, e)
            return
        }
        store.removePendingAction(id)
        val requestId = (action.parameters[CLIENT_REQUEST_ID] as? JsonPrimitive)?.contentOrNull
        val matching = sessions().filter { it.matches(action) }
        if (attempt != null) {
            matching.filter { it.callId == attempt.callId || it.clientRequestId == requestId }
                .forEach { it.apply(attempt) }
        } else if (action.path == CallAttemptResponses.CANCEL_REQUEST) {
            matching.filter { it.clientRequestId == requestId && it.callId == null }.forEach { it.resolveAbsent() }
        }
    }

    private fun failed(id: String, action: PendingCallAction, error: CallAttemptException) {
        Log.w(TAG, "Pending call action failed; status ${error.statusCode}, error ${error.errorKey}", error)
        if (error.isOcs && error.statusCode in DROPPED_STATUSES) {
            store.removePendingAction(id)
        }
        sessions().filter { session ->
            session.matches(action) && session.callId?.let { action.path.startsWith("/$it/") } == true
        }.forEach { session ->
            session.handle(error)
            if (action.path.endsWith("/cancel") && error.statusCode == CallAttemptException.HTTP_CONFLICT) {
                session.finishAfterCancelRace()
            }
            session.reconcile()
        }
    }

    private fun DirectCallSession.matches(action: PendingCallAction): Boolean =
        accountId == action.accountId && token == action.token

    companion object {
        private val TAG = DirectCallActionQueue::class.java.simpleName
        private const val RETRY_INTERVAL_MS = 5000L
        private const val SESSION_ID = "sessionId"
        private const val CLIENT_REQUEST_ID = "clientRequestId"
        private val DROPPED_STATUSES = setOf(400, 401, 403, 404, 409)
        private val DROPPED_SESSION_STATUSES = setOf(401, 403, 404)
    }
}
