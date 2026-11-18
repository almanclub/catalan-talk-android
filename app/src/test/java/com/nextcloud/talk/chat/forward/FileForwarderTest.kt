/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.forward

import com.nextcloud.talk.utils.message.groupHashOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileForwarderTest {

    private fun forwarder(api: ForwardApi, usesSubfolders: Boolean = true, isHostedElsewhere: Boolean = false) =
        FileForwarder(
            api,
            ForwardSettings("alice", "alice@cloud.example.com", isHostedElsewhere, usesSubfolders, "/Talk"),
            readOnlyNotice = { "notice $it" },
            newUuid = sequenceOf("upload", "uuid-1", "uuid-2", "uuid-3").iterator()::next
        )

    @Test
    fun filesAreCopiedToTheDraftFolderAndPostedAsOneBatch() =
        runTest {
            val api = FakeForwardApi(listOf(RoomMember("users", "alice"), RoomMember("users", "bob")))

            val result = forwarder(api).forward(listOf(PHOTO, VOICE), silent = false)

            assertEquals(ForwardResult(emptyList(), emptyList()), result)
            assertEquals(
                listOf(
                    "probe photo.jpg",
                    "copy /Photos/photo.jpg to /Talk/Room-abc/Draft/uuid-1-photo.jpg",
                    "post /Talk/Room-abc/Draft/uuid-1-photo.jpg as renamed-photo.jpg {\"caption\":\"Our trip\"}",
                    "probe a.mp3",
                    "copy /Talk/a.mp3 to /Talk/Room-abc/Draft/uuid-2-a.mp3",
                    "post /Talk/Room-abc/Draft/uuid-2-a.mp3 as renamed-a.mp3 {\"messageType\":\"voice-message\"}"
                ),
                api.calls
            )
            assertEquals(listOf("001", "002"), api.referenceIds.map { it.substringAfterLast('-') })
            assertNotNull(api.referenceIds.map { groupHashOf(it) }.toSet().single())
        }

    @Test
    fun missingAttachmentFolderIsCreatedBeforeCopyingAgain() =
        runTest {
            val api = FakeForwardApi(
                listOf(RoomMember("users", "bob")),
                copyStatuses = mutableMapOf("/Photos/photo.jpg" to mutableListOf(HTTP_CONFLICT))
            )

            val result = forwarder(api, usesSubfolders = false).forward(listOf(PHOTO), silent = true)

            assertEquals(ForwardResult(emptyList(), emptyList()), result)
            assertEquals(
                listOf(
                    "copy /Photos/photo.jpg to /Talk/photo.jpg",
                    "mkcol /Talk",
                    "copy /Photos/photo.jpg to /Talk/photo.jpg",
                    "share /Talk/photo.jpg with conversation {\"caption\":\"Our trip\",\"silent\":true}"
                ),
                api.calls
            )
        }

    @Test
    fun remoteOnlyConversationGetsReadOnlySharesAndANotice() =
        runTest {
            val api = FakeForwardApi(
                listOf(RoomMember("users", "alice"), RoomMember("federated_users", "carol@other.example.com"))
            )

            val result = forwarder(api).forward(listOf(PHOTO), silent = true)

            assertEquals(ForwardResult(emptyList(), emptyList()), result)
            assertEquals(
                listOf(
                    "mkcol /Talk-forwarded",
                    "mkcol /Talk-forwarded/uuid-1",
                    "copy /Photos/photo.jpg to /Talk-forwarded/uuid-1/photo.jpg",
                    "share /Talk-forwarded/uuid-1/photo.jpg read-only with 6:carol@other.example.com",
                    "message \"Our trip\nnotice photo.jpg\" silent=true"
                ),
                api.calls
            )
        }

    @Test
    fun failingFileDoesNotStopTheOthers() =
        runTest {
            val api = FakeForwardApi(
                listOf(RoomMember("users", "bob"), RoomMember("federated_users", "carol@other.example.com")),
                copyStatuses = mutableMapOf("/Photos/photo.jpg" to mutableListOf(HTTP_INSUFFICIENT_STORAGE))
            )

            val result = forwarder(api).forward(listOf(PHOTO, VOICE), silent = false)

            assertEquals(ForwardResult(listOf("photo.jpg"), emptyList()), result)
            assertTrue(api.calls.any { it.startsWith("post /Talk/Room-abc/Draft/uuid-2-a.mp3") })
            assertTrue(api.calls.any { it.endsWith("read-only with 6:carol@other.example.com") })
            assertTrue(api.calls.none { it.startsWith("message") })
        }

    @Test
    fun conversationHostedElsewhereReportsUnreachedRecipients() =
        runTest {
            val api = FakeForwardApi(
                listOf(
                    RoomMember("users", "alice"),
                    RoomMember("users", "dave"),
                    RoomMember("federated_users", "carol@other.example.com")
                ),
                failingRecipients = setOf("carol@other.example.com")
            )

            val result = forwarder(api, isHostedElsewhere = true).forward(listOf(PHOTO), silent = false)

            assertEquals(ForwardResult(emptyList(), listOf("carol@other.example.com")), result)
            assertTrue(api.calls.contains("share /Talk-forwarded/uuid-1/photo.jpg read-only with 0:dave"))
            assertTrue(api.calls.none { it.startsWith("probe") || it.startsWith("post") })
            assertTrue(api.calls.last().startsWith("message"))
        }

    @Test
    fun allFilesFailWhenTheMembersCanNotBeLoaded() =
        runTest {
            val api = FakeForwardApi(members = null)

            val result = forwarder(api).forward(listOf(PHOTO, VOICE), silent = false)

            assertEquals(ForwardResult(listOf("photo.jpg", "a.mp3"), emptyList()), result)
            assertTrue(api.calls.isEmpty())
        }

    companion object {
        private val PHOTO = ForwardedFile("/Photos/photo.jpg", "photo.jpg", "Our trip", false)
        private val VOICE = ForwardedFile("/Talk/a.mp3", "a.mp3", null, true)
        private const val HTTP_CONFLICT = 409
        private const val HTTP_INSUFFICIENT_STORAGE = 507
    }
}
