/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Sowjanya Kota <sowjanya.kch@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.contacts

import androidx.lifecycle.ViewModelStore
import com.nextcloud.talk.contacts.apiService.FakeItem
import com.nextcloud.talk.contacts.repository.FakeRepositoryError
import com.nextcloud.talk.contacts.repository.FakeRepositorySuccess
import com.nextcloud.talk.data.user.UsersDao
import com.nextcloud.talk.data.user.UsersRepository
import com.nextcloud.talk.data.user.UsersRepositoryImpl
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.logger.Logger
import com.nextcloud.talk.models.json.autocomplete.AutocompleteOCS
import com.nextcloud.talk.models.json.autocomplete.AutocompleteOverall
import com.nextcloud.talk.models.json.autocomplete.AutocompleteUserDto
import com.nextcloud.talk.models.json.conversations.RoomOverall
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.preview.DummyUserDaoImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class ContactsViewModelTest {
    private lateinit var viewModel: ContactsViewModel
    private val repository: ContactsRepository = FakeRepositorySuccess()
    private val viewModelStore = ViewModelStore()

    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()

    val usersDao: UsersDao
        get() = DummyUserDaoImpl()

    val userRepository: UsersRepository
        get() = UsersRepositoryImpl(usersDao)

    val userManager: UserManager
        get() = UserManager(userRepository)

    private var viewModelCount = 0

    private fun createViewModel(repo: ContactsRepository): ContactsViewModel {
        val user = runBlocking { userManager.getDefaultUser() }!!
        return ContactsViewModel(repo, mock<Logger>(), user).also {
            viewModelStore.put("contactsViewModel${viewModelCount++}", it)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = createViewModel(repository)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `fetch contacts`() =
        runTest {
            viewModel = createViewModel(repository)
            viewModel.getContactsFromSearchParams()
            assert(viewModel.contactsViewState.value is ContactsViewModel.ContactsUiState.Success)
            val successState = viewModel.contactsViewState.value as ContactsViewModel.ContactsUiState.Success
            assert(successState.contacts == FakeItem.contacts)
        }

    @Test
    fun `test error contacts state`() =
        runTest {
            viewModel = createViewModel(FakeRepositoryError())
            viewModel.getContactsFromSearchParams()
            assert(viewModel.contactsViewState.value is ContactsViewModel.ContactsUiState.Error)
            val errorState = viewModel.contactsViewState.value as ContactsViewModel.ContactsUiState.Error
            assert(errorState.message == "unable to fetch contacts")
        }

    @Test
    fun `update search query`() {
        viewModel.updateSearchQuery("Ma")
        assert(viewModel.searchQuery.value == "Ma")
    }

    @Test
    fun `initial search query is empty string`() {
        viewModel.updateSearchQuery("")
        assert(viewModel.searchQuery.value == "")
    }

    @Test
    fun `initial shareType is User`() {
        assert(viewModel.shareTypeList.contains(ShareType.User.shareType))
    }

    @Test
    fun `update shareTypes`() {
        viewModel.updateShareTypes(listOf(ShareType.Group.shareType))
        assert(viewModel.shareTypeList.contains(ShareType.Group.shareType))
    }

    @Test
    fun `initial room state is none`() =
        runTest {
            assert(viewModel.roomViewState.value is ContactsViewModel.RoomUiState.None)
        }

    @Test
    fun `test success room state`() =
        runTest {
            viewModel.createRoom("1", "users", "s@gmail.com", null)
            assert(viewModel.roomViewState.value is ContactsViewModel.RoomUiState.Success)
            val successState = viewModel.roomViewState.value as ContactsViewModel.RoomUiState.Success
            assert(successState.conversation == FakeItem.roomOverall.ocs!!.data)
        }

    @Test
    fun `a handled room state is cleared`() =
        runTest {
            viewModel.createRoom("1", "users", "s@gmail.com", null)
            viewModel.clearRoomState()
            assert(viewModel.roomViewState.value is ContactsViewModel.RoomUiState.None)
        }

    @Test
    fun `test failure room state`() =
        runTest {
            viewModel = createViewModel(FakeRepositoryError())
            viewModel.createRoom("1", "users", "s@gmail.com", null)
            assert(viewModel.roomViewState.value is ContactsViewModel.RoomUiState.Error)
            val errorState = viewModel.roomViewState.value as ContactsViewModel.RoomUiState.Error
            assert(errorState.message == "unable to create room")
            assertEquals(ContactsViewModel.RoomCreationError.OTHER, errorState.reason)
        }

    @Test
    fun `a forbidden or unreachable invitee is told apart`() =
        runTest {
            mapOf(
                HTTP_FORBIDDEN to ContactsViewModel.RoomCreationError.NOT_ALLOWED,
                HTTP_NOT_FOUND to ContactsViewModel.RoomCreationError.UNREACHABLE
            ).forEach { (code, reason) ->
                viewModel = createViewModel(FakeRepositoryRoomCreationFails(code))
                viewModel.createRoom("1", null, "bob@other.example.com", null)
                val errorState = viewModel.roomViewState.value as ContactsViewModel.RoomUiState.Error
                assertEquals(reason, errorState.reason)
            }
        }

    @Test
    fun `the own account is not listed as contact`() =
        runTest {
            viewModel = createViewModel(FakeRepositoryWithOwnAccount())
            viewModel.getContactsFromSearchParams()
            val successState = viewModel.contactsViewState.value as ContactsViewModel.ContactsUiState.Success
            assertEquals(listOf("android", "user1_id@other.example.com"), successState.contacts?.map { it.id })
        }

    @Test
    fun `test image uri`() {
        val expectedImageUri = "https://mydomain.com/index.php/avatar/vidya/512"
        val imageUri = viewModel.getImageUri("vidya", false, false)
        assert(imageUri == expectedImageUri)
    }

    @Test
    fun `test error image uri`() {
        val expectedImageUri = "https://mydoman.com/index.php/avatar/vidya/512"
        val imageUri = viewModel.getImageUri("vidya", false, false)
        assert(imageUri != expectedImageUri)
    }

    private class FakeRepositoryRoomCreationFails(private val code: Int) :
        ContactsRepository by FakeRepositorySuccess() {
        override suspend fun createRoom(
            user: User,
            roomType: String,
            sourceType: String?,
            userId: String,
            conversationName: String?
        ): RoomOverall = throw HttpException(Response.error<RoomOverall>(code, "".toResponseBody()))
    }

    private class FakeRepositoryWithOwnAccount : ContactsRepository by FakeRepositorySuccess() {
        override suspend fun getContacts(user: User, searchQuery: String?, shareTypes: List<String>) =
            AutocompleteOverall(
                ocs = AutocompleteOCS(
                    meta = null,
                    data = listOf(
                        AutocompleteUserDto(id = "User1_ID", label = "Me", source = "users"),
                        AutocompleteUserDto(id = "android", label = "Android", source = "users"),
                        AutocompleteUserDto(id = "USER1_ID@Server1", label = "Me", source = "remotes"),
                        AutocompleteUserDto(id = "user1_id@other.example.com", label = "Namesake", source = "remotes")
                    )
                )
            )
    }

    companion object {
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
    }
}
