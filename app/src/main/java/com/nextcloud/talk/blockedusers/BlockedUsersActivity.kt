/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.blockedusers

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.BaseActivity
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.components.ColoredStatusBar
import com.nextcloud.talk.components.StandardAppBar
import com.nextcloud.talk.dagger.modules.assistedViewModels
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists the users blocked on this device for one account and lets them be unblocked.
 */
@AutoInjector(NextcloudTalkApplication::class)
class BlockedUsersActivity : BaseActivity() {

    @Inject
    lateinit var blockedUsersViewModelFactory: BlockedUsersViewModel.Factory

    private var accountId: Long = 0L

    private val blockedUsersViewModel: BlockedUsersViewModel by assistedViewModels {
        blockedUsersViewModelFactory.build(accountId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
        accountId = setUpBoundUserOrFinish()?.id ?: return

        val colorScheme = viewThemeUtils.getColorScheme(this)
        setContent {
            MaterialTheme(colorScheme = colorScheme) {
                ColoredStatusBar()
                val blockedUsers by blockedUsersViewModel.blockedUsers.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                Scaffold(
                    modifier = Modifier.statusBarsPadding(),
                    topBar = { StandardAppBar(title = stringResource(R.string.nc_blocked_users), menuItems = null) },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { paddingValues ->
                    BlockedUsersScreen(
                        blockedUsers = blockedUsers,
                        onUnblock = { blockedUser ->
                            blockedUsersViewModel.unblock(blockedUser.actor)
                            val confirmation = getString(R.string.nc_unblocked_user_snackbar, blockedUser.name)
                            scope.launch { snackbarHostState.showSnackbar(confirmation) }
                        },
                        modifier = Modifier
                            .padding(paddingValues)
                            .background(colorResource(R.color.bg_default))
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

private val BlockedUser.name: String
    get() = displayName?.takeIf { it.isNotBlank() } ?: actor.actorId

@Composable
fun BlockedUsersScreen(
    blockedUsers: List<BlockedUser>?,
    onUnblock: (BlockedUser) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingUnblock by rememberSaveable(stateSaver = blockedActorSaver) { mutableStateOf<BlockedActor?>(null) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.nc_blocked_users_device_only_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        when {
            blockedUsers == null -> CenteredContent { CircularProgressIndicator() }

            blockedUsers.isEmpty() -> CenteredContent { Text(stringResource(R.string.nc_blocked_users_empty)) }

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = blockedUsers, key = { "${it.actor.actorType}/${it.actor.actorId}" }) { blockedUser ->
                    BlockedUserRow(blockedUser = blockedUser, onUnblock = { pendingUnblock = blockedUser.actor })
                    HorizontalDivider()
                }
            }
        }
    }

    val userToUnblock = blockedUsers?.firstOrNull { it.actor == pendingUnblock }
    if (userToUnblock != null) {
        UnblockUserConfirmationDialog(
            displayName = userToUnblock.name,
            onConfirm = {
                pendingUnblock = null
                onUnblock(userToUnblock)
            },
            onDismiss = { pendingUnblock = null }
        )
    }
}

private val blockedActorSaver = Saver<BlockedActor?, List<String>>(
    save = { actor -> actor?.let { listOf(it.actorType, it.actorId) } },
    restore = { saved -> BlockedActor(saved[0], saved[1]) }
)

@Composable
private fun BlockedUserRow(blockedUser: BlockedUser, onUnblock: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = blockedUser.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (blockedUser.name != blockedUser.actor.actorId) {
                Text(
                    text = blockedUser.actor.actorId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(onClick = onUnblock) {
            Text(stringResource(R.string.nc_unblock))
        }
    }
}

@Composable
private fun CenteredContent(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Preview
@Composable
private fun BlockedUsersScreenPreview() {
    MaterialTheme {
        BlockedUsersScreen(
            blockedUsers = listOf(
                BlockedUser(BlockedActor(ACTOR_TYPE_USERS, "bob"), "Bob Smith", 0L),
                BlockedUser(BlockedActor(ACTOR_TYPE_FEDERATED_USERS, "carol@cloud.example.com"), null, 0L)
            ),
            onUnblock = {}
        )
    }
}

@Preview
@Composable
private fun BlockedUsersScreenEmptyPreview() {
    MaterialTheme {
        BlockedUsersScreen(blockedUsers = emptyList(), onUnblock = {})
    }
}
