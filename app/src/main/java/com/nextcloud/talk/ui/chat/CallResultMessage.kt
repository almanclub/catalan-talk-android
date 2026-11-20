/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.ui.model.ChatMessageUi
import com.nextcloud.talk.chat.ui.model.MessageTypeContent
import com.nextcloud.talk.utils.DateUtils

private const val OUTGOING_CARD_ALPHA = 0.12f
private val cardMaxWidth = 300.dp
private val cardShape = RoundedCornerShape(14.dp)

/**
 * Summary card of a 1-1 call: outgoing calls align to the end, incoming calls to the start.
 */
@Composable
fun CallResultMessage(
    typeContent: MessageTypeContent.CallResult,
    message: ChatMessageUi,
    canCallBack: Boolean,
    onCallBackClick: (Boolean) -> Unit
) {
    val timeString = DateUtils(LocalContext.current).getLocalTimeStringFromTimestamp(message.timestamp)
    val cardColor = if (typeContent.isOutgoing) {
        colorScheme.primary.copy(alpha = OUTGOING_CARD_ALPHA)
    } else {
        colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (typeContent.isOutgoing) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = cardMaxWidth)
                .clip(cardShape)
                .background(cardColor)
                .border(0.5.dp, colorScheme.outlineVariant, cardShape)
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (canCallBack) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CallResultSummary(typeContent)
            if (canCallBack) {
                HorizontalDivider(thickness = 0.5.dp, color = colorScheme.outlineVariant)
                TextButton(
                    onClick = { onCallBackClick(typeContent.isVideo) },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.nc_call_history_call_back))
                }
            }
        }
        Text(
            text = timeString,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CallResultSummary(typeContent: MessageTypeContent.CallResult) {
    val mediaTitle = stringResource(
        if (typeContent.isVideo) R.string.nc_call_history_video else R.string.nc_call_history_voice
    )
    val detail = typeContent.durationText
        ?.let { stringResource(R.string.nc_call_history_detail, mediaTitle, it) }
        ?: mediaTitle

    Text(
        text = stringResource(typeContent.titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = if (typeContent.isMissed) colorScheme.error else colorScheme.onSurface
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(if (typeContent.isVideo) R.drawable.ic_videocam_24px else R.drawable.ic_phone),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colorScheme.onSurfaceVariant
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant
        )
    }
}
