package com.example.tools

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable context representing a single user interaction turn.
 * Binds the unique userTurnId, authoritative transcript, and toolCallId into
 * an immutable decision envelope to prevent stale or race-conditioned tool execution.
 */
data class UserTurnContext(
    val userTurnId: String,
    val transcript: String?,
    val toolCallId: String,
    private val consumedFlag: AtomicBoolean? = null
) {
    /**
     * Atomically marks this user turn context as consumed by a tool execution.
     * Returns true if successfully marked, false if it was ALREADY consumed.
     */
    fun markConsumed(): Boolean {
        return consumedFlag?.compareAndSet(false, true) ?: true
    }

    val isConsumed: Boolean
        get() = consumedFlag?.get() ?: false
}
