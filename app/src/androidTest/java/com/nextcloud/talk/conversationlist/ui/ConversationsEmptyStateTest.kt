/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.conversationlist.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.nextcloud.talk.R
import org.junit.Rule
import org.junit.Test

class ConversationsEmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyConversationsShowsLogo() {
        composeRule.setContent {
            EmptyConversationsView(showLogo = true, onCreateNewConversation = {})
        }

        val appName = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.nc_app_product_name)
        composeRule.onNodeWithContentDescription(appName).assertIsDisplayed()
    }
}
