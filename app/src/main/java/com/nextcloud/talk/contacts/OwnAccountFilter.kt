/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.contacts

import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.json.autocomplete.AutocompleteUserDto
import com.nextcloud.talk.utils.CloudIdUtils

const val CONTACT_SOURCE_USERS = "users"
const val CONTACT_SOURCE_REMOTES = "remotes"

/**
 * These contacts without the account of [user], which a search can list as a user of this server or, by its cloud
 * ID, as a remote user.
 */
fun List<AutocompleteUserDto>.withoutOwnAccount(user: User): List<AutocompleteUserDto> {
    val userId = user.userId ?: return this
    val ownCloudId = user.baseUrl?.let { CloudIdUtils.ownCloudId(userId, it) }
    return filterNot { contact ->
        when (contact.source) {
            CONTACT_SOURCE_USERS -> contact.id.equals(userId, ignoreCase = true)
            CONTACT_SOURCE_REMOTES -> CloudIdUtils.isSameCloudId(contact.id, ownCloudId)
            else -> false
        }
    }
}
