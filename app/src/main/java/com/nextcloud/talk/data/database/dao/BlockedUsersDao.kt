/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nextcloud.talk.data.database.model.BlockedUserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedUsersDao {
    @Query(
        """
        SELECT * FROM BlockedUsers
        WHERE accountId = :accountId
        ORDER BY displayName COLLATE NOCASE, actorId
        """
    )
    fun getBlockedUsers(accountId: Long): Flow<List<BlockedUserEntity>>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM BlockedUsers
            WHERE accountId = :accountId AND actorType = :actorType AND actorId = :actorId
        )
        """
    )
    suspend fun isBlocked(accountId: Long, actorType: String, actorId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM BlockedUsers WHERE accountId = :accountId AND actorId = :actorId)")
    suspend fun isActorIdBlocked(accountId: Long, actorId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blockedUser: BlockedUserEntity)

    @Query("DELETE FROM BlockedUsers WHERE accountId = :accountId AND actorType = :actorType AND actorId = :actorId")
    suspend fun delete(accountId: Long, actorType: String, actorId: String)

    @Query("DELETE FROM BlockedUsers WHERE accountId = :accountId")
    fun deleteAllForAccount(accountId: Long): Int
}
