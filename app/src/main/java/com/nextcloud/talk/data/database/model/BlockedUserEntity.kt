/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.data.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A user blocked on this device by the account [accountId] (`User.id`).
 *
 * There is intentionally no foreign key to `User`: accounts are re-saved with `REPLACE`, which would cascade and
 * drop the block list. The entries are deleted together with the account instead.
 */
@Entity(
    tableName = "BlockedUsers",
    primaryKeys = ["accountId", "actorType", "actorId"]
)
data class BlockedUserEntity(
    @ColumnInfo(name = "accountId") val accountId: Long,
    @ColumnInfo(name = "actorType") val actorType: String,
    @ColumnInfo(name = "actorId") val actorId: String,
    @ColumnInfo(name = "displayName") val displayName: String?,
    @ColumnInfo(name = "blockedAt") val blockedAt: Long
)
