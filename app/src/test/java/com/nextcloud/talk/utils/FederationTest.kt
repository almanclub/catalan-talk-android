/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import com.nextcloud.talk.models.json.conversations.ConversationDto
import com.nextcloud.talk.models.json.conversations.ConversationEnums.ConversationType
import com.nextcloud.talk.models.json.participants.ParticipantDto.ParticipantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FederationTest {

    private fun conversation(name: String, type: ConversationType = ConversationType.ROOM_GROUP_CALL) =
        ConversationModel.mapToConversationModel(
            ConversationDto(token = "token", name = name, type = type, participantType = ParticipantType.OWNER),
            User().apply { id = 1 }
        )

    private fun capabilities(feature: Boolean, enabled: Any?, outgoingEnabled: Any?) =
        SpreedCapabilityDto().apply {
            features = if (feature) listOf(SpreedFeatures.FEDERATION_V1.value) else emptyList()
            config = hashMapOf(
                "federation" to hashMapOf<String, Any>().apply {
                    enabled?.let { put("enabled", it) }
                    outgoingEnabled?.let { put("outgoing-enabled", it) }
                }
            )
        }

    @Test
    fun ownCloudIdIsTheUserAtTheServerAddress() {
        assertEquals("alice@cloud.example.com", CloudIdUtils.ownCloudId("alice", "https://cloud.example.com/"))
        assertEquals(
            "alice@cloud.example.com:8443/nextcloud",
            CloudIdUtils.ownCloudId("alice", "https://cloud.example.com:8443/nextcloud")
        )
    }

    @Test
    fun cloudIdsAreComparedIgnoringCase() {
        assertTrue(CloudIdUtils.isSameCloudId("Alice@Cloud.example.com", "alice@cloud.example.com"))
        assertFalse(CloudIdUtils.isSameCloudId("alice@other.example.com", "alice@cloud.example.com"))
        assertFalse(CloudIdUtils.isSameCloudId(null, null))
    }

    @Test
    fun groupConversationNamedDmIsAFederatedDirectMessage() {
        val directMessage = conversation("dm:alice@cloud.example.com|bob@other.example.com")

        assertTrue(ConversationUtils.isFederatedDirectMessage(directMessage))
        assertFalse(ConversationUtils.isNameEditable(directMessage, SpreedCapabilityDto()))
    }

    @Test
    fun otherConversationsAreNoFederatedDirectMessages() {
        val group = conversation("Team")

        assertFalse(ConversationUtils.isFederatedDirectMessage(group))
        assertTrue(ConversationUtils.isNameEditable(group, SpreedCapabilityDto()))
        assertFalse(ConversationUtils.isFederatedDirectMessage(conversation("dm:x", ConversationType.ROOM_PUBLIC_CALL)))
        assertFalse(ConversationUtils.isFederatedDirectMessage(null))
    }

    @Test
    fun federatedUsersCanBeInvitedWhenFederationIsEnabledForOutgoingInvites() {
        assertTrue(CapabilitiesUtil.canInviteFederatedUsers(capabilities(true, true, true)))
    }

    @Test
    fun federatedUsersCanNotBeInvitedWithoutFederation() {
        assertFalse(CapabilitiesUtil.canInviteFederatedUsers(capabilities(false, true, true)))
        assertFalse(CapabilitiesUtil.canInviteFederatedUsers(capabilities(true, false, true)))
        assertFalse(CapabilitiesUtil.canInviteFederatedUsers(capabilities(true, true, false)))
        assertFalse(CapabilitiesUtil.canInviteFederatedUsers(capabilities(true, true, null)))
        assertFalse(CapabilitiesUtil.canInviteFederatedUsers(null))
    }
}
