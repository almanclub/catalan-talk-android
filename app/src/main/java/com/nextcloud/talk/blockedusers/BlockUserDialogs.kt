/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.blockedusers

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.nextcloud.talk.R

@Composable
fun BlockUserConfirmationDialog(displayName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BlockStateConfirmationDialog(
        title = stringResource(R.string.nc_block_user_confirm_title, displayName),
        message = stringResource(R.string.nc_block_user_confirm_message),
        confirmLabel = stringResource(R.string.nc_block),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
fun UnblockUserConfirmationDialog(displayName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BlockStateConfirmationDialog(
        title = stringResource(R.string.nc_unblock_user_confirm_title, displayName),
        message = stringResource(R.string.nc_unblock_user_confirm_message),
        confirmLabel = stringResource(R.string.nc_unblock),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun BlockStateConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.baseline_block_24),
                contentDescription = null
            )
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.nc_cancel))
            }
        }
    )
}
