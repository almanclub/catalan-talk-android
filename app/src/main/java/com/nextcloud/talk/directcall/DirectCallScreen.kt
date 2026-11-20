/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import androidx.annotation.StringRes
import com.nextcloud.talk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the call screen with the [session] of a 1-1 call attempt.
 *
 * The functions may be called from any thread; the session is only used on the thread of [scope]. The properties
 * that read the session are meant for the thread of [scope].
 */
class DirectCallScreen(val session: DirectCallSession, private val scope: CoroutineScope) {
    private val roomJoinRequested = AtomicBoolean()
    private val bound = AtomicBoolean()
    private val lost = AtomicBoolean()
    private val publisherReady = AtomicBoolean()
    private val remoteReady = AtomicBoolean()

    /**
     * The fresh room session of this attempt. Once the attempt is bound to it, it is never replaced.
     */
    @Volatile
    var roomSession: String? = null

    val isBound: Boolean
        get() = bound.get()

    /**
     * Whether the screen has to close, because the attempt ended or its local waiting stopped.
     */
    val isOver: Boolean
        get() = session.isTerminal || session.stoppedLocally

    /**
     * Whether media may be left; while the attempt runs, only its business actions decide the outcome.
     */
    val mayLeaveMedia: Boolean
        get() = session.isTerminal

    /**
     * Returns true exactly once, for the caller that has to join the room with a fresh session.
     */
    fun requestRoomJoin(): Boolean = roomJoinRequested.compareAndSet(false, true)

    /**
     * Creates or accepts the attempt with [sessionId], only on the first call. [joinMedia] runs on the thread of
     * [scope] with the call ID once media may be joined.
     */
    fun prepare(sessionId: String, audioOnly: Boolean, consent: Boolean, joinMedia: (String) -> Unit) {
        if (!bound.compareAndSet(false, true)) {
            return
        }
        val mediaType = if (audioOnly) CallAttempt.MediaType.AUDIO else CallAttempt.MediaType.VIDEO
        scope.launch {
            session.prepare(sessionId, mediaType, consent) { result ->
                val callId = session.callId
                if (result.isSuccess && callId != null) {
                    joinMedia(callId)
                } else {
                    stop(result.exceptionOrNull() ?: CallAttemptException.unavailable())
                }
            }
        }
    }

    /**
     * Stops the local call after [error] without a business action; the server decides the outcome.
     */
    fun fail(error: Throwable) {
        scope.launch { stop(error) }
    }

    private fun stop(error: Throwable) {
        if (!isOver && !session.endedByUser) {
            session.fail(error)
        }
    }

    /**
     * Reports a connected peer connection: the own publisher or the media of the other participant. With an MCU both
     * are needed before media is ready.
     */
    fun mediaConnected(publisher: Boolean, hasMcu: Boolean) {
        (if (publisher) publisherReady else remoteReady).set(true)
        if (remoteReady.get() && (!hasMcu || publisherReady.get())) {
            scope.launch { session.mediaReady() }
        }
    }

    /**
     * Ends the call after the session bound to it was lost, instead of joining it again with another session.
     */
    fun connectionLost() {
        if (!lost.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            if (session.isTerminal) {
                return@launch
            }
            val state = session.snapshot?.state
            if (state == CallAttempt.State.CONNECTING || state == CallAttempt.State.CONNECTED) {
                session.finishByUser()
            }
            session.fail(CallAttemptException.unavailable())
            session.reconcile()
        }
    }

    /**
     * Checks the attempt after the own session left the call without a local hangup.
     */
    fun leftCall() {
        scope.launch {
            session.reconcile {
                if (!session.isTerminal) connectionLost()
            }
        }
    }

    /**
     * Ends the attempt for the local user, unless it already ended or stopped.
     */
    fun hangUp() {
        scope.launch {
            if (!isOver) session.finishByUser()
        }
    }

    @StringRes
    fun outcomeRes(): Int? {
        val snapshot = session.snapshot
        val error = session.error as? CallAttemptException
        return when {
            snapshot?.isTerminal == true -> DirectCallHistory.resultTitleRes(snapshot, session.initiator)
            session.isTerminal -> R.string.nc_call_history_unavailable
            error?.errorKey == CallAttemptException.ANSWERED_ELSEWHERE -> R.string.nc_call_answered_elsewhere
            session.endedByUser -> null
            error?.errorKey == CallAttemptException.UNAVAILABLE -> R.string.nc_call_no_response
            else -> R.string.nc_call_join_failed
        }
    }
}
