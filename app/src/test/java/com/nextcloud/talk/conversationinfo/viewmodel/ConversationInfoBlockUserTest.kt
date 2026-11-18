/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.conversationinfo.viewmodel

import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.blockedusers.ACTOR_TYPE_USERS
import com.nextcloud.talk.blockedusers.BlockedActor
import com.nextcloud.talk.blockedusers.BlockedUsersRepository
import com.nextcloud.talk.chat.data.network.ChatNetworkDataSource
import com.nextcloud.talk.data.database.dao.BlockedUsersDao
import com.nextcloud.talk.data.database.model.BlockedUserEntity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.logger.Logger
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.conversations.ConversationDto
import com.nextcloud.talk.models.json.conversations.ConversationEnums.ConversationType
import com.nextcloud.talk.repositories.passwordpolicy.PasswordPolicyRepository
import com.nextcloud.talk.utils.preferences.preferencestorage.DatabaseStorageModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Blocking the other user of a 1-1 conversation from the conversation info. The user has no capabilities here,
 * so only the room and its blocked state are loaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationInfoBlockUserTest {

    private val dispatcher = StandardTestDispatcher()

    private val blockedUsersDao = FakeBlockedUsersDao()

    private val user = User(id = ACCOUNT_ID, userId = "alice", username = "alice", token = "token", baseUrl = BASE_URL)

    private val bob = BlockedActor(ACTOR_TYPE_USERS, "bob")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(room: ConversationModel) =
        ConversationInfoViewModel(
            chatNetworkDataSource = mock<ChatNetworkDataSource> { onBlocking { getRoom(any(), any()) } doReturn room },
            conversationsRepository = FakeConversationsRepository(),
            ncApi = mock<NcApi>(),
            passwordPolicyRepository = mock<PasswordPolicyRepository>(),
            blockedUsersRepository = BlockedUsersRepository(blockedUsersDao),
            logger = mock<Logger>()
        ).apply { databaseStorageModule = mock<DatabaseStorageModule>() }

    @Test
    fun `the other user of a 1-1 conversation can be blocked and unblocked`() =
        runTest(dispatcher) {
            val model = viewModel(room(ConversationType.ROOM_TYPE_ONE_TO_ONE_CALL, name = "bob"))
            model.getRoom(user, ROOM_TOKEN)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(bob, model.uiState.value.blockableUser)
            assertFalse(model.uiState.value.isUserBlocked)

            model.setUserBlocked(true, "blocked")
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(model.uiState.value.isUserBlocked)
            assertEquals(listOf("Bob" to bob), blockedUsersDao.entries(ACCOUNT_ID))

            model.setUserBlocked(false, "unblocked")
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(model.uiState.value.isUserBlocked)
            assertEquals(emptyList<Pair<String?, BlockedActor>>(), blockedUsersDao.entries(ACCOUNT_ID))
        }

    @Test
    fun `a user blocked for this account is shown as blocked`() =
        runTest(dispatcher) {
            blockedUsersDao.upsert(entity(OTHER_ACCOUNT_ID, "bob"))
            val model = viewModel(room(ConversationType.ROOM_TYPE_ONE_TO_ONE_CALL, name = "bob"))
            model.getRoom(user, ROOM_TOKEN)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(model.uiState.value.isUserBlocked)

            blockedUsersDao.upsert(entity(ACCOUNT_ID, "bob"))
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(model.uiState.value.isUserBlocked)
        }

    @Test
    fun `group and federated conversations offer no blocking`() =
        runTest(dispatcher) {
            val group = viewModel(room(ConversationType.ROOM_GROUP_CALL, name = "team"))
            group.getRoom(user, ROOM_TOKEN)
            val federated = viewModel(
                room(
                    ConversationType.ROOM_TYPE_ONE_TO_ONE_CALL,
                    name = "bob",
                    remoteServer = "https://other.example.com"
                )
            )
            federated.getRoom(user, ROOM_TOKEN)
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(group.uiState.value.blockableUser)
            assertNull(federated.uiState.value.blockableUser)

            group.setUserBlocked(true, "blocked")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(emptyList<Pair<String?, BlockedActor>>(), blockedUsersDao.entries(ACCOUNT_ID))
        }

    private fun room(type: ConversationType, name: String, remoteServer: String = ""): ConversationModel =
        ConversationModel.mapToConversationModel(
            ConversationDto(
                token = ROOM_TOKEN,
                name = name,
                displayName = name.replaceFirstChar { it.uppercase() },
                type = type
            ).apply { this.remoteServer = remoteServer },
            user
        )

    private fun entity(accountId: Long, actorId: String) =
        BlockedUserEntity(accountId, ACTOR_TYPE_USERS, actorId, actorId, 0L)

    private class FakeBlockedUsersDao : BlockedUsersDao {
        private val rows = MutableStateFlow<List<BlockedUserEntity>>(emptyList())

        fun entries(accountId: Long) =
            rows.value
                .filter { it.accountId == accountId }
                .map { it.displayName to BlockedActor(it.actorType, it.actorId) }

        override fun getBlockedUsers(accountId: Long): Flow<List<BlockedUserEntity>> =
            rows.map { all -> all.filter { it.accountId == accountId } }

        override suspend fun isBlocked(accountId: Long, actorType: String, actorId: String): Boolean =
            rows.value.any { it.accountId == accountId && it.actorType == actorType && it.actorId == actorId }

        override suspend fun isActorIdBlocked(accountId: Long, actorId: String): Boolean =
            rows.value.any { it.accountId == accountId && it.actorId == actorId }

        override suspend fun upsert(blockedUser: BlockedUserEntity) {
            delete(blockedUser.accountId, blockedUser.actorType, blockedUser.actorId)
            rows.update { it + blockedUser }
        }

        override suspend fun delete(accountId: Long, actorType: String, actorId: String) {
            rows.update { all ->
                all.filterNot { it.accountId == accountId && it.actorType == actorType && it.actorId == actorId }
            }
        }

        override fun deleteAllForAccount(accountId: Long): Int {
            val count = rows.value.count { it.accountId == accountId }
            rows.update { all -> all.filterNot { it.accountId == accountId } }
            return count
        }
    }

    companion object {
        private const val ACCOUNT_ID = 1L
        private const val OTHER_ACCOUNT_ID = 2L
        private const val ROOM_TOKEN = "room"
        private const val BASE_URL = "https://cloud.example.com"
    }
}
