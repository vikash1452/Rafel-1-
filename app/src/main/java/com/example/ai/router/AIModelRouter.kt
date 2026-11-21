package com.example.ai.router

/**
 * Intelligent Automatic Model Router for RAFEL.
 *
 * Implements the 7-step selection pipeline:
 * 1. Check available registered models.
 * 2. Check model health, cooldowns, and network connectivity.
 * 3. Compute rolling latency composite score for each compatible candidate.
 * 4. Verify task capability requirements (never selecting text-only models for Live Audio).
 * 5. Select the fastest healthy compatible model automatically.
 * 6. Respect active-turn stability & hysteresis to avoid unnecessary mid-conversation switches.
 * 7. Build an ordered fallback chain for instant failover if the primary choice fails or times out.
 */
class AIModelRouter(
    private val registry: AIModelRegistry,
    private val capabilityManager: ModelCapabilityManager,
    private val healthMonitor: ModelHealthMonitor,
    private val latencyTracker: ModelLatencyTracker,
    private val fallbackManager: ModelFallbackManager
) {

    /**
     * Routes a request for [taskType] to the optimal model.
     */
    fun routeRequest(
        taskType: ModelTaskType,
        isAutoMode: Boolean = true,
        hasApiKey: Boolean = true,
        currentActiveModelId: String? = null,
        isTurnInProgress: Boolean = false,
        preferredManualModelId: String? = null
    ): ModelRoutingDecision {
        val isOnline = healthMonitor.isOnline.value
        val offlineVerification = healthMonitor.refreshOfflineModelState()

        // 1. If device is offline and task is not LIVE_AUDIO, check if offline local LLM is installed & compatible
        if (!isOnline) {
            if (taskType == ModelTaskType.LIVE_AUDIO) {
                return ModelRoutingDecision(
                    taskType = taskType,
                    selectedModel = null,
                    fallbackChain = emptyList(),
                    candidateScoresMs = emptyMap(),
                    selectionReason = "Device is offline; Gemini Live native audio requires an internet connection.",
                    isAutoMode = isAutoMode,
                    isOfflineMode = true
                )
            }

            if (offlineVerification.isWeightFilePresent) {
                val localModel = registry.getModel(AIModelRegistry.MODEL_OFFLINE_LOCAL_LLM)
                if (localModel != null && capabilityManager.supportsTask(
                        model = localModel,
                        taskType = taskType,
                        isOfflineWeightVerified = true
                    )
                ) {
                    val score = latencyTracker.getEffectiveScoreMs(localModel, taskType, 100.0)
                    return ModelRoutingDecision(
                        taskType = taskType,
                        selectedModel = localModel,
                        fallbackChain = listOf(localModel),
                        candidateScoresMs = mapOf(localModel.modelId to score),
                        selectionReason = "Offline mode: Selected verified on-device LLM (${offlineVerification.statusMessage})",
                        isAutoMode = isAutoMode,
                        isOfflineMode = true
                    )
                }
            }

            return ModelRoutingDecision(
                taskType = taskType,
                selectedModel = null,
                fallbackChain = emptyList(),
                candidateScoresMs = emptyMap(),
                selectionReason = offlineVerification.statusMessage,
                isAutoMode = isAutoMode,
                isOfflineMode = true
            )
        }

        // 2. Gather all task-compatible models (strictly filtering out text-only models for LIVE_AUDIO)
        val compatibleModels = capabilityManager.getCompatibleModels(
            taskType = taskType,
            isOfflineWeightVerified = offlineVerification.isWeightFilePresent
        ).filter { model ->
            // When online, prefer online cloud models first unless offline fallback is explicitly requested
            if (taskType != ModelTaskType.OFFLINE_FALLBACK && model.providerType == ModelProviderType.OFFLINE_LOCAL_LLM) {
                false
            } else {
                true
            }
        }

        // 3. Filter to currently healthy & available models
        val healthyCandidates = compatibleModels.filter { model ->
            healthMonitor.isModelAvailableForRouting(model, hasApiKey = hasApiKey)
        }

        // If all compatible models are in cooldown, fall back to the highest-priority compatible model
        // only if we have an API key and internet connection (graceful cooldown recovery)
        val effectiveCandidates = if (healthyCandidates.isNotEmpty()) {
            healthyCandidates
        } else if (hasApiKey && isOnline) {
            compatibleModels
        } else {
            emptyList()
        }

        if (effectiveCandidates.isEmpty()) {
            val reason = if (!hasApiKey) {
                "Gemini API Key is not configured."
            } else {
                "No healthy compatible model available for ${taskType.displayName}."
            }
            return ModelRoutingDecision(
                taskType = taskType,
                selectedModel = null,
                fallbackChain = emptyList(),
                candidateScoresMs = emptyMap(),
                selectionReason = reason,
                isAutoMode = isAutoMode,
                isOfflineMode = !isOnline
            )
        }

        // 4. Compute rolling latency composite scores for each candidate (0L if not yet measured)
        val scoreMap = LinkedHashMap<String, Long>()
        effectiveCandidates.forEach { candidate ->
            val avail = healthMonitor.getAvailabilityPercent(candidate.modelId)
            scoreMap[candidate.modelId] = latencyTracker.getEffectiveScoreMs(candidate, taskType, avail)
        }

        val anyMeasured = scoreMap.values.any { it > 0L }

        // 5. Sort candidates by fastest real measured latency score when available, otherwise by priorityRank
        val rankedCandidates = effectiveCandidates.sortedWith(
            compareBy<AIModelDescriptor> { candidate ->
                val measured = scoreMap[candidate.modelId] ?: 0L
                when {
                    measured > 0L -> measured
                    anyMeasured -> 100_000L + candidate.priorityRank
                    else -> candidate.priorityRank.toLong()
                }
            }.thenBy { it.priorityRank }
        )

        val fastestCandidate = rankedCandidates.first()

        // 6. Manual mode check: if auto mode is OFF and manual/default model is healthy, honor it
        if (!isAutoMode) {
            val manualTargetId = preferredManualModelId ?: currentActiveModelId
            val manualDescriptor = manualTargetId?.let { id ->
                effectiveCandidates.firstOrNull {
                    it.modelId.equals(id, ignoreCase = true) ||
                        it.restModelName.equals(id.removePrefix("models/"), ignoreCase = true)
                }
            } ?: effectiveCandidates.minByOrNull { it.priorityRank }

            if (manualDescriptor != null) {
                val chain = listOf(manualDescriptor) + rankedCandidates.filter { it.modelId != manualDescriptor.modelId }
                return ModelRoutingDecision(
                    taskType = taskType,
                    selectedModel = manualDescriptor,
                    fallbackChain = chain,
                    candidateScoresMs = scoreMap,
                    selectionReason = "Manual mode: Using pinned model ${manualDescriptor.modelId}",
                    isAutoMode = false,
                    isOfflineMode = false
                )
            }
        }

        // 7. Automatic mode hysteresis & active-turn safety guard:
        // If there is already a healthy active model for this task, check if switching is warranted and safe
        if (!currentActiveModelId.isNullOrBlank()) {
            val currentDescriptor = effectiveCandidates.firstOrNull {
                it.modelId.equals(currentActiveModelId, ignoreCase = true) ||
                    it.restModelName.equals(currentActiveModelId.removePrefix("models/"), ignoreCase = true)
            }
            if (currentDescriptor != null && currentDescriptor.modelId != fastestCandidate.modelId) {
                val shouldSwitch = fallbackManager.shouldSwitchForPerformance(
                    taskType = taskType,
                    currentModelId = currentDescriptor.modelId,
                    candidateModel = fastestCandidate,
                    isTurnActive = isTurnInProgress
                )
                if (!shouldSwitch) {
                    val chain = listOf(currentDescriptor) + rankedCandidates.filter { it.modelId != currentDescriptor.modelId }
                    val reason = if (isTurnInProgress) {
                        "Preserved active model ${currentDescriptor.modelId} during in-progress turn (queued ${fastestCandidate.modelId})"
                    } else {
                        "Retained healthy model ${currentDescriptor.modelId} (${scoreMap[currentDescriptor.modelId] ?: 0L}ms within hysteresis threshold)"
                    }
                    return ModelRoutingDecision(
                        taskType = taskType,
                        selectedModel = currentDescriptor,
                        fallbackChain = chain,
                        candidateScoresMs = scoreMap,
                        selectionReason = reason,
                        isAutoMode = true,
                        isOfflineMode = false
                    )
                }
            }
        }

        val fastestScore = scoreMap[fastestCandidate.modelId] ?: 0L
        val reasonText = if (fastestScore > 0L) {
            "Auto-selected fastest healthy model ${fastestCandidate.modelId} (measured score=${fastestScore}ms)"
        } else {
            "Auto-selected priority healthy model ${fastestCandidate.modelId} (unmeasured)"
        }
        return ModelRoutingDecision(
            taskType = taskType,
            selectedModel = fastestCandidate,
            fallbackChain = rankedCandidates,
            candidateScoresMs = scoreMap,
            selectionReason = reasonText,
            isAutoMode = true,
            isOfflineMode = false
        )
    }
}
