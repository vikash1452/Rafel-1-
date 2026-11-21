package com.example.ai.security

import android.content.Context
import com.example.BuildConfig
import com.example.ai.router.AIModelManager
import com.example.voice.RafelLiveDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Classification of an API key or auth token validation check.
 * Never includes the raw secret value.
 */
enum class ApiKeyValidationStatus {
    VALID_GOOGLE_API_KEY,
    VALID_AISTUDIO_PROXY_TOKEN,
    VALID_CUSTOM_KEY,
    VALID_BACKEND_PROXY_SESSION,
    MISSING,
    PLACEHOLDER_REJECTED,
    MALFORMED_TOO_SHORT
}

data class ApiKeyAuditReport(
    val status: ApiKeyValidationStatus,
    val isConfigured: Boolean,
    val isBackendProxyConfigured: Boolean,
    val backendProxyEndpoint: String?,
    val apkContainsEmbeddedProductionKey: Boolean,
    val summary: String
)

/**
 * Secure Gateway & Key Management Architecture for RAFEL.
 *
 * Responsibilities:
 * 1. Supports a protected backend proxy (`backendProxyBaseUrl` + session bearer token) that holds
 *    the production Gemini API key server-side so the APK never needs an embedded unrestricted key.
 * 2. Never pretends an unconfigured backend proxy is operational (`isBackendProxyOperational() == false`
 *    unless a valid HTTPS backend endpoint is explicitly configured).
 * 3. Validates runtime API keys without exposing them in logs or diagnostics.
 * 4. Supports runtime key rotation (`rotateApiKey`) and revocation (`clearApiKey`) with immediate
 *    health/cooldown reset so rotated keys take effect without restarting the app.
 */
object GeminiSecureGateway {

    private const val SECURE_PREFS_NAME = "rafel_secure_gateway_prefs"
    private const val KEY_BACKEND_PROXY_URL = "backend_proxy_base_url"
    private const val KEY_BACKEND_SESSION_TOKEN = "backend_proxy_session_token"

    private val PLACEHOLDER_KEYS = setOf(
        "MY_GEMINI_API_KEY",
        "YOUR_API_KEY",
        "YOUR_GEMINI_API_KEY",
        "PLACEHOLDER",
        "INSERT_API_KEY_HERE",
        "TODO",
        "NULL"
    )

    private val _backendProxyUrl = MutableStateFlow<String?>(null)
    val backendProxyUrl: StateFlow<String?> = _backendProxyUrl.asStateFlow()

    @Volatile
    private var backendSessionToken: String? = null

