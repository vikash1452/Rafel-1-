package com.example.ai.router

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Manages intelligent model fallback transitions, hysteresis-based performance upgrades,
 * anti-reconnect-loop protection, and mid-conversation safety guards.
 */
class ModelFallbackManager(
    private val registry: AIModelRegistry,
    private val capabilityManager: ModelCapabilityManager,
    private val healthMonitor: ModelHealthMonitor,
    private val latencyTracker: ModelLatencyTracker
) {

    companion object {
        // Minimum latency advantage (ms) and ratio required to switch away from a healthy active model
        const val HYSTERESIS_MIN_ADVANTAGE_MS = 150L
        const val HYSTERESIS_MIN_IMPROVEMENT_RATIO = 0.18 // 18% faster

        // Anti-reconnect loop window: max 4 fallbacks within 20 seconds
        const val LOOP_GUARD_WINDOW_MS = 20_000L
        const val MAX_FALLBACKS_IN_WINDOW = 4
    }

    private val _fallbackHistory = MutableStateFlow<List<FallbackEvent>>(emptyList())
    val fallbackHistory: StateFlow<List<FallbackEvent>> = _fallbackHistory.asStateFlow()

    private val _lastFallbackEvent = MutableStateFlow<FallbackEvent?>(null)
    val lastFallbackEvent: StateFlow<FallbackEvent?> = _lastFallbackEvent.asStateFlow()

    private val recentSwitchTimestamps = ConcurrentLinkedDeque<Long>()

    @Volatile
    private var pendingSafeUpgradeModelId: String? = null

    /**
     * Selects the next healthy fallback model when [failedModelId] fails or times out.
     * Records the failure in [ModelHealthMonitor] & [ModelLatencyTracker] and logs a [FallbackEvent].
     */
    @Synchronized
    fun onModelFailedAndSelectNext(
        taskType: ModelTaskType,
        failedModelId: String,
        reason: String,
        httpOrWsCode: Int = 0,
        hasApiKey: Boolean = true,
        elapsedBeforeFailureMs: Long = 0L,
        baseCooldownMs: Long = 4000L
    ): AIModelDescriptor? {
        // 1. Record failure in latency tracker and health monitor
        latencyTracker.recordFailure(failedModelId, elapsedBeforeFailureMs)
        healthMonitor.recordModelFailure(
            modelId = failedModelId,
            reason = reason,
            statusCode = httpOrWsCode,
            baseCooldownMs = baseCooldownMs
        )

        // 2. Check anti-loop guard
        val now = System.currentTimeMillis()
        while (recentSwitchTimestamps.peekFirst()?.let { now - it > LOOP_GUARD_WINDOW_MS } == true) {
            recentSwitchTimestamps.pollFirst()
        }
        if (recentSwitchTimestamps.size >= MAX_FALLBACKS_IN_WINDOW) {
            return null
        }

        // 3. Find compatible models excluding the failed one
        val offlineVerified = healthMonitor.refreshOfflineModelState().isWeightFilePresent
        val candidates = capabilityManager.getCompatibleModels(
            taskType = taskType,
            isOfflineWeightVerified = offlineVerified
        ).filter { candidate ->
            !candidate.modelId.equals(failedModelId, ignoreCase = true) &&
                !candidate.restModelName.equals(failedModelId.removePrefix("models/"), ignoreCase = true) &&
                healthMonitor.isModelAvailableForRouting(candidate, hasApiKey = hasApiKey)
        }

        val sortedCandidates = candidates.sortedWith(
            compareBy<AIModelDescriptor> { candidate ->
                val measured = latencyTracker.getEffectiveScoreMs(
                    model = candidate,
                    taskType = taskType,
                    availabilityPercent = healthMonitor.getAvailabilityPercent(candidate.modelId)
                )
                if (measured > 0L) measured else 100_000L + candidate.priorityRank
            }.thenBy { it.priorityRank }
        )

        val nextModel = sortedCandidates.firstOrNull() ?: return null

        // 4. Record fallback event
        recentSwitchTimestamps.addLast(now)
        val event = FallbackEvent(
            id = "fb_${UUID.randomUUID().toString().take(8)}",
            timestampMs = now,
            taskType = taskType,
            fromModelId = failedModelId,
            toModelId = nextModel.modelId,
            reason = reason,
            httpOrWsCode = httpOrWsCode,
            contextPreserved = true
        )
        appendFallbackEvent(event)
        return nextModel
    }

    /**
     * Evaluates whether RAFEL should proactively switch from [currentModelId] to [candidateModel]
     * based on superior measured latency.
     *
     * Respects:
     * - Active conversation safety: never switches during an active turn; queues as pending upgrade instead.
     * - Hysteresis threshold: only switches if both models have real measured latency and the candidate
     *   is meaningfully faster (at least 150ms & 18% faster).
     */
    fun shouldSwitchForPerformance(
        taskType: ModelTaskType,
        currentModelId: String,
        candidateModel: AIModelDescriptor,
        isTurnActive: Boolean
    ): Boolean {
        if (currentModelId == candidateModel.modelId ||
            currentModelId.removePrefix("models/") == candidateModel.restModelName
        ) {
            return false
        }

        val currentDescriptor = registry.getModel(currentModelId) ?: return !isTurnActive

        // If current model is unhealthy or offline, switch as soon as safe
        val currentHealth = healthMonitor.getHealthState(currentDescriptor.modelId)
        if (currentHealth == ModelHealthState.UNHEALTHY || currentHealth == ModelHealthState.OFFLINE) {
            if (isTurnActive) {
                pendingSafeUpgradeModelId = candidateModel.modelId
                return false
            }
            return true
        }

        val currentScore = latencyTracker.getEffectiveScoreMs(
            model = currentDescriptor,
            taskType = taskType,
            availabilityPercent = healthMonitor.getAvailabilityPercent(currentDescriptor.modelId)
        )
        val candidateScore = latencyTracker.getEffectiveScoreMs(
            model = candidateModel,
            taskType = taskType,
            availabilityPercent = healthMonitor.getAvailabilityPercent(candidateModel.modelId)
        )

        if (currentScore <= 0L || candidateScore <= 0L) {
            return false
        }

        val advantageMs = currentScore - candidateScore
        val improvementRatio = if (currentScore > 0L) {
            advantageMs.toDouble() / currentScore.toDouble()
        } else {
            0.0
        }

        val isSignificantlyFaster = advantageMs >= HYSTERESIS_MIN_ADVANTAGE_MS &&
                improvementRatio >= HYSTERESIS_MIN_IMPROVEMENT_RATIO

        if (!isSignificantlyFaster) {
            return false
        }

        // If a conversation turn is currently active, defer the switch until the turn finishes!
        if (isTurnActive) {
            pendingSafeUpgradeModelId = candidateModel.modelId
            return false
        }

        pendingSafeUpgradeModelId = null
        val event = FallbackEvent(
            id = "opt_${UUID.randomUUID().toString().take(8)}",
            timestampMs = System.currentTimeMillis(),
            taskType = taskType,
            fromModelId = currentDescriptor.modelId,
            toModelId = candidateModel.modelId,
            reason = "Auto-switched to faster model (${candidateScore}ms vs ${currentScore}ms)",
            httpOrWsCode = 200,
            contextPreserved = true
        )
        appendFallbackEvent(event)
        return true
    }

    fun consumePendingSafeUpgrade(): String? {
        val pending = pendingSafeUpgradeModelId
        pendingSafeUpgradeModelId = null
        return pending
    }

    fun getPendingSafeUpgrade(): String? = pendingSafeUpgradeModelId

    private fun appendFallbackEvent(event: FallbackEvent) {
        _lastFallbackEvent.value = event
        val updated = (_fallbackHistory.value + event).takeLast(30)
        _fallbackHistory.value = updated
    }

    fun clearHistory() {
        _fallbackHistory.value = emptyList()
        _lastFallbackEvent.value = null
        recentSwitchTimestamps.clear()
        pendingSafeUpgradeModelId = null
    }
}
