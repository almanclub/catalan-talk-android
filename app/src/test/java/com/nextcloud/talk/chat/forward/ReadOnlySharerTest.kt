/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.chat.forward

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReadOnlySharerTest {

    private fun sharer(api: ForwardApi) = ReadOnlySharer(api, newUuid = { "uuid-1" })

    @Test
    fun theFileIsPlacedInANewFolderAndSharedWithEachRecipient() =
        runTest {
            val api = FakeForwardApi(members = emptyList())

            val unreached = sharer(api).share("photo.jpg", listOf(CAROL, DAVE)) { api.calls += "place $it" }

            assertEquals(emptyList<String>(), unreached)
            assertEquals(
                listOf(
                    "mkcol /Talk-forwarded",
                    "mkcol /Talk-forwarded/uuid-1",
                    "place /Talk-forwarded/uuid-1/photo.jpg",
                    "share /Talk-forwarded/uuid-1/photo.jpg read-only with 6:carol@other.example.com",
                    "share /Talk-forwarded/uuid-1/photo.jpg read-only with 0:dave"
                ),
                api.calls
            )
        }

    @Test
    fun recipientsTheFileCouldNotBeSharedWithAreReturned() =
        runTest {
            val api = FakeForwardApi(members = emptyList(), failingRecipients = setOf(CAROL.shareWith))

            val unreached = sharer(api).share("photo.jpg", listOf(CAROL, DAVE)) {}

            assertEquals(listOf(CAROL.shareWith), unreached)
            assertTrue(api.calls.last().endsWith("read-only with 0:dave"))
        }

    @Test
    fun nothingIsSharedWhenTheFileCouldNotBePlaced() {
        val api = FakeForwardApi(members = emptyList())

        assertThrows(IOException::class.java) {
            runBlocking { sharer(api).share("photo.jpg", listOf(CAROL)) { throw IOException("upload failed") } }
        }
        assertTrue(api.calls.none { it.startsWith("share") })
    }

    @Test
    fun theFileIsNotPlacedWhenItsFolderCouldNotBeCreated() {
        val api = FakeForwardApi(
            members = emptyList(),
            folderStatuses = mapOf("/Talk-forwarded/uuid-1" to HTTP_INSUFFICIENT_STORAGE)
        )

        assertThrows(IOException::class.java) {
            runBlocking { sharer(api).share("photo.jpg", listOf(CAROL)) { api.calls += "place $it" } }
        }
        assertEquals(listOf("mkcol /Talk-forwarded", "mkcol /Talk-forwarded/uuid-1"), api.calls)
    }

    @Test
    fun theReadOnlyNoticeFollowsTheCaption() {
        assertEquals("Our trip\nnotice", readOnlyShareMessage("Our trip", "notice"))
        assertEquals("notice", readOnlyShareMessage(null, "notice"))
    }

    @Test
    fun anUploadIsSharedReadOnlyOnlyWhenNoOtherMemberCanOpenTheAttachments() =
        runTest {
            val remoteOnly = listOf(ME, RoomMember("federated_users", CAROL.shareWith))

            assertEquals(listOf(CAROL), sharer(FakeForwardApi(remoteOnly)).uploadRecipients("me", OWN_CLOUD_ID, false))
            assertNull(
                sharer(FakeForwardApi(remoteOnly + RoomMember("users", "bob")))
                    .uploadRecipients("me", OWN_CLOUD_ID, false)
            )
            assertNull(sharer(FakeForwardApi(listOf(ME))).uploadRecipients("me", OWN_CLOUD_ID, false))
        }

    @Test
    fun theRecipientsOfAnUploadAreToldAboutItInItsPlaceholder() =
        runTest {
            val api = FakeForwardApi(members = emptyList(), failingRecipients = setOf(DAVE.shareWith))

            val unreached = sharer(api).shareUpload(UPLOAD, listOf(CAROL, DAVE)) {}

            assertEquals(listOf(DAVE.shareWith), unreached)
            assertEquals("message \"Our trip\nnotice\" silent=false ref=ref-1 replyTo=42", api.calls.last())
        }

    @Test
    fun nobodyIsToldAboutAnUploadNobodyGot() =
        runTest {
            val api = FakeForwardApi(members = emptyList(), failingRecipients = setOf(CAROL.shareWith))

            assertNull(sharer(api).shareUpload(UPLOAD, listOf(CAROL)) {})
            assertTrue(api.calls.none { it.startsWith("message") })
        }

    @Test
    fun anUploadNobodyWasToldAboutIsNotShared() =
        runTest {
            val api = FakeForwardApi(members = emptyList(), messageFails = true)

            assertNull(sharer(api).shareUpload(UPLOAD, listOf(CAROL)) {})
        }

    @Test
    fun captionAndReplyAreReadFromTheUploadMetaData() {
        assertEquals(
            UploadMessageOptions("Our trip", 42),
            uploadMessageOptions("{\"caption\":\"Our trip\",\"replyTo\":\"42\",\"referenceId\":\"abc\"}")
        )
        assertEquals(UploadMessageOptions(null, 0), uploadMessageOptions("{\"replyTo\":\"null\"}"))
        assertEquals(UploadMessageOptions(null, 0), uploadMessageOptions("{\"caption\":\"\",\"replyTo\":null}"))
        assertEquals(UploadMessageOptions(null, 0), uploadMessageOptions("not json"))
        assertEquals(UploadMessageOptions(null, 0), uploadMessageOptions(null))
    }

    companion object {
        private val CAROL = ReadOnlyRecipient(SHARE_TYPE_REMOTE, "carol@other.example.com")
        private val DAVE = ReadOnlyRecipient(SHARE_TYPE_USER, "dave")
        private val ME = RoomMember("users", "me")
        private const val OWN_CLOUD_ID = "me@server.example.com"
        private val UPLOAD = ReadOnlyUpload(
            fileName = "photo.jpg",
            notice = "notice",
            talkMetaData = "{\"caption\":\"Our trip\",\"replyTo\":\"42\"}",
            referenceId = "ref-1"
        )
        private const val HTTP_INSUFFICIENT_STORAGE = 507
    }
}
