/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.blockedusers

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nextcloud.talk.data.source.local.TalkDatabase
import com.nextcloud.talk.data.user.model.UserEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BlockedUsersRepositoryTest {

    private lateinit var db: TalkDatabase
    private lateinit var repository: BlockedUsersRepository

    private val bob = BlockedActor(ACTOR_TYPE_USERS, "bob")
    private val federatedCarol = BlockedActor(ACTOR_TYPE_FEDERATED_USERS, "carol@other.example.com")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TalkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BlockedUsersRepository(db.blockedUsersDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a block only applies to the account it was made for`() =
        runBlocking {
            repository.block(ACCOUNT_A, bob, "Bob")

            assertTrue(repository.isBlocked(ACCOUNT_A, bob))
            assertFalse(repository.isBlocked(ACCOUNT_B, bob))
            assertEquals(setOf(bob), repository.observeBlockedActors(ACCOUNT_A).first())
            assertEquals(emptySet<BlockedActor>(), repository.observeBlockedActors(ACCOUNT_B).first())
        }

    @Test
    fun `blocking again keeps a single entry with the newest name`() =
        runBlocking {
            repository.block(ACCOUNT_A, bob, "Bob")
            repository.block(ACCOUNT_A, bob, "Bob Smith")

            val blockedUsers = repository.observeBlockedUsers(ACCOUNT_A).first()
            assertEquals(listOf(bob), blockedUsers.map { it.actor })
            assertEquals("Bob Smith", blockedUsers.single().displayName)
        }

    @Test
    fun `unblocking removes only that user`() =
        runBlocking {
            repository.block(ACCOUNT_A, bob, "Bob")
            repository.block(ACCOUNT_A, federatedCarol, "Carol")

            repository.unblock(ACCOUNT_A, bob)

            assertFalse(repository.isBlocked(ACCOUNT_A, bob))
            assertEquals(setOf(federatedCarol), repository.observeBlockedActors(ACCOUNT_A).first())
        }

    @Test
    fun `an actor id is blocked whatever its actor type`() =
        runBlocking {
            repository.block(ACCOUNT_A, federatedCarol, "Carol")

            assertTrue(repository.isActorIdBlocked(ACCOUNT_A, "carol@other.example.com"))
            assertFalse(repository.isActorIdBlocked(ACCOUNT_A, "carol"))
            assertFalse(repository.isActorIdBlocked(ACCOUNT_B, "carol@other.example.com"))
        }

    @Test
    fun `removing an account deletes only its blocks`() =
        runBlocking {
            repository.block(ACCOUNT_A, bob, "Bob")
            repository.block(ACCOUNT_A, federatedCarol, "Carol")
            repository.block(ACCOUNT_B, bob, "Bob")

            assertEquals(2, repository.deleteAllForAccount(ACCOUNT_A))

            assertEquals(emptySet<BlockedActor>(), repository.observeBlockedActors(ACCOUNT_A).first())
            assertEquals(setOf(bob), repository.observeBlockedActors(ACCOUNT_B).first())
        }

    @Test
    fun `saving the account again keeps its blocks`() =
        runBlocking {
            val usersDao = db.usersDao()
            usersDao.saveUser(UserEntity(id = ACCOUNT_A, username = "alice", baseUrl = BASE_URL, token = "token"))
            repository.block(ACCOUNT_A, bob, "Bob")

            usersDao.saveUser(UserEntity(id = ACCOUNT_A, username = "alice", baseUrl = BASE_URL, token = "newToken"))

            assertTrue(repository.isBlocked(ACCOUNT_A, bob))
        }

    companion object {
        private const val ACCOUNT_A = 1L
        private const val ACCOUNT_B = 2L
        private const val BASE_URL = "https://cloud.example.com"
    }
}