    /**
     * Configures a protected backend proxy that holds the Gemini API key server-side.
     */
    fun configureBackendProxy(
        context: Context,
        proxyBaseUrl: String,
        sessionBearerToken: String = ""
    ): Boolean {
        val cleanUrl = proxyBaseUrl.trim().trimEnd('/')
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://127.0.0.1") && !cleanUrl.startsWith("http://localhost")) {
            return false
        }
        _backendProxyUrl.value = cleanUrl
        backendSessionToken = sessionBearerToken.trim().ifBlank { null }
        context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_PROXY_URL, cleanUrl)
            .putString(KEY_BACKEND_SESSION_TOKEN, backendSessionToken ?: "")
            .apply()
        return true
    }

    /**
     * Clears any configured backend proxy.
     */
    fun clearBackendProxy(context: Context) {
        _backendProxyUrl.value = null
        backendSessionToken = null
        context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BACKEND_PROXY_URL)
            .remove(KEY_BACKEND_SESSION_TOKEN)
            .apply()
    }

    /**
     * Returns true ONLY if a real backend proxy URL has been explicitly configured.
     * Never pretends an unconfigured backend is operational.
     */
    fun isBackendProxyOperational(context: Context? = null): Boolean {
        val memUrl = _backendProxyUrl.value
        if (!memUrl.isNullOrBlank()) return true
        if (context != null) {
            val saved = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_BACKEND_PROXY_URL, null)
            if (!saved.isNullOrBlank()) {
                _backendProxyUrl.value = saved
                backendSessionToken = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_BACKEND_SESSION_TOKEN, null)?.ifBlank { null }
                return true
            }
        }
        return false
    }

    fun getBackendSessionToken(): String? = backendSessionToken

    /**
     * Validates a candidate API key without logging or exposing the secret.
     */
    fun validateKeyFormat(rawKey: String?): ApiKeyValidationStatus {
        val trimmed = rawKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return ApiKeyValidationStatus.MISSING
        }
        if (PLACEHOLDER_KEYS.any { it.equals(trimmed, ignoreCase = true) }) {
            return ApiKeyValidationStatus.PLACEHOLDER_REJECTED
        }
        if (trimmed.length < 6) {
            return ApiKeyValidationStatus.MALFORMED_TOO_SHORT
        }
        return when {
            trimmed.startsWith("AIza") && trimmed.length >= 30 -> ApiKeyValidationStatus.VALID_GOOGLE_API_KEY
            trimmed.startsWith("AQ.") && trimmed.length >= 25 -> ApiKeyValidationStatus.VALID_AISTUDIO_PROXY_TOKEN
            else -> ApiKeyValidationStatus.VALID_CUSTOM_KEY
        }
    }

    fun isUsableApiKey(rawKey: String?): Boolean {
        val status = validateKeyFormat(rawKey)
        return status == ApiKeyValidationStatus.VALID_GOOGLE_API_KEY ||
            status == ApiKeyValidationStatus.VALID_AISTUDIO_PROXY_TOKEN ||
            status == ApiKeyValidationStatus.VALID_CUSTOM_KEY
    }

    /**
     * Rotates the runtime API key, persists it in private app storage, clears any stale model
     * auth-failure cooldowns, and updates runtime diagnostics (`apiKeyConfigured = YES`).
     */
    fun rotateApiKey(context: Context, newApiKey: String): Boolean {
        val trimmed = newApiKey.trim()
        if (!isUsableApiKey(trimmed)) {
            return false
        }
        context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("api_key", trimmed)
            .apply()
        context.getSharedPreferences("rafel_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("gemini_api_key", trimmed)
            .apply()

        RafelLiveDiagnostics.apiKeyConfigured.value = "YES"
        val manager = AIModelManager.getInstance(context)
        manager.healthMonitor.clearCooldowns()
        manager.refreshTelemetrySnapshot()
        return true
    }

    /**
     * Revokes/clears user-configured runtime API keys from device storage.
     */
    fun clearRuntimeApiKey(context: Context) {
        context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove("api_key")
            .remove("gemini_api_key")
            .apply()
        context.getSharedPreferences("rafel_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("gemini_api_key")
            .remove("api_key")
            .apply()
        val manager = AIModelManager.getInstance(context)
        val stillHasKey = manager.resolveEffectiveApiKey().isNotBlank()
        RafelLiveDiagnostics.apiKeyConfigured.value = if (stillHasKey) "YES" else "NO"
        manager.refreshTelemetrySnapshot()
    }

    /**
     * Audits the current APK BuildConfig and runtime environment to verify that no unrestricted
     * production API keys are hardcoded in the APK and reports the exact security status.
     */
    fun auditSecurity(context: Context, effectiveKey: String): ApiKeyAuditReport {
        val buildConfigKey = try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (_: Exception) {
            ""
        }
        val buildKeyStatus = validateKeyFormat(buildConfigKey)
        val apkHasEmbeddedKey = buildKeyStatus == ApiKeyValidationStatus.VALID_GOOGLE_API_KEY

        val proxyOperational = isBackendProxyOperational(context)
        val runtimeStatus = if (proxyOperational && effectiveKey.isBlank()) {
            ApiKeyValidationStatus.VALID_BACKEND_PROXY_SESSION
        } else {
            validateKeyFormat(effectiveKey)
        }
        val isConfigured = proxyOperational || isUsableApiKey(effectiveKey)

        val summary = buildString {
            append("keyStatus=").append(runtimeStatus.name)
            append(", apiKeyConfigured=").append(if (isConfigured) "YES" else "NO")
            append(", backendProxyOperational=").append(proxyOperational)
            append(", apkEmbeddedKey=").append(if (apkHasEmbeddedKey) "DETECTED" else "NONE")
        }

        return ApiKeyAuditReport(
            status = runtimeStatus,
            isConfigured = isConfigured,
            isBackendProxyConfigured = proxyOperational,
            backendProxyEndpoint = _backendProxyUrl.value,
            apkContainsEmbeddedProductionKey = apkHasEmbeddedKey,
            summary = summary
        )
    }
}
