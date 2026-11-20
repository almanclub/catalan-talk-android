/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.json

import com.bluelinelabs.logansquare.LoganSquare
import com.nextcloud.talk.chat.data.model.ChatMessage.SystemMessageType
import com.nextcloud.talk.directcall.CallAttempt
import com.nextcloud.talk.models.json.chat.ChatMessageDto
import com.nextcloud.talk.models.json.chat.ChatOverall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageCallAttemptParsingTest {

    private fun chat(callAttempt: String): String =
        """
            {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":[
              {"id":10,"systemMessage":"call_result","callAttempt":$callAttempt,"message":"Outgoing call"}
            ]}}
        """.trimIndent()

    private fun parseMessage(callAttempt: String): ChatMessageDto =
        LoganSquare.parse(chat(callAttempt), ChatOverall::class.java).ocs!!.data!!.single()

    @Test
    fun callAttemptObjectIsKeptAsRawJson() {
        val message = parseMessage(CALL_ATTEMPT)

        assertEquals(SystemMessageType.CALL_RESULT, message.systemMessageType)
        assertEquals("Outgoing call", message.message)
        assertEquals(65L, CallAttempt.parse(message.callAttempt)?.durationSeconds)
    }

    @Test
    fun nonObjectCallAttemptIsIgnoredWithoutBreakingTheMessage() {
        listOf("null", "\"text\"", "[{\"callId\":1},[2]]", "42").forEach { value ->
            val message = parseMessage(value)

            assertNull(message.callAttempt)
            assertEquals("Outgoing call", message.message)
        }
    }

    @Test
    fun storedLastMessageRoundTripsTheCallAttempt() {
        val original = parseMessage(CALL_ATTEMPT)

        val restored = LoganSquare.parse(LoganSquare.serialize(original), ChatMessageDto::class.java)

        assertNotNull(CallAttempt.parse(restored.callAttempt))
        assertEquals(CallAttempt.parse(original.callAttempt), CallAttempt.parse(restored.callAttempt))
        assertFalse(LoganSquare.serialize(ChatMessageDto(id = 1)).contains("callAttempt"))
    }

    companion object {
        private val CALL_ATTEMPT = """
            {"callId":"747A2949-9990-4C8D-8574-21D4B0546843","sequence":1,"origin":"https://cloud.catalan.vn",
             "version":4,"state":"ended","outcome":"completed","endReason":"normal_hangup","mediaType":"audio",
             "caller":{"cloudId":"alice@cloud.catalan.vn"},"callee":{"cloudId":"alice@cloud.danco.vn"},
             "ringingStartedAt":"2026-11-09T08:00:00Z","ringingDeadline":"2026-11-09T08:00:30Z",
             "connectedAt":"2026-11-09T08:00:05Z","serverTime":"2026-11-09T08:01:10Z","durationSeconds":65,
             "nested":{"list":[1,{"deep":true}]}}
        """.trimIndent()
    }
}
