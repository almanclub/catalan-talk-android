/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.blockedusers

import com.nextcloud.talk.data.database.dao.BlockedUsersDao
import com.nextcloud.talk.data.database.model.BlockedUserEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Users blocked on this device, separately for every account. Blocking only hides their messages, notifications
 * and 1-1 calls locally; nothing is changed on the server.
 */
class BlockedUsersRepository @Inject constructor(private val blockedUsersDao: BlockedUsersDao) {

    fun observeBlockedUsers(accountId: Long): Flow<List<BlockedUser>> =
        blockedUsersDao.getBlockedUsers(accountId).map { entities ->
            entities.map { BlockedUser(BlockedActor(it.actorType, it.actorId), it.displayName, it.blockedAt) }
        }

    fun observeBlockedActors(accountId: Long): Flow<Set<BlockedActor>> =
        observeBlockedUsers(accountId)
            .map { blockedUsers -> blockedUsers.mapTo(HashSet()) { it.actor } }
            .distinctUntilChanged()

    suspend fun isBlocked(accountId: Long, actor: BlockedActor): Boolean =
        blockedUsersDao.isBlocked(accountId, actor.actorType, actor.actorId)

    /**
     * Whether a user with the id [actorId] is blocked, whatever their actor type.
     */
    suspend fun isActorIdBlocked(accountId: Long, actorId: String): Boolean =
        blockedUsersDao.isActorIdBlocked(accountId, actorId)

    /**
     * Not cancelled with the caller, so a confirmed block is kept even if the screen is closed right away.
     */
    suspend fun block(accountId: Long, actor: BlockedActor, displayName: String?) {
        withContext(NonCancellable) {
            blockedUsersDao.upsert(
                BlockedUserEntity(
                    accountId = accountId,
                    actorType = actor.actorType,
                    actorId = actor.actorId,
                    displayName = displayName,
                    blockedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun unblock(accountId: Long, actor: BlockedActor) {
        withContext(NonCancellable) {
            blockedUsersDao.delete(accountId, actor.actorType, actor.actorId)
        }
    }

    fun deleteAllForAccount(accountId: Long): Int = blockedUsersDao.deleteAllForAccount(accountId)
}
