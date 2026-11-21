/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.account.viewmodels

import com.nextcloud.talk.account.data.LoginRepository
import com.nextcloud.talk.account.data.model.LoginCompletion
import com.nextcloud.talk.account.data.model.LoginResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserLoginActivityViewModelTest {

    private val repository: LoginRepository = mock()
    private lateinit var viewModel: BrowserLoginActivityViewModel

    private val loginResponse = LoginResponse(token = "token", pollUrl = "https://example.com/poll", loginUrl = URL)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = BrowserLoginActivityViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starting the login again continues the started login`() {
        wheneverBlocking { repository.startLoginFlow(any(), any(), anyOrNull()) }.thenReturn(loginResponse)

        viewModel.startWebBrowserLogin(BASE_URL)
        viewModel.startWebBrowserLogin(BASE_URL)

        verifyBlocking(repository, times(1)) { startLoginFlow(any(), any(), anyOrNull()) }
        assertEquals(
            BrowserLoginActivityViewModel.InitialLoginViewState.InitialLoginRequestSuccess(URL),
            viewModel.initialLoginRequestState.value
        )
    }

    @Test
    fun `browser login completes once without waiting for the activity to resume`() {
        val granted = CompletableDeferred<LoginCompletion?>()
        val completion = LoginCompletion(200, BASE_URL, "user", "app-password")
        wheneverBlocking { repository.startLoginFlow(any(), any(), anyOrNull()) }.thenReturn(loginResponse)
        wheneverBlocking { repository.pollLogin(loginResponse) }.doSuspendableAnswer { granted.await() }
        wheneverBlocking { repository.parseAndLogin(completion) }
            .thenReturn(LoginRepository.LoginResult.ExistingAccount)

        viewModel.startWebBrowserLogin(BASE_URL)
        viewModel.startWebBrowserLogin(BASE_URL)

        verifyBlocking(repository, times(1)) { pollLogin(loginResponse) }
        verifyBlocking(repository, never()) { parseAndLogin(any()) }
        assertEquals(
            BrowserLoginActivityViewModel.PostLoginViewState.None,
            viewModel.postLoginState.value
        )

        granted.complete(completion)
        viewModel.startWebBrowserLogin(BASE_URL)

        assertEquals(
            BrowserLoginActivityViewModel.PostLoginViewState.PostLoginRestartApp,
            viewModel.postLoginState.value
        )
        verifyBlocking(repository, times(1)) { startLoginFlow(any(), any(), anyOrNull()) }
        verifyBlocking(repository, times(1)) { pollLogin(loginResponse) }
        verifyBlocking(repository, times(1)) { parseAndLogin(completion) }
    }

    @Test
    fun `a QR code login is not started twice either`() {
        wheneverBlocking { repository.startOTPLoginFlow(any(), any(), anyOrNull()) }.thenReturn(null)

        viewModel.loginWithOTPQR("qr")
        viewModel.loginWithOTPQR("qr")

        verifyBlocking(repository, times(1)) { startOTPLoginFlow(any(), any(), anyOrNull()) }
    }

    @Test
    fun `a login refused because of too many failed logins is reported as such`() {
        wheneverBlocking { repository.startOTPLoginFlow(any(), any(), anyOrNull()) }
            .thenReturn(LoginCompletion(LoginRepository.HTTP_TOO_MANY_REQUESTS, BASE_URL, "user", ""))

        viewModel.loginWithOTPQR("qr")

        assertEquals(
            BrowserLoginActivityViewModel.PostLoginViewState.PostLoginTooManyLoginAttempts,
            viewModel.postLoginState.value
        )
        verifyBlocking(repository, never()) { parseAndLogin(any()) }
    }

    companion object {
        private const val BASE_URL = "https://example.com"
        private const val URL = "https://example.com/login"
    }
}
