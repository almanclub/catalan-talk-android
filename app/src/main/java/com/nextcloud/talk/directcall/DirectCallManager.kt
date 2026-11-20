/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nextcloud.talk.api.NcApiCoroutines
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.users.UserManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Owns the [DirectCallSession]s of all accounts and retries their pending business actions.
 *
 * Every function has to be called on the main thread.
 */
@Suppress("TooManyFunctions")
@Singleton
class DirectCallManager internal constructor(
    private val backend: DirectCallBackend,
    private val store: DirectCallStore,
    override val scope: CoroutineScope,
    private val clock: () -> Long
) : DirectCallSession.Host {

    @Inject
    constructor(api: NcApiCoroutines, userManager: UserManager, context: Context) : this(
        NcDirectCallBackend(api, userManager),
        DirectCallStore(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)),
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e -> Log.e(TAG, "Direct call task failed", e) }
        ),
        SystemClock::elapsedRealtime
    ) {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = resume()
            })
        }
    }

    private val sessions = mutableListOf<DirectCallSession>()
    private val announcedCallIds = LinkedHashSet<String>()
    private val latestSequences = mutableMapOf<String, Long>()
    private val queue = DirectCallActionQueue(backend, store, scope) { sessions.toList() }
    private val mutableChanges = MutableSharedFlow<DirectCallSession>(extraBufferCapacity = CHANGES_BUFFER)

    /**
     * Emits a session whenever its snapshot or local state changed.
     */
    val changes: SharedFlow<DirectCallSession> = mutableChanges

    init {
        scope.launch {
            restoreUnfinished()
            queue.start()
        }
    }

    /**
     * Returns the callee session of the attempt [callId] announced by a push notification.
     */
    fun incoming(accountId: Long, token: String, callId: String): DirectCallSession =
        sessions.firstOrNull { it.isIn(accountId, token) && it.callId == callId }
            ?: createSession(accountId, token, initiator = false, callId = callId)

    /**
     * Returns the session shown by the call screen: [incoming], a callee session that is being accepted, or a new
     * session when call attempts are [enabled] for the room.
     */
    fun attach(
        accountId: Long,
        token: String,
        initiator: Boolean,
        enabled: Boolean,
        incoming: DirectCallSession? = null
    ): DirectCallSession? {
        val session = incoming
            ?: sessions.firstOrNull { !initiator && it.isIn(accountId, token) && it.accepting && !it.isTerminal }
            ?: if (enabled) createSession(accountId, token, initiator) else null
        session?.attachedToUI = true
        return session
    }

    fun current(accountId: Long, token: String): DirectCallSession? =
        sessions.firstOrNull { it.isIn(accountId, token) && it.attachedToUI }

    /**
     * Whether an attempt of the room may still be running, so that starting another call has to wait.
     */
    fun hasUnresolvedCall(accountId: Long, token: String): Boolean =
        sessions.any {
            it.isIn(accountId, token) && !it.isTerminal && (it.recoveredReadOnly || it.endedByUser || it.attachedToUI)
        }

    fun detach(session: DirectCallSession) {
        session.attachedToUI = false
        if (session.canDiscard) {
            sessions.remove(session)
            session.close()
            store.removeUnfinishedCall(session.clientRequestId)
        }
    }

    fun reconcile(accountId: Long, token: String? = null) {
        sessions.filter { it.accountId == accountId && (token == null || it.token == token) && !it.isTerminal }
            .forEach { it.reconcile() }
    }

    /**
     * Looks up the attempt [callId] announced by a push and returns its session, or null when the attempt has already
     * been announced. The lookup waits for one fetch of the snapshot at most.
     */
    suspend fun announceIncoming(accountId: Long, token: String, callId: String): DirectCallSession? {
        if (!announcedCallIds.add(callId)) {
            return null
        }
        if (announcedCallIds.size > MAX_ANNOUNCED_CALLS) {
            announcedCallIds.remove(announcedCallIds.first())
        }
        val session = incoming(accountId, token, callId)
        session.announcedIncoming = true
        withTimeoutOrNull(ANNOUNCE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation -> session.reconcile { continuation.resume(Unit) } }
        }
        return session
    }

    /**
     * Whether the server confirmed that the incoming [session] is ringing and this device may still ring.
     */
    fun mayRing(session: DirectCallSession): Boolean =
        session.snapshot?.state == CallAttempt.State.RINGING &&
            !session.stoppedLocally &&
            !session.endedByUser &&
            !isSuperseded(session)

    /**
     * Whether the callee missed the announced [session] and has not been told yet; afterwards it counts as told.
     */
    fun takeMissedCall(session: DirectCallSession): Boolean {
        val missed = session.snapshot?.outcome == CallAttempt.Outcome.NO_ANSWER &&
            session.announcedIncoming &&
            !session.initiator &&
            !session.acceptedLocally &&
            !session.endedByUser &&
            !session.notifiedMissed &&
            !isSuperseded(session)
        if (missed) {
            session.notifiedMissed = true
        }
        return missed
    }

    /**
     * Calls [onChange] with the announced [session] and after each of its changes, until it can neither ring nor be
     * missed anymore.
     */
    suspend fun watchIncoming(session: DirectCallSession, onChange: (DirectCallSession) -> Unit) {
        changes.onStart { emit(session) }
            .filter { it === session }
            .transformWhile {
                emit(it)
                val state = it.snapshot?.state
                !it.isTerminal && !isSuperseded(it) && (state == null || state == CallAttempt.State.RINGING)
            }
            .collect(onChange)
    }

    /**
     * Whether a newer attempt of the same room and origin has been seen.
     */
    fun isSuperseded(session: DirectCallSession): Boolean {
        val snapshot = session.snapshot ?: return false
        return (latestSequences[revisionKey(session, snapshot)] ?: snapshot.sequence) > snapshot.sequence
    }

    fun resume() {
        sessions.filter { !it.isTerminal }.forEach { it.reconcile() }
        queue.retry()
    }

    override fun uptimeMillis(): Long = clock()

    override suspend fun request(session: DirectCallSession, path: String, body: JsonObject?): CallAttempt? =
        backend.request(session.accountId, session.token, path, body)

    override fun enqueue(session: DirectCallSession, path: String, body: JsonObject) =
        queue.enqueue(session, path, body)

    override fun noteRevision(session: DirectCallSession) {
        val snapshot = session.snapshot ?: return
        val key = revisionKey(session, snapshot)
        latestSequences[key] = maxOf(latestSequences[key] ?: 0, snapshot.sequence)
    }

    override fun remember(session: DirectCallSession) {
        if (session.isTerminal) {
            store.removeUnfinishedCall(session.clientRequestId)
        } else {
            store.putUnfinishedCall(
                UnfinishedCall(
                    accountId = session.accountId,
                    token = session.token,
                    initiator = session.initiator,
                    clientRequestId = session.clientRequestId,
                    callId = session.callId
                )
            )
        }
    }

    override fun changed(session: DirectCallSession) {
        mutableChanges.tryEmit(session)
    }

    private suspend fun restoreUnfinished() {
        for (record in store.unfinishedCalls().values) {
            when {
                sessions.any { it.clientRequestId == record.clientRequestId } -> Unit
                !backend.hasAccount(record.accountId) -> store.removeUnfinishedCall(record.clientRequestId)
                else -> with(record) { newSession(accountId, token, initiator, callId, clientRequestId) }
                    .restoreForReconciliation()
            }
        }
    }

    private fun createSession(
        accountId: Long,
        token: String,
        initiator: Boolean,
        callId: String? = null
    ): DirectCallSession {
        sessions.filter { it.isTerminal && !it.attachedToUI }.forEach {
            sessions.remove(it)
            it.close()
        }
        return newSession(accountId, token, initiator, callId, UUID.randomUUID().toString())
    }

    private fun newSession(
        accountId: Long,
        token: String,
        initiator: Boolean,
        callId: String?,
        clientRequestId: String
    ): DirectCallSession =
        DirectCallSession(accountId, token, initiator, callId, clientRequestId, this).also(sessions::add)

    private fun revisionKey(session: DirectCallSession, snapshot: CallAttempt): String =
        listOf(session.accountId, session.token, snapshot.origin).joinToString("\n")

    private fun DirectCallSession.isIn(accountId: Long, token: String): Boolean =
        this.accountId == accountId && this.token == token

    companion object {
        private val TAG = DirectCallManager::class.java.simpleName
        private const val PREFERENCES = "catalan_direct_calls"
        private const val CHANGES_BUFFER = 64
        private const val ANNOUNCE_TIMEOUT_MS = 10000L
        private const val MAX_ANNOUNCED_CALLS = 100

        fun isEnabled(conversation: ConversationModel?, user: User?): Boolean =
            DirectCallHistory.isDirectRoom(conversation) && DirectCallHistory.isSupported(user)
    }
}
