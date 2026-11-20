/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.directcall

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * Client side of one 1-1 call attempt (catalan-call-attempts-v1).
 *
 * The server owns the attempt: a session only sends the business actions of the local user and applies the snapshots
 * of the server. A local timeout or a failed request stops the local UI ([stoppedLocally]) but never sends a cancel or
 * reject. Not thread safe; only use it on the thread of [Host.scope].
 */
@Suppress("TooManyFunctions")
class DirectCallSession internal constructor(
    val accountId: Long,
    val token: String,
    val initiator: Boolean,
    callId: String?,
    val clientRequestId: String,
    private val host: Host
) {
    interface Host {
        val scope: CoroutineScope

        fun uptimeMillis(): Long

        suspend fun request(session: DirectCallSession, path: String, body: JsonObject?): CallAttempt?

        fun enqueue(session: DirectCallSession, path: String, body: JsonObject)

        fun noteRevision(session: DirectCallSession)

        fun remember(session: DirectCallSession)

        fun changed(session: DirectCallSession)
    }

    var callId: String? = callId
        private set
    var snapshot: CallAttempt? = null
        private set
    var sessionId: String? = null
        private set
    var acceptedLocally = false
        private set
    var accepting = false
        private set
    var stoppedLocally = false
        private set
    var endedByUser = false
        private set
    var recoveredReadOnly = false
        private set
    var error: Throwable? = null
        private set
    var attachedToUI = false
    var announcedIncoming = false
    var notifiedMissed = false

    private var resolvedAbsent = false
    private var createSent = false
    private var readySent = false
    private var readyRequested = false
    private var fetching = false
    private var endEnqueued = false
    private var acceptRequestInFlight = false
    private var consecutiveFailures = 0
    private var nextRequestAt = 0L
    private var snapshotAt = 0L
    private var createParameters: JsonObject? = null
    private var acceptParameters: JsonObject? = null
    private var prepareCompletion: ((Result<Unit>) -> Unit)? = null
    private val reconcileCompletions = mutableListOf<() -> Unit>()
    private var polling: Job? = null
    private var deadlineTimer: Job? = null
    private var resolutionTimer: Job? = null

    val isTerminal: Boolean
        get() = snapshot?.isTerminal == true || resolvedAbsent

    val canDiscard: Boolean
        get() = isTerminal || (!createSent && callId == null)

    /**
     * How long the attempt has been connected, extrapolated from the server time of the last snapshot.
     */
    val connectedDuration: Duration?
        get() = snapshot?.takeIf { it.state == CallAttempt.State.CONNECTED }?.let { attempt ->
            attempt.connectedAt?.let { connectedAt ->
                val elapsed = Duration.between(connectedAt, attempt.serverTime)
                    .plusMillis(host.uptimeMillis() - snapshotAt)
                maxOf(elapsed, Duration.ZERO)
            }
        }

    private val mayReportMediaReady: Boolean
        get() = !readySent && !stoppedLocally && !endedByUser && snapshot?.state == CallAttempt.State.CONNECTING

    fun restoreForReconciliation() {
        recoveredReadOnly = true
        stoppedLocally = true
        reconcile()
    }

    fun beginAccepting() {
        accepting = true
    }

    /**
     * Creates (caller) or accepts (callee) the attempt with [sessionId], the fresh room session that has already
     * joined signaling. [completion] succeeds once media may be joined with [callId].
     */
    fun prepare(
        sessionId: String,
        mediaType: CallAttempt.MediaType,
        consent: Boolean,
        completion: (Result<Unit>) -> Unit
    ) {
        if (this.sessionId?.let { it != sessionId } == true || endedByUser || stoppedLocally) {
            completion(Result.failure(CallAttemptException.unavailable()))
            return
        }
        this.sessionId = sessionId
        Log.d(TAG, "Preparing ${if (initiator) "create" else "accept"} of $clientRequestId")
        host.remember(this)
        prepareCompletion = completion
        when {
            initiator && createSent -> reconcile()
            initiator -> {
                createSent = true
                val body = buildJsonObject {
                    put(CLIENT_REQUEST_ID, clientRequestId)
                    put(MEDIA_TYPE, mediaType.wireValue)
                    put(SESSION_ID, sessionId)
                    put(RECORDING_CONSENT, consent)
                }
                request("", body, ::prepared)
            }
            else -> {
                // Receiving the push is not an accept; the attempt has to be ringing on the server.
                accepting = true
                reconcile { acceptIfRinging(sessionId, consent) }
            }
        }
        startPolling()
    }

    private fun acceptIfRinging(sessionId: String, consent: Boolean) {
        val ringing = snapshot?.takeIf { it.state == CallAttempt.State.RINGING }
        if (endedByUser || stoppedLocally || ringing == null) {
            completePreparation(Result.failure(error ?: CallAttemptException.unavailable()))
        } else {
            acceptParameters = buildJsonObject {
                put(SESSION_ID, sessionId)
                put(RECORDING_CONSENT, consent)
            }
            sendAccept(ringing.callId)
        }
    }

    private fun prepared(result: Result<CallAttempt?>) {
        val failure = result.exceptionOrNull()
        val attempt = result.getOrNull()
        when {
            failure != null -> {
                handle(failure)
                // A failed response does not prove that the create or accept was not committed.
                if (failure.isRecoverable()) reconcile() else completePreparation(Result.failure(failure))
            }
            attempt == null -> fail(CallAttemptException.invalidResponse())
            else -> {
                apply(attempt)
                if (endedByUser) {
                    finishByUser()
                    completePreparation(Result.failure(CallAttemptException.unavailable()))
                } else if (attempt.isTerminal) {
                    completePreparation(Result.failure(CallAttemptException.unavailable()))
                } else {
                    acceptedLocally = !initiator
                    accepting = false
                    completePreparation(Result.success(Unit))
                }
            }
        }
    }

    private fun sendAccept(callId: String) {
        val parameters = acceptParameters
        if (parameters == null || acceptRequestInFlight || endedByUser || stoppedLocally) {
            return
        }
        acceptRequestInFlight = true
        request("/$callId/accept", parameters) { result ->
            acceptRequestInFlight = false
            prepared(result)
        }
    }

    private fun completePreparation(result: Result<Unit>) {
        val completion = prepareCompletion ?: return
        prepareCompletion = null
        completion(result)
    }

    /**
     * Fetches the authoritative snapshot, unless a fetch is running or the session is backing off. [completion] runs
     * after the next fetch finished.
     */
    fun reconcile(completion: (() -> Unit)? = null) {
        completion?.let(reconcileCompletions::add)
        if (fetching || host.uptimeMillis() < nextRequestAt) {
            return
        }
        startPolling()
        if (snapshot == null && resolutionTimer == null) {
            resolutionTimer = host.scope.launch {
                delay(RESOLUTION_TIMEOUT_MS)
                if (snapshot == null) fail(CallAttemptException.unavailable())
            }
        }
        fetching = true
        request(callId?.let { "/$it" } ?: CallAttemptResponses.ACTIVE, null, ::reconciled)
    }

    private fun reconciled(result: Result<CallAttempt?>) {
        fetching = false
        val failure = result.exceptionOrNull()
        val attempt = result.getOrNull()
        when {
            failure != null -> handle(failure)
            attempt != null -> reconciledWith(attempt)
            recoveredReadOnly -> resolveAbsent()
            callId != null || !initiator -> fail(CallAttemptException.invalidResponse())
            createSent && !endedByUser -> retryCreateForCorrelation()
        }
        val completions = reconcileCompletions.toList()
        reconcileCompletions.clear()
        completions.forEach { it() }
    }

    private fun reconciledWith(attempt: CallAttempt) {
        if (callId == null && initiator && !recoveredReadOnly) {
            // /active may return an unrelated attempt after an ambiguous create; only the create response correlates.
            if (endedByUser) finishByUser() else retryCreateForCorrelation()
            return
        }
        apply(attempt)
        if (endedByUser) finishByUser()
        if (!initiator && accepting && prepareCompletion != null && !attempt.isTerminal) {
            sendAccept(attempt.callId)
        }
    }

    private fun retryCreateForCorrelation() {
        val parameters = createParameters
        if (parameters != null && !endedByUser && !stoppedLocally) {
            request("", parameters, ::prepared)
        }
    }

    /**
     * Ends a session for which the server confirmed that no attempt exists or will be created.
     */
    fun resolveAbsent() {
        resolvedAbsent = true
        close()
        host.remember(this)
        host.changed(this)
    }

    private fun request(path: String, body: JsonObject?, completion: (Result<CallAttempt?>) -> Unit) {
        if (callId != null && !CallAttempt.isCallId(callId)) {
            completion(Result.failure(CallAttemptException.invalidResponse()))
            return
        }
        if (path.isEmpty() && body != null) {
            createParameters = body
        }
        host.scope.launch {
            val result = try {
                Result.success(host.request(this@DirectCallSession, path, body))
            } catch (e: CallAttemptException) {
                Result.failure(e)
            }
            completion(result)
        }
    }

    /**
     * Applies a snapshot of this attempt, ignoring other attempts and revisions older than the current one.
     */
    fun apply(attempt: CallAttempt) {
        val previous = snapshot
        if (callId?.let { it != attempt.callId } == true) {
            return
        }
        if (previous != null && !attempt.supersedes(previous) && !attempt.refreshes(previous)) {
            return
        }
        callId = attempt.callId
        snapshot = attempt
        host.noteRevision(this)
        if (attachedToUI || recoveredReadOnly) host.remember(this)
        consecutiveFailures = 0
        nextRequestAt = 0
        resolutionTimer?.cancel()
        resolutionTimer = null
        snapshotAt = host.uptimeMillis()
        if (!stoppedLocally) error = null
        armDeadline(attempt)
        if (isTerminal) {
            polling?.cancel()
            deadlineTimer?.cancel()
            completePreparation(Result.failure(CallAttemptException.unavailable()))
        } else {
            startPolling()
            if (initiator && prepareCompletion != null && createSent && !endedByUser) {
                completePreparation(Result.success(Unit))
            }
        }
        host.changed(this)
    }

    /**
     * Whether this is the same revision as [previous], fetched again with a newer server time.
     */
    private fun CallAttempt.refreshes(previous: CallAttempt): Boolean =
        copy(serverTime = previous.serverTime) == previous && serverTime >= previous.serverTime

    private fun armDeadline(attempt: CallAttempt) {
        deadlineTimer?.cancel()
        val remaining = attempt.remainingTime
        if (remaining == null || stoppedLocally) {
            return
        }
        deadlineTimer = host.scope.launch {
            delay(max(MIN_DEADLINE_DELAY_MS, remaining.toMillis()))
            // Only the local waiting stops; the server alone decides that nobody answered.
            stoppedLocally = true
            error = CallAttemptException.unavailable()
            host.changed(this@DirectCallSession)
            reconcile()
        }
    }

    /**
     * Reports that media is connected. Sent once while connecting; when requested before, it is sent as soon as the
     * attempt is connecting.
     */
    fun mediaReady() {
        readyRequested = true
        val id = callId
        val session = sessionId
        if (id == null || session == null || !mayReportMediaReady) {
            return
        }
        readySent = true
        request("/$id/media-ready", buildJsonObject { put(SESSION_ID, session) }) { result ->
            result.onSuccess { attempt -> attempt?.let(::apply) }
            result.onFailure {
                readySent = false
                handle(it)
            }
        }
    }

    /**
     * Ends the attempt for the local user: cancels or rejects while ringing, ends once answered. The action is
     * retried by the host until the server answered it.
     */
    fun finishByUser() {
        endedByUser = true
        deadlineTimer?.cancel()
        completePreparation(Result.failure(CallAttemptException.unavailable()))
        val path = finishPath() ?: return
        if (path == CallAttemptResponses.CANCEL_REQUEST) {
            host.enqueue(this, path, buildJsonObject { put(CLIENT_REQUEST_ID, clientRequestId) })
        } else if (!endEnqueued) {
            endEnqueued = true
            host.enqueue(this, path, buildJsonObject { sessionId?.let { put(SESSION_ID, it) } })
        }
    }

    private fun finishPath(): String? {
        val id = callId
        val state = snapshot?.state
        return when {
            isTerminal -> null
            id == null -> CallAttemptResponses.CANCEL_REQUEST.takeIf { initiator && createSent }
            state == null || state == CallAttempt.State.RINGING -> "/$id/${if (initiator) "cancel" else "reject"}"
            initiator || acceptedLocally -> "/$id/end"
            else -> null
        }
    }

    /**
     * Ends the attempt after a cancel lost the race against the accept of the callee.
     */
    fun finishAfterCancelRace() {
        val answered = snapshot?.state.let { it == CallAttempt.State.CONNECTING || it == CallAttempt.State.CONNECTED }
        if (endedByUser && initiator && answered) {
            endEnqueued = false
            finishByUser()
        }
    }

    fun handle(failure: Throwable) {
        val ocs = (failure as? CallAttemptException)?.takeIf { it.isOcs }
        if (ocs != null && ocs.isRecoverable) {
            consecutiveFailures++
            val backoff = min(MAX_BACKOFF_SECONDS, 1L shl min(consecutiveFailures, MAX_BACKOFF_EXPONENT))
            nextRequestAt = host.uptimeMillis() + max(ocs.retryAfterSeconds ?: 0, backoff) * MILLIS_PER_SECOND
        }
        Log.w(TAG, "Call attempt request failed; status ${ocs?.statusCode ?: 0}, error ${ocs?.errorKey}", failure)
        val embedded = ocs?.attempt
        if (callId != null && embedded != null) {
            try {
                apply(CallAttempt.fromJson(embedded))
            } catch (e: IllegalArgumentException) {
                fail(e)
            }
        }
        if (!isTerminal) error = failure
        if (stopsLocally(ocs)) {
            stoppedLocally = true
            completePreparation(Result.failure(failure))
        }
        if (ocs?.statusCode == CallAttemptException.HTTP_NOT_FOUND && (recoveredReadOnly || callId != null)) {
            resolveAbsent()
        }
        host.changed(this)
    }

    private fun stopsLocally(ocs: CallAttemptException?): Boolean =
        when {
            ocs == null -> true
            ocs.errorKey == CallAttemptException.ANSWERED_ELSEWHERE -> true
            ocs.statusCode == CallAttemptException.HTTP_NOT_FOUND -> true
            else -> !ocs.isRecoverable && snapshot == null
        }

    fun fail(failure: Throwable) {
        error = failure
        stoppedLocally = true
        Log.w(TAG, "Stopped local call attempt $clientRequestId", failure)
        completePreparation(Result.failure(failure))
        host.changed(this)
    }

    /**
     * Stops all timers of a session that is no longer used.
     */
    fun close() {
        polling?.cancel()
        deadlineTimer?.cancel()
        resolutionTimer?.cancel()
    }

    private fun startPolling() {
        if (polling?.isActive == true) {
            return
        }
        polling = host.scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                reconcile()
                if (readyRequested) mediaReady()
            }
        }
    }

    private fun Throwable.isRecoverable(): Boolean = (this as? CallAttemptException)?.isRecoverable == true

    companion object {
        private val TAG = DirectCallSession::class.java.simpleName
        private const val POLL_INTERVAL_MS = 2000L
        private const val RESOLUTION_TIMEOUT_MS = 10000L
        private const val MIN_DEADLINE_DELAY_MS = 10L
        private const val MAX_BACKOFF_SECONDS = 30L
        private const val MAX_BACKOFF_EXPONENT = 5
        private const val MILLIS_PER_SECOND = 1000L
        private const val CLIENT_REQUEST_ID = "clientRequestId"
        private const val MEDIA_TYPE = "mediaType"
        private const val SESSION_ID = "sessionId"
        private const val RECORDING_CONSENT = "recordingConsent"
    }
}
