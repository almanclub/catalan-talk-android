/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

object CloudIdUtils {
    /**
     * The federated cloud ID of [userId] on the server at [baseUrl], e.g. "alice@cloud.example.com/nextcloud".
     */
    fun ownCloudId(userId: String, baseUrl: String): String = "$userId@${baseUrl.substringAfter("://").trimEnd('/')}"

    fun isSameCloudId(first: String?, second: String?): Boolean =
        first != null && second != null && first.equals(second, ignoreCase = true)
}
