/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Sowjanya Kota <sowjanya.kch@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.contacts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.talk.R
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.logger.Logger
import com.nextcloud.talk.models.json.autocomplete.AutocompleteUserDto
import com.nextcloud.talk.models.json.conversations.ConversationDto
import com.nextcloud.talk.utils.ApiUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

@Suppress("TooManyFunctions")
class ContactsViewModel @AssistedInject constructor(
    private val repository: ContactsRepository,
    private val logger: Logger,
    @Assisted val currentUser: User
) : ViewModel() {

    private val _contactsViewState = MutableStateFlow<ContactsUiState>(ContactsUiState.None)
    val contactsViewState: StateFlow<ContactsUiState> = _contactsViewState
    private val _roomViewState = MutableStateFlow<RoomUiState>(RoomUiState.None)
    val roomViewState: StateFlow<RoomUiState> = _roomViewState
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    private val shareTypes: MutableList<String> = mutableListOf(ShareType.User.shareType)
    val shareTypeList: List<String> = shareTypes
    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive
    private val selectedParticipants = MutableStateFlow<List<AutocompleteUserDto>>(emptyList())
    val selectedParticipantsList: StateFlow<List<AutocompleteUserDto>> = selectedParticipants.asStateFlow()
    private val _isAddParticipantsView = MutableStateFlow(false)
    val isAddParticipantsView: StateFlow<Boolean> = _isAddParticipantsView

    private val _enableAddButton = MutableStateFlow(false)
    val enableAddButton: StateFlow<Boolean> = _enableAddButton

    @Suppress("PropertyName")
    private val _selectedContacts = MutableStateFlow<List<AutocompleteUserDto>>(emptyList())

    @Suppress("PropertyName")
    private val _clickAddButton = MutableStateFlow(false)

    private var hideAlreadyAddedParticipants: Boolean = false

    @AssistedFactory
    interface Factory {
        fun build(user: User): ContactsViewModel
    }

    companion object {
        private val TAG = ContactsViewModel::class.java.simpleName
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun modifyClickAddButton(value: Boolean) {
        _clickAddButton.value = value
    }

    fun selectContact(contact: AutocompleteUserDto) {
        val updatedParticipants = selectedParticipants.value + contact
        selectedParticipants.value = updatedParticipants
        _selectedContacts.value = _selectedContacts.value + contact
    }

    fun updateAddButtonState() {
        _enableAddButton.value = _selectedContacts.value.isNotEmpty()
    }

    fun deselectContact(contact: AutocompleteUserDto) {
        val updatedParticipants = selectedParticipants.value - contact
        selectedParticipants.value = updatedParticipants
        _selectedContacts.value = _selectedContacts.value - contact
    }

    fun updateSelectedParticipants(participants: List<AutocompleteUserDto>) {
        selectedParticipants.value = participants
    }
    fun setSearchActive(searchState: Boolean) {
        _isSearchActive.value = searchState
    }

    fun updateShareTypes(value: List<String>) {
        shareTypes.addAll(value.filterNot { it in shareTypes })
    }

    fun updateIsAddParticipants(value: Boolean) {
        _isAddParticipantsView.value = value
    }

    fun hideAlreadyAddedParticipants(value: Boolean) {
        hideAlreadyAddedParticipants = value
    }

    @Suppress("Detekt.TooGenericExceptionCaught")
    fun getContactsFromSearchParams(query: String = "") {
        val user = currentUser
        _contactsViewState.value = ContactsUiState.Loading
        viewModelScope.launch {
            try {
                val contacts = repository.getContacts(
                    user,
                    if (query != "") query else searchQuery.value,
                    shareTypeList
                )
                _contactsViewState.value = ContactsUiState.Success(contactsToShow(contacts.ocs!!.data))
            } catch (exception: Exception) {
                logger.e(TAG, "Failed to fetch contacts for search", exception)
                _contactsViewState.value = ContactsUiState.Error(exception.message ?: "")
                if (exception is CancellationException) {
                    throw exception
                }
            }
        }
    }

    @Suppress("Detekt.TooGenericExceptionCaught")
    suspend fun getBlockingContactsFromSearchParams(query: String = "") =
        withContext(Dispatchers.IO) {
            val user = currentUser
            _contactsViewState.value = ContactsUiState.Loading
            try {
                val contacts = repository.getContacts(
                    user,
                    if (query != "") query else searchQuery.value,
                    shareTypeList
                )
                _contactsViewState.value = ContactsUiState.Success(contactsToShow(contacts.ocs!!.data))
            } catch (exception: Exception) {
                logger.e(TAG, "Failed to fetch contacts for search", exception)
                _contactsViewState.value = ContactsUiState.Error(exception.message ?: "")
                if (exception is CancellationException) {
                    throw exception
                }
            }
        }

    /**
     * Resets [roomViewState] once its result was handled, so a recreated screen doesn't handle it again.
     */
    fun clearRoomState() {
        _roomViewState.value = RoomUiState.None
    }

    private fun contactsToShow(found: List<AutocompleteUserDto>?): List<AutocompleteUserDto>? {
        val contactsList = found?.withoutOwnAccount(currentUser)?.toMutableList()
        if (hideAlreadyAddedParticipants && !_clickAddButton.value) {
            contactsList?.removeAll(selectedParticipants.value)
        }
        if (_clickAddButton.value) {
            contactsList?.removeAll(selectedParticipants.value)
            contactsList?.addAll(_selectedContacts.value)
        }
        return contactsList
    }

    @Suppress("Detekt.TooGenericExceptionCaught")
    fun createRoom(roomType: String, sourceType: String?, userId: String, conversationName: String?) {
        val user = currentUser
        viewModelScope.launch {
            try {
                val room = repository.createRoom(
                    user,
                    roomType,
                    sourceType,
                    userId,
                    conversationName
                )

                val conversation: ConversationDto? = room.ocs?.data
                _roomViewState.value = RoomUiState.Success(user.id!!, conversation)
            } catch (exception: Exception) {
                logger.e(TAG, "Failed to create room", exception)
                _roomViewState.value = RoomUiState.Error(exception.message ?: "", RoomCreationError.from(exception))
            }
        }
    }

    fun getImageUri(avatarId: String, requestBigSize: Boolean, isDarkMode: Boolean): String =
        repository.getImageUri(
            currentUser,
            avatarId,
            requestBigSize,
            isDarkMode
        )

    fun getFederatedImageUri(cloudId: String, isDarkMode: Boolean): String =
        ApiUtils.getUrlForFederatedAvatarWithoutRoom(
            currentUser.baseUrl!!,
            cloudId,
            if (isDarkMode) 1 else 0,
            true
        )

    sealed class ContactsUiState {
        data object None : ContactsUiState()
        data object Loading : ContactsUiState()
        data class Success(val contacts: List<AutocompleteUserDto>?) : ContactsUiState()
        data class Error(val message: String) : ContactsUiState()
    }

    sealed class RoomUiState {
        data object None : RoomUiState()
        data class Success(val userId: Long, val conversation: ConversationDto?) : RoomUiState()
        data class Error(val message: String, val reason: RoomCreationError = RoomCreationError.OTHER) :
            RoomUiState()
    }

    enum class RoomCreationError(@StringRes val message: Int) {
        NOT_ALLOWED(R.string.nc_new_conversation_not_allowed),
        UNREACHABLE(R.string.nc_new_conversation_unreachable),
        OTHER(R.string.nc_common_error_sorry);

        companion object {
            fun from(exception: Exception): RoomCreationError =
                when ((exception as? HttpException)?.code()) {
                    HTTP_FORBIDDEN -> NOT_ALLOWED
                    HTTP_NOT_FOUND -> UNREACHABLE
                    else -> OTHER
                }
        }
    }
}
