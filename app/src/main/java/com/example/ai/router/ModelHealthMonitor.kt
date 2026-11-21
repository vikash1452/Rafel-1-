package com.example.ai.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors model health, error cooldowns, availability percentages, and device online/offline state.
 *
 * Automatically restores online model availability when internet connectivity returns
 * without interrupting an active response.
 */
class ModelHealthMonitor(
    private val context: Context,
    private val registry: AIModelRegistry
) {

    private class ModelHealthRecord(
        @Volatile var state: ModelHealthState = ModelHealthState.HEALTHY,
        val consecutiveFailures: AtomicInteger = AtomicInteger(0),
        val totalChecks: AtomicInteger = AtomicInteger(0),
        val healthyChecks: AtomicInteger = AtomicInteger(0),
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
        @Volatile var lastErrorReason: String? = null,
        @Volatile var lastStatusCode: Int = 0
    )

    private val healthRecords = ConcurrentHashMap<String, ModelHealthRecord>()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile
    private var simulatedOnlineOverride: Boolean? = null

    var onConnectivityChanged: ((Boolean) -> Unit)? = null

    init {
        initializeRecords()
        refreshConnectivityState()
        registerNetworkCallbackSafely()
    }

    private fun initializeRecords() {
        val offlineVerify = registry.verifyOfflineModelWeights(context)
        for (model in registry.getAllModels()) {
            val initialState = when {
                model.providerType == ModelProviderType.OFFLINE_LOCAL_LLM -> {
                    if (offlineVerify.isWeightFilePresent) ModelHealthState.HEALTHY else ModelHealthState.MISSING_WEIGHTS
                }
                else -> ModelHealthState.HEALTHY
            }
            healthRecords[model.modelId] = ModelHealthRecord(state = initialState)
        }
    }

    private fun registerNetworkCallbackSafely() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (simulatedOnlineOverride == null) {
                        handleNetworkRestored()
                    }
                }

                override fun onLost(network: Network) {
                    if (simulatedOnlineOverride == null) {
                        refreshConnectivityState()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (simulatedOnlineOverride == null) {
                        refreshConnectivityState()
                    }
                }
            })
        } catch (_: Exception) {
            // Safe fallback on JVM/Robolectric or restricted contexts
        }
    }

    /**
     * Checks live Android ConnectivityManager state (unless overridden in tests).
     */
    fun refreshConnectivityState(): Boolean {
        simulatedOnlineOverride?.let { sim ->
            if (sim) handleNetworkRestored() else handleNetworkLost()
            return sim
        }

        val online = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                true
            } else {
                val activeNet = cm.activeNetwork
                val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
                @Suppress("DEPRECATION")
                val legacyConnected = cm.activeNetworkInfo?.isConnected == true
                when {
                    caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> true
                    legacyConnected -> true
                    activeNet == null && cm.activeNetworkInfo == null -> true
                    else -> false
                }
            }
        } catch (_: Exception) {
            true
        }

        if (online) {
            handleNetworkRestored()
        } else {
            handleNetworkLost()
        }
        return online
    }

    /**
     * Test hook for deterministic offline/online transition testing.
     */
    fun setSimulatedOnlineState(online: Boolean?) {
        simulatedOnlineOverride = online
        if (online != null) {
            if (online) handleNetworkRestored() else handleNetworkLost()
        } else {
            refreshConnectivityState()
        }
    }

    private fun handleNetworkLost() {
        val wasOnline = _isOnline.value
        _isOnline.value = false
        for (model in registry.getAllModels()) {
            if (model.requiresInternet) {
                val rec = healthRecords.getOrPut(model.modelId) { ModelHealthRecord() }
                rec.state = ModelHealthState.OFFLINE
            }
        }
        // Re-verify offline model weights on disk
        refreshOfflineModelState()
        if (wasOnline) {
            onConnectivityChanged?.invoke(false)
        }
    }

    private fun handleNetworkRestored() {
        val wasOnline = _isOnline.value
        _isOnline.value = true
        val now = System.currentTimeMillis()
        for (model in registry.getAllModels()) {
            if (model.requiresInternet) {
                val rec = healthRecords.getOrPut(model.modelId) { ModelHealthRecord() }
                if (rec.state == ModelHealthState.OFFLINE) {
                    rec.state = if (rec.cooldownUntilMs.get() > now) {
                        ModelHealthState.DEGRADED
                    } else {
                        ModelHealthState.HEALTHY
                    }
                }
            }
        }
        if (!wasOnline) {
            onConnectivityChanged?.invoke(true)
        }
    }

    fun refreshOfflineModelState(): OfflineModelVerificationResult {
        val verify = registry.verifyOfflineModelWeights(context)
        val rec = healthRecords.getOrPut(AIModelRegistry.MODEL_OFFLINE_LOCAL_LLM) { ModelHealthRecord() }
        rec.state = if (verify.isWeightFilePresent) {
            ModelHealthState.HEALTHY
        } else {
            ModelHealthState.MISSING_WEIGHTS
        }
        return verify
    }

    /**
     * Marks a model request/turn as healthy and clears any transient failure cooldown.
     */
    fun recordModelSuccess(modelId: String) {
        val rec = healthRecords.getOrPut(modelId) { ModelHealthRecord() }
        rec.consecutiveFailures.set(0)
        rec.cooldownUntilMs.set(0L)
        rec.lastErrorReason = null
        rec.lastStatusCode = 200
        rec.totalChecks.incrementAndGet()
        rec.healthyChecks.incrementAndGet()
        rec.state = ModelHealthState.HEALTHY
    }

    /**
     * Marks a model failure, computes exponential backoff cooldown to prevent reconnect loops,
     * and transitions the model state to DEGRADED or UNHEALTHY.
     */
    fun recordModelFailure(
        modelId: String,
        reason: String,
        statusCode: Int = 0,
        baseCooldownMs: Long = 4000L
    ) {
        val rec = healthRecords.getOrPut(modelId) { ModelHealthRecord() }
        val failures = rec.consecutiveFailures.incrementAndGet()
        rec.totalChecks.incrementAndGet()
        rec.lastErrorReason = reason
        rec.lastStatusCode = statusCode

        // If model is rejected as not found / unsupported (1008 or 404), apply a longer cooldown (60s)
        val isModelUnsupported = statusCode == 1008 || statusCode == 404 ||
                reason.contains("not found", ignoreCase = true) ||
                reason.contains("not supported", ignoreCase = true)

        val cooldownDuration = if (isModelUnsupported) {
            60_000L
        } else {
            val multiplier = (1L shl (failures - 1).coerceAtMost(4))
            (baseCooldownMs * multiplier).coerceAtMost(45_000L)
        }

        rec.cooldownUntilMs.set(System.currentTimeMillis() + cooldownDuration)
        rec.state = if (failures >= 2 || isModelUnsupported) {
            ModelHealthState.UNHEALTHY
        } else {
            ModelHealthState.DEGRADED
        }
    }

    /**
     * Checks whether [model] is currently healthy and eligible for routing.
     */
    fun isModelAvailableForRouting(
        model: AIModelDescriptor,
        hasApiKey: Boolean = true,
        allowCooldownExpiredRecovery: Boolean = true
    ): Boolean {
        if (model.providerType == ModelProviderType.OFFLINE_LOCAL_LLM) {
            val verify = refreshOfflineModelState()
            return verify.isWeightFilePresent
        }

        if (model.requiresInternet && !_isOnline.value) {
            return false
        }

        if (model.requiresApiKey && !hasApiKey) {
            return false
        }

        val rec = healthRecords[model.modelId] ?: return true
        val now = System.currentTimeMillis()
        val cooldownEnd = rec.cooldownUntilMs.get()

        if (cooldownEnd > now) {
            return false
        }

        if (allowCooldownExpiredRecovery && (rec.state == ModelHealthState.UNHEALTHY || rec.state == ModelHealthState.DEGRADED)) {
            // Cooldown expired: allow probe/recovery as DEGRADED
            rec.state = ModelHealthState.DEGRADED
        }

        return rec.state != ModelHealthState.OFFLINE && rec.state != ModelHealthState.MISSING_WEIGHTS
    }

    fun getHealthState(modelId: String): ModelHealthState {
        val model = registry.getModel(modelId)
        if (model?.providerType == ModelProviderType.OFFLINE_LOCAL_LLM) {
            return if (registry.verifyOfflineModelWeights(context).isWeightFilePresent) {
                ModelHealthState.HEALTHY
            } else {
                ModelHealthState.MISSING_WEIGHTS
            }
        }
        if (model?.requiresInternet == true && !_isOnline.value) {
            return ModelHealthState.OFFLINE
        }
        val rec = healthRecords[modelId] ?: return ModelHealthState.HEALTHY
        val now = System.currentTimeMillis()
        if (rec.cooldownUntilMs.get() in 1..now && rec.state == ModelHealthState.UNHEALTHY) {
            rec.state = ModelHealthState.DEGRADED
        }
        return rec.state
    }

    fun getAvailabilityPercent(modelId: String): Double {
        val state = getHealthState(modelId)
        if (state == ModelHealthState.OFFLINE || state == ModelHealthState.MISSING_WEIGHTS) {
            return 0.0
        }
        val rec = healthRecords[modelId] ?: return 100.0
        val total = rec.totalChecks.get()
        if (total == 0) return 100.0
        val healthy = rec.healthyChecks.get()
        return ((healthy.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    }

    fun getAllHealthStates(): Map<String, ModelHealthState> {
        return registry.getAllModels().associate { it.modelId to getHealthState(it.modelId) }
    }

    fun clearCooldowns() {
        val now = System.currentTimeMillis()
        for ((id, rec) in healthRecords) {
            rec.consecutiveFailures.set(0)
            rec.cooldownUntilMs.set(0L)
            if (rec.state == ModelHealthState.UNHEALTHY || rec.state == ModelHealthState.DEGRADED) {
                rec.state = if (_isOnline.value) ModelHealthState.HEALTHY else ModelHealthState.OFFLINE
            }
        }
        refreshOfflineModelState()
    }
}
