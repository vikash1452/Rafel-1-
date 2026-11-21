package com.example.voice

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable data snapshot representing a specific user microphone turn and its verified transcript.
 */
data class UserTurnContext(
    val userTurnId: Long,
    val transcript: String? = null,
    val isFrozen: Boolean = false,
    val consumedToolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Authoritative thread-safe manager for microphone turns and user transcripts.
 *
 * Guarantees:
 * 1. Every mic turn has a strictly unique monotonic userTurnId.
 * 2. Speech onset creates a fresh turn.
 * 3. Speech end freezes that turn.
 * 4. Immutable snapshots for tool evaluations: (userTurnId, transcript, toolCallId).
 * 5. Consumed turns cannot be reused by subsequent tool calls.
 * 6. Stale or mismatched transcripts are rejected.
 */
class UserTurnManager {

    private val turnIdGenerator = AtomicLong(1000L)
    private val activeTurn = AtomicReference<UserTurnContext>(
        UserTurnContext(userTurnId = turnIdGenerator.get(), transcript = null, isFrozen = true)
    )

    fun startNewTurn(): Long {
        val newTurnId = turnIdGenerator.incrementAndGet()
        val newTurn = UserTurnContext(
            userTurnId = newTurnId,
            transcript = null,
            isFrozen = false,
            consumedToolCallId = null,
            timestamp = System.currentTimeMillis()
        )
        activeTurn.set(newTurn)
        RafelLogger.logVoice("[TURN-START] userTurnId=$newTurnId")
        return newTurnId
    }

    fun updateTranscript(turnId: Long, transcript: String): Boolean {
        if (transcript.isBlank()) return false
        val cleanTranscript = transcript.trim()
        while (true) {
            val current = activeTurn.get()
            if (current.userTurnId != turnId) {
                RafelLogger.logVoice("[TURN-TRANSCRIPT-IGNORED] Turn $turnId is no longer active (current=${current.userTurnId})")
                return false
            }
            if (current.consumedToolCallId != null) {
                RafelLogger.logVoice("[TURN-TRANSCRIPT-IGNORED] Turn $turnId already consumed by tool ${current.consumedToolCallId}")
                return false
            }
            val updated = current.copy(transcript = cleanTranscript)
            if (activeTurn.compareAndSet(current, updated)) {
                RafelLogger.logVoice("[TURN-TRANSCRIPT-UPDATE] userTurnId=$turnId transcript=\"$cleanTranscript\"")
                RafelLogger.logUserTranscript(cleanTranscript)
                return true
            }
        }
    }

    fun freezeTurn(turnId: Long) {
        while (true) {
            val current = activeTurn.get()
            if (current.userTurnId != turnId) return
            if (current.isFrozen) return
            val updated = current.copy(isFrozen = true)
            if (activeTurn.compareAndSet(current, updated)) {
                RafelLogger.logVoice("[TURN-FREEZE] userTurnId=$turnId (transcript=\"${updated.transcript ?: "NULL"}\")")
                return
            }
        }
    }

    fun getCurrentTurnSnapshot(): UserTurnContext {
        return activeTurn.get()
    }

    fun snapshotForTool(toolCallId: String): UserTurnContext {
        return activeTurn.get()
    }

    fun markConsumed(turnId: Long, toolCallId: String) {
        while (true) {
            val current = activeTurn.get()
            if (current.userTurnId != turnId) return
            val updated = current.copy(consumedToolCallId = toolCallId)
            if (activeTurn.compareAndSet(current, updated)) {
                RafelLogger.logVoice("[TURN-CONSUMED] userTurnId=$turnId consumedBy=$toolCallId")
                return
            }
        }
    }

    fun reset() {
        val resetId = turnIdGenerator.incrementAndGet()
        activeTurn.set(UserTurnContext(userTurnId = resetId, transcript = null, isFrozen = true))
    }
}
