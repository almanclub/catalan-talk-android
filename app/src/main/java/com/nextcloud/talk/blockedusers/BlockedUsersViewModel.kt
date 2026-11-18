/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.blockedusers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BlockedUsersViewModel @AssistedInject constructor(
    private val blockedUsersRepository: BlockedUsersRepository,
    @Assisted private val accountId: Long
) : ViewModel() {

    /** The users blocked for the account, null until they are loaded. */
    val blockedUsers: StateFlow<List<BlockedUser>?> = blockedUsersRepository.observeBlockedUsers(accountId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun unblock(actor: BlockedActor) {
        viewModelScope.launch {
            blockedUsersRepository.unblock(accountId, actor)
        }
    }

    @AssistedFactory
    interface Factory {
        fun build(accountId: Long): BlockedUsersViewModel
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
