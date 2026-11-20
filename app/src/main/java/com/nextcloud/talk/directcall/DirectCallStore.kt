/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A business action (cancel, reject, end, cancel-request) that is retried until the server answered it.
 */
@Serializable
data class PendingCallAction(val accountId: Long, val token: String, val path: String, val parameters: JsonObject)

/**
 * A call attempt that was not seen ending, reconciled read-only after the process was restarted.
 */
@Serializable
data class UnfinishedCall(
    val accountId: Long,
    val token: String,
    val initiator: Boolean,
    val clientRequestId: String,
    val callId: String? = null
)

/**
 * Persists the pending actions and unfinished calls of [DirectCallManager] as JSON strings.
 */
class DirectCallStore(private val read: (String) -> String?, private val write: (String, String) -> Unit) {

    constructor(preferences: SharedPreferences) : this(
        { key -> preferences.getString(key, null) },
        { key, value -> preferences.edit().putString(key, value).apply() }
    )

    fun pendingActions(): Map<String, PendingCallAction> = load(PENDING_KEY, PENDING_SERIALIZER)

    fun putPendingAction(id: String, action: PendingCallAction) =
        save(PENDING_KEY, PENDING_SERIALIZER, pendingActions() + (id to action))

    fun removePendingAction(id: String) = save(PENDING_KEY, PENDING_SERIALIZER, pendingActions() - id)

    fun unfinishedCalls(): Map<String, UnfinishedCall> = load(UNFINISHED_KEY, UNFINISHED_SERIALIZER)

    fun putUnfinishedCall(call: UnfinishedCall) {
        val calls = unfinishedCalls()
        if (calls[call.clientRequestId] != call) {
            save(UNFINISHED_KEY, UNFINISHED_SERIALIZER, calls + (call.clientRequestId to call))
        }
    }

    fun removeUnfinishedCall(clientRequestId: String) {
        val calls = unfinishedCalls()
        if (clientRequestId in calls) {
            save(UNFINISHED_KEY, UNFINISHED_SERIALIZER, calls - clientRequestId)
        }
    }

    private fun <T> load(key: String, serializer: KSerializer<Map<String, T>>): Map<String, T> {
        val json = read(key) ?: return emptyMap()
        return try {
            JSON.decodeFromString(serializer, json)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping invalid $key", e)
            emptyMap()
        }
    }

    private fun <T> save(key: String, serializer: KSerializer<Map<String, T>>, value: Map<String, T>) =
        write(key, JSON.encodeToString(serializer, value))

    companion object {
        private val TAG = DirectCallStore::class.java.simpleName
        private const val PENDING_KEY = "pendingDirectCallActions"
        private const val UNFINISHED_KEY = "unfinishedDirectCalls"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val PENDING_SERIALIZER = MapSerializer(String.serializer(), PendingCallAction.serializer())
        private val UNFINISHED_SERIALIZER = MapSerializer(String.serializer(), UnfinishedCall.serializer())
    }
}
