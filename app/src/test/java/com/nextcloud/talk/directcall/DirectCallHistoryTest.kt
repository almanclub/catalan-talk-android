/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import com.nextcloud.talk.R
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.chat.data.model.ChatMessage.SystemMessageType
import com.nextcloud.talk.chat.ui.model.MessageTypeContent
import com.nextcloud.talk.data.database.model.ChatMessageEntity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.capabilities.CapabilitiesDto
import com.nextcloud.talk.models.json.capabilities.SpreedCapabilityDto
import com.nextcloud.talk.models.json.conversations.ConversationDto
import com.nextcloud.talk.models.json.conversations.ConversationEnums.ConversationType
import com.nextcloud.talk.models.json.participants.ParticipantDto.ParticipantType
import com.nextcloud.talk.utils.SpreedFeatures
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("TooManyFunctions")
class DirectCallHistoryTest {

    private fun attempt(vararg changes: Pair<String, Any?>): String {
        val fields = mutableMapOf<String, JsonElement>(
            "callId" to JsonPrimitive(CALL_ID),
            "sequence" to JsonPrimitive(1),
            "origin" to JsonPrimitive(ORIGIN),
            "version" to JsonPrimitive(4),
            "state" to JsonPrimitive("ended"),
            "outcome" to JsonPrimitive("completed"),
            "endReason" to JsonPrimitive("normal_hangup"),
            "mediaType" to JsonPrimitive("audio"),
            "caller" to participant(CALLER),
            "callee" to participant(CALLEE),
            "ringingStartedAt" to JsonPrimitive("2026-11-09T08:00:00Z"),
            "ringingDeadline" to JsonPrimitive("2026-11-09T08:00:30Z"),
            "answeredAt" to JsonPrimitive("2026-11-09T08:00:03Z"),
            "connectedAt" to JsonPrimitive("2026-11-09T08:00:05Z"),
            "endedAt" to JsonPrimitive("2026-11-09T08:01:10Z"),
            "serverTime" to JsonPrimitive("2026-11-09T08:01:10Z"),
            "durationSeconds" to JsonPrimitive(65),
            "chatMessageId" to JsonPrimitive(10)
        )
        changes.forEach { (key, value) ->
            fields[key] = when (value) {
                null -> JsonNull
                is Number -> JsonPrimitive(value)
                is JsonElement -> value
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(fields).toString()
    }

    private fun participant(cloudId: String) = JsonObject(mapOf("cloudId" to JsonPrimitive(cloudId)))

    private fun snapshot(vararg changes: Pair<String, Any?>): CallAttempt =
        requireNotNull(CallAttempt.parse(attempt(*changes)))

    @Suppress("LongParameterList")
    private fun message(
        id: Int = 10,
        callAttempt: String? = attempt(),
        type: SystemMessageType? = SystemMessageType.CALL_RESULT,
        text: String = "Legacy call result",
        messageType: String = "system",
        expirationTimestamp: Int = 0
    ): ChatMessage =
        ChatMessage(
            jsonMessageId = id,
            token = ROOM_TOKEN,
            timestamp = TIMESTAMP,
            message = text,
            systemMessageType = type,
            messageType = messageType,
            expirationTimestamp = expirationTimestamp,
            callAttempt = callAttempt
        )

    private fun entity(
        callAttempt: String? = attempt(),
        type: SystemMessageType = SystemMessageType.CALL_RESULT,
        messageType: String = "system",
        expirationTimestamp: Int = 0
    ): ChatMessageEntity =
        ChatMessageEntity(
            internalId = "1@$ROOM_TOKEN@10",
            accountId = 1,
            token = ROOM_TOKEN,
            id = 10,
            internalConversationId = "1@$ROOM_TOKEN",
            actorDisplayName = "Alice",
            message = "Legacy call result",
            actorId = "alice",
            actorType = "users",
            messageType = messageType,
            systemMessageType = type,
            expirationTimestamp = expirationTimestamp,
            callAttempt = callAttempt
        )

    private fun conversation(type: ConversationType, name: String = "alice") =
        ConversationModel.mapToConversationModel(
            ConversationDto(token = ROOM_TOKEN, name = name, type = type, participantType = ParticipantType.OWNER),
            User().apply { id = 1 }
        )

    private fun user(withCallAttempts: Boolean) =
        User(
            id = 1,
            userId = "alice",
            baseUrl = "https://cloud.catalan.vn/",
            capabilities = CapabilitiesDto().apply {
                spreedCapability = SpreedCapabilityDto().apply {
                    features = if (withCallAttempts) listOf(SpreedFeatures.CALL_ATTEMPTS_V1.value) else emptyList()
                }
            }
        )

    private fun visibleIds(messages: List<ChatMessage>, hidesCallEvents: Boolean = true, now: Long = NOW) =
        DirectCallHistory.visibleMessages(messages, isDirectRoom = true, hidesCallEvents, now).map { it.jsonMessageId }

    @Test
    fun resultLabelsUseFullViewerCloudId() {
        val unanswered = message(callAttempt = attempt("outcome" to "no_answer", "endReason" to "ring_timeout"))

        assertEquals(R.string.nc_call_history_no_answer, DirectCallHistory.historyTextRes(unanswered, CALLER, NOW))
        assertEquals(R.string.nc_call_history_missed, DirectCallHistory.historyTextRes(unanswered, CALLEE, NOW))
        assertNull(DirectCallHistory.historyTextRes(unanswered, "alice@unrelated.example", NOW))

        val rejected = snapshot("outcome" to "rejected", "endReason" to "callee_declined")
        assertEquals(R.string.nc_call_history_rejected, DirectCallHistory.resultTitleRes(rejected, isCaller = true))
        assertEquals(R.string.nc_call_history_declined, DirectCallHistory.resultTitleRes(rejected, isCaller = false))

        val cancelled = snapshot("outcome" to "cancelled", "endReason" to "caller_cancelled")
        assertEquals(R.string.nc_call_history_cancelled, DirectCallHistory.resultTitleRes(cancelled, isCaller = true))
        assertEquals(R.string.nc_call_history_cancelled, DirectCallHistory.resultTitleRes(cancelled, isCaller = false))
    }

    @Test
    fun viewerCloudIdIsTheFullFederatedId() {
        assertEquals(CALLER, DirectCallHistory.viewerCloudId(user(withCallAttempts = true)))
        assertNull(DirectCallHistory.viewerCloudId(null))
        assertEquals(true, DirectCallHistory.isCaller(snapshot(), "Alice@Cloud.Catalan.vn"))
        assertEquals(false, DirectCallHistory.isCaller(snapshot(), CALLEE))
        assertNull(DirectCallHistory.isCaller(snapshot(), "alice"))
    }

    @Test
    fun failedCallsNeverBecomeRejections() {
        val notConnected = snapshot("outcome" to "failed", "endReason" to "ended_before_connection")
        assertEquals(R.string.nc_call_history_not_connected, DirectCallHistory.resultTitleRes(notConnected, true))
        val timeout = snapshot("outcome" to "failed", "endReason" to "connection_timeout")
        assertEquals(R.string.nc_call_history_connection_timeout, DirectCallHistory.resultTitleRes(timeout, false))
        listOf("caller_session_lost", "connection_lost").forEach { reason ->
            val failed = snapshot("outcome" to "failed", "endReason" to reason)
            assertEquals(R.string.nc_call_history_failed, DirectCallHistory.resultTitleRes(failed, false))
        }
    }

    @Test
    fun mediaAndDurationComeOnlyFromMetadata() {
        val audio = snapshot()
        assertEquals(R.string.nc_call_history_outgoing_voice, DirectCallHistory.resultTitleRes(audio, true))
        assertEquals(R.string.nc_call_history_incoming_voice, DirectCallHistory.resultTitleRes(audio, false))
        assertEquals("1:05", DirectCallHistory.durationText(audio))

        val video = snapshot("mediaType" to "video", "durationSeconds" to 3661)
        assertEquals(R.string.nc_call_history_outgoing_video, DirectCallHistory.resultTitleRes(video, true))
        assertEquals(R.string.nc_call_history_incoming_video, DirectCallHistory.resultTitleRes(video, false))
        assertEquals("1:01:01", DirectCallHistory.durationText(video))

        val dropped = snapshot("outcome" to "failed", "endReason" to "connection_lost", "durationSeconds" to 12)
        assertEquals("0:12", DirectCallHistory.durationText(dropped))

        val neverConnected = snapshot("connectedAt" to null, "outcome" to "no_answer", "durationSeconds" to 30)
        assertNull("Ringing time must never be shown as call duration", DirectCallHistory.durationText(neverConnected))

        val failedSetup = snapshot("connectedAt" to null, "outcome" to "failed", "durationSeconds" to 8)
        assertNull("Setup time must never be shown as call duration", DirectCallHistory.durationText(failedSetup))
    }

    @Test
    fun directRoomsShowCallCardsAndOtherRoomsTheLocalizedText() {
        val outgoing = DirectCallHistory.systemMessageContent(message(), CALLER, isDirectRoom = true, now = NOW)
        assertEquals(
            MessageTypeContent.CallResult(
                titleRes = R.string.nc_call_history_outgoing_voice,
                isOutgoing = true,
                isMissed = false,
                isVideo = false,
                durationText = "1:05"
            ),
            outgoing
        )

        val missedVideo = message(
            callAttempt = attempt("mediaType" to "video", "outcome" to "no_answer", "connectedAt" to null)
        )
        assertEquals(
            MessageTypeContent.CallResult(
                titleRes = R.string.nc_call_history_missed,
                isOutgoing = false,
                isMissed = true,
                isVideo = true,
                durationText = null
            ),
            DirectCallHistory.systemMessageContent(missedVideo, CALLEE, isDirectRoom = true, now = NOW)
        )

        assertEquals(
            MessageTypeContent.CallHistoryText(R.string.nc_call_history_outgoing_voice),
            DirectCallHistory.systemMessageContent(message(), CALLER, isDirectRoom = false, now = NOW)
        )
    }

    @Test
    fun invalidRemovedOrForeignResultsKeepTheirSystemMessage() {
        val rows = listOf(
            message(callAttempt = null),
            message(callAttempt = """{"mediaType":"video"}"""),
            message(callAttempt = attempt("caller" to participant("someone@other.example"))),
            message(expirationTimestamp = 1),
            message(type = SystemMessageType.CALL_JOINED)
        )

        rows.forEach { row ->
            assertNull(DirectCallHistory.systemMessageContent(row, CALLER, isDirectRoom = true, now = NOW))
        }
    }

    @Test
    fun storedLegacyUnansweredCallsLocalizeWithoutChangingHistory() {
        listOf(SystemMessageType.CALL_TRIED, SystemMessageType.CALL_MISSED, SystemMessageType.CALL_RESULT)
            .forEach { type ->
                val row = message(callAttempt = null, type = type, text = "Unanswered call")

                assertEquals(R.string.nc_call_history_no_answer, DirectCallHistory.historyTextRes(row, CALLER, NOW))
                assertEquals(
                    "Legacy rows must not invent a call card",
                    MessageTypeContent.CallHistoryText(R.string.nc_call_history_no_answer),
                    DirectCallHistory.systemMessageContent(row, CALLER, isDirectRoom = true, now = NOW)
                )
                assertEquals("Unanswered call", row.message)
                assertNull(row.callAttempt)
            }
        val missed = message(callAttempt = null, type = SystemMessageType.CALL_MISSED, text = "Missed call")
        assertEquals(R.string.nc_call_history_missed, DirectCallHistory.historyTextRes(missed, CALLER, NOW))
    }

    @Test
    fun legacyTranslationDoesNotRewriteUserTextOrUnknownMetadata() {
        val comment = message(callAttempt = null, type = null, text = "Unanswered call", messageType = "comment")
        assertNull(DirectCallHistory.legacyTitleRes(comment, NOW))

        val unknownMetadata = message(callAttempt = """{"futureMetadata":true}""", text = "Unanswered call")
        assertNull(DirectCallHistory.legacyTitleRes(unknownMetadata, NOW))
        assertNull(DirectCallHistory.historyTextRes(unknownMetadata, CALLER, NOW))

        val custom = message(callAttempt = null, text = "Custom call result")
        assertNull(DirectCallHistory.historyTextRes(custom, CALLER, NOW))
    }

    @Test
    fun resultsWithoutMetadataKeepServerTextUnlessItIsEmpty() {
        assertNull(DirectCallHistory.historyTextRes(message(callAttempt = null), CALLER, NOW))
        assertNull(DirectCallHistory.historyTextRes(message(callAttempt = """{"mediaType":"video"}"""), CALLER, NOW))
        assertEquals(
            R.string.nc_call_history_unavailable,
            DirectCallHistory.historyTextRes(message(callAttempt = null, text = ""), CALLER, NOW)
        )
    }

    @Test
    fun technicalCallFilteringIsPresentationOnlyAndDirectScoped() {
        val events = listOf(
            SystemMessageType.CALL_STARTED,
            SystemMessageType.CALL_JOINED,
            SystemMessageType.CALL_LEFT,
            SystemMessageType.CALL_ENDED,
            SystemMessageType.CALL_ENDED_EVERYONE
        ).mapIndexed { index, type -> message(id = index + 1, callAttempt = null, type = type) }
        val rows = events + message()

        val allIds = rows.map { it.jsonMessageId }
        assertEquals(listOf(10), visibleIds(rows))
        assertEquals(allIds, visibleIds(rows, hidesCallEvents = false))
        assertEquals(
            allIds,
            DirectCallHistory.visibleMessages(rows, isDirectRoom = false, hidesCallEvents = true, NOW)
                .map { it.jsonMessageId }
        )
        assertEquals(SystemMessageType.CALL_STARTED, events.first().systemMessageType)
    }

    @Test
    fun callEventsAreHiddenOnlyInDirectRoomsOfSupportingServers() {
        val oneToOne = conversation(ConversationType.ROOM_TYPE_ONE_TO_ONE_CALL)
        val federated = conversation(ConversationType.ROOM_GROUP_CALL, "dm:alice@cloud.catalan.vn|alice@cloud.danco.vn")
        val group = conversation(ConversationType.ROOM_GROUP_CALL, "Ordinary two-person group")

        assertTrue(DirectCallHistory.hidesCallEvents(oneToOne, user(withCallAttempts = true)))
        assertTrue(DirectCallHistory.hidesCallEvents(federated, user(withCallAttempts = true)))
        assertFalse(DirectCallHistory.hidesCallEvents(group, user(withCallAttempts = true)))
        assertFalse(DirectCallHistory.hidesCallEvents(oneToOne, user(withCallAttempts = false)))
        assertTrue(DirectCallHistory.isDirectRoom(federated))
        assertFalse(DirectCallHistory.isDirectRoom(group))
    }

    @Test
    fun dedupeUsesVersionMessageIdAndOrigin() {
        val old = message(id = 10, callAttempt = attempt("version" to 3))
        val latest = message(id = 11)
        val anotherOrigin = message(id = 13, callAttempt = attempt("origin" to "https://other.example", "version" to 9))
        val anotherCall = message(id = 14, callAttempt = attempt("callId" to OTHER_CALL_ID, "version" to 1))

        assertEquals(listOf(11, 13, 14), visibleIds(listOf(old, latest, anotherOrigin, anotherCall)))
        assertEquals(listOf(11), visibleIds(listOf(latest, old)))

        val duplicate = message(id = 15)
        assertEquals(listOf(15), visibleIds(listOf(latest, duplicate)))
    }

    @Test
    fun deletedAndExpiredRowsCannotResurrectOlderResults() {
        val old = message(id = 10, callAttempt = attempt("version" to 3))
        val tombstone = message(
            id = 11,
            type = SystemMessageType.DUMMY,
            text = "Message deleted",
            messageType = "comment_deleted"
        )
        assertEquals(listOf(11), visibleIds(listOf(old, tombstone)))

        val expired = message(id = 12, expirationTimestamp = 100)
        assertEquals(emptyList<Int>(), visibleIds(listOf(expired), now = 101))
        assertEquals(emptyList<Int>(), visibleIds(listOf(old, expired), now = 101))
        assertEquals(listOf(12), visibleIds(listOf(old, expired), now = 99))
    }

    @Test
    fun staleRevisionsNeverReplaceANewerStoredResult() {
        val stored = entity()
        val stale = entity(callAttempt = attempt("version" to 2))
        val newer = entity(callAttempt = attempt("version" to 5, "durationSeconds" to 66))

        assertSame(stored, DirectCallHistory.merge(stored, stale, NOW))
        assertSame(newer, DirectCallHistory.merge(stored, newer, NOW))
        assertSame(newer, DirectCallHistory.merge(null, newer, NOW))

        val otherCall = entity(callAttempt = attempt("callId" to OTHER_CALL_ID, "version" to 1))
        assertSame(otherCall, DirectCallHistory.merge(stored, otherCall, NOW))
    }

    @Test
    fun rowsWithoutMetadataKeepTheStoredAttempt() {
        val stored = entity()
        val preview = entity(callAttempt = null)
        assertEquals(stored.callAttempt, DirectCallHistory.merge(stored, preview, NOW).callAttempt)

        val tombstone = entity(callAttempt = null, type = SystemMessageType.DUMMY, messageType = "comment_deleted")
        val deleted = DirectCallHistory.merge(stored, tombstone, NOW)
        assertEquals("comment_deleted", deleted.messageType)
        assertEquals("Deletion retains the attempt identity", stored.callAttempt, deleted.callAttempt)

        assertSame("A deleted result never comes back", deleted, DirectCallHistory.merge(deleted, entity(), NOW))
        val expired = entity(expirationTimestamp = 100)
        assertSame(expired, DirectCallHistory.merge(expired, entity(), now = 101))
    }

    @Test
    fun onlyCallHistoryRowsNeedTheStoredRow() {
        assertTrue(DirectCallHistory.needsStoredRow(entity()))
        assertTrue(DirectCallHistory.needsStoredRow(entity(callAttempt = null, messageType = "comment_deleted")))
        assertFalse(DirectCallHistory.needsStoredRow(entity(callAttempt = null, type = SystemMessageType.DUMMY)))
        assertFalse(DirectCallHistory.needsStoredRow(entity(callAttempt = null, type = SystemMessageType.CALL_JOINED)))
    }

    companion object {
        private const val CALL_ID = "747A2949-9990-4C8D-8574-21D4B0546843"
        private const val OTHER_CALL_ID = "12345678-1234-4234-8234-123456789abc"
        private const val ORIGIN = "https://cloud.catalan.vn"
        private const val CALLER = "alice@cloud.catalan.vn"
        private const val CALLEE = "alice@cloud.danco.vn"
        private const val ROOM_TOKEN = "direct-room"
        private const val TIMESTAMP = 1_794_211_200L
        private const val NOW = 1_794_211_300L
    }
}
