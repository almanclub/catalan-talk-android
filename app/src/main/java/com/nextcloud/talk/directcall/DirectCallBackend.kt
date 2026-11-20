/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import com.nextcloud.talk.api.NcApiCoroutines
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException

/**
 * Server access of [DirectCallManager]. Every function throws [CallAttemptException] on failure.
 */
interface DirectCallBackend {
    suspend fun hasAccount(accountId: Long): Boolean

    /**
     * Sends a call-attempt request for [path] relative to the attempts of [token]; a GET when [body] is null.
     */
    suspend fun request(accountId: Long, token: String, path: String, body: JsonObject?): CallAttempt?

    /**
     * Joins [token] with a new session and returns its id.
     */
    suspend fun joinRoom(accountId: Long, token: String): String
}

class NcDirectCallBackend(private val api: NcApiCoroutines, private val userManager: UserManager) :
    DirectCallBackend {

    override suspend fun hasAccount(accountId: Long): Boolean = userManager.getUserWithId(accountId) != null

    override suspend fun request(accountId: Long, token: String, path: String, body: JsonObject?): CallAttempt? {
        val user = userManager.getUserWithId(accountId)
        val credentials = credentials(user)
        val url = ApiUtils.getUrlForCallAttempt(user?.baseUrl ?: throw CallAttemptException.unavailable(), token) +
            path
        try {
            val response = if (body == null) {
                api.getCallAttempt(credentials, url)
            } else {
                api.postCallAttempt(credentials, url, body.toString().toRequestBody(JSON))
            }
            val raw = if (response.isSuccessful) response.body()?.string() else response.errorBody()?.string()
            return CallAttemptResponses.parse(path, response.code(), response.headers()[RETRY_AFTER], raw)
        } catch (e: IOException) {
            throw CallAttemptException(0, cause = e)
        }
    }

    override suspend fun joinRoom(accountId: Long, token: String): String {
        val user = userManager.getUserWithId(accountId)
        val credentials = credentials(user)
        val url = ApiUtils.getUrlForParticipantsActive(ApiUtils.API_V4, user?.baseUrl, token)
        val sessionId = try {
            api.joinRoomWithNewSession(credentials, url, force = true).ocs?.data?.sessionId
        } catch (e: HttpException) {
            throw CallAttemptException(e.code(), cause = e)
        } catch (e: IOException) {
            throw CallAttemptException(0, cause = e)
        }
        return sessionId?.takeUnless { it.isEmpty() || it == NO_SESSION }
            ?: throw CallAttemptException.invalidResponse()
    }

    private fun credentials(user: User?): String =
        ApiUtils.getCredentials(user?.username, user?.token) ?: throw CallAttemptException.unavailable()

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val RETRY_AFTER = "Retry-After"
        private const val NO_SESSION = "0"
    }
}
