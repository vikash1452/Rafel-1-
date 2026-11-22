package com.example.ai.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encrypted storage for the user's Gemini API key using Jetpack Security (`EncryptedSharedPreferences`).
 *
 * Guarantees:
 * 1. Legacy plain-text keys (`RafelPrefs`, `rafel_settings`, `ZoyaPrefs`) are ONLY deleted AFTER
 *    the encrypted write is committed and verified via read-back.
 * 2. MasterKey / EncryptedSharedPreferences initialization is wrapped in try/catch so keystore
 *    failures never crash the app; instead, the user is prompted to re-enter the API key.
 */
object SecureApiKeyStore {

    private const val ENCRYPTED_PREFS_NAME = "rafel_encrypted_api_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val REENTRY_MESSAGE = "Secure storage reset ho gaya — kripya Gemini API key dobara daalo."

    private val LEGACY_PREF_FILES = listOf("RafelPrefs", "rafel_settings", "ZoyaPrefs")

    @Volatile
    private var cachedEncryptedPrefs: SharedPreferences? = null

    private val _requiresKeyReentry = MutableStateFlow(false)
    val requiresKeyReentry: StateFlow<Boolean> = _requiresKeyReentry.asStateFlow()

    private val _reentryMessage = MutableStateFlow<String?>(null)
    val reentryMessage: StateFlow<String?> = _reentryMessage.asStateFlow()

    private val reentryToastShown = AtomicBoolean(false)

    /**
     * In-memory fallback used ONLY in local JVM/Robolectric unit tests where AndroidKeyStore is absent.
     * Never writes keys to plain-text SharedPreferences.
     */
    private val jvmFallbackMemoryStore = ConcurrentHashMap<String, String>()

    private fun isRobolectricEnvironment(): Boolean {
        return try {
            Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun notifyKeyReentryRequired(context: Context) {
        _requiresKeyReentry.value = true
        _reentryMessage.value = REENTRY_MESSAGE
        if (reentryToastShown.compareAndSet(false, true)) {
            try {
                val appContext = context.applicationContext
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(appContext, REENTRY_MESSAGE, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {
                        // Ignore Toast failures in headless/service-only contexts
                    }
                }
            } catch (_: Throwable) {
                // Ignore Looper failures in non-Android JVM environments
            }
        }
    }

    fun consumeReentryPrompt() {
        _requiresKeyReentry.value = false
        _reentryMessage.value = null
        reentryToastShown.set(false)
    }

    @Synchronized
    private fun getEncryptedPrefsOrNull(context: Context): SharedPreferences? {
        cachedEncryptedPrefs?.let { return it }
        return try {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cachedEncryptedPrefs = prefs
            prefs
        } catch (_: Throwable) {
            cachedEncryptedPrefs = null
            if (!isRobolectricEnvironment()) {
                notifyKeyReentryRequired(context)
            }
            null
        }
    }

    /**
     * One-time migration: moves any plain-text API key from legacy SharedPreferences files
     * into encrypted storage. Plain-text keys are ONLY deleted after the encrypted write
     * commits and verifies on read-back.
     */
    @Synchronized
    fun migrateLegacyPlaintextKeys(context: Context) {
        val appContext = context.applicationContext
        var migratedKey: String? = null
        var hasAnyLegacyKeyEntry = false

        // Step 1: Read legacy plain-text keys WITHOUT deleting them yet
        for (fileName in LEGACY_PREF_FILES) {
            val legacyPrefs = appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            if (legacyPrefs.contains("api_key") || legacyPrefs.contains("gemini_api_key")) {
                hasAnyLegacyKeyEntry = true
            }
            val rawKey = legacyPrefs.getString("api_key", null)?.takeIf { it.isNotBlank() }
                ?: legacyPrefs.getString("gemini_api_key", null)?.takeIf { it.isNotBlank() }

            if (!rawKey.isNullOrBlank() && migratedKey == null) {
                migratedKey = rawKey.trim()
            }
        }

        if (!hasAnyLegacyKeyEntry) return

        // Step 2: Ensure key is safely stored & verified in encrypted storage before deleting legacy plain keys
        val readyToDeleteLegacy = if (!migratedKey.isNullOrBlank()) {
            val existing = readFromSecureStorage(appContext)
            if (existing.isNotBlank()) {
                true
            } else {
                writeAndVerifySecureStorage(appContext, migratedKey)
            }
        } else {
            // Only empty/blank legacy entries exist; safe to clean up
            true
        }

        // Step 3: Delete legacy plain-text keys ONLY if encrypted write verified
        if (readyToDeleteLegacy) {
            clearLegacyPlaintextKeys(appContext)
        }
    }

    fun getApiKey(context: Context): String {
        val appContext = context.applicationContext
        migrateLegacyPlaintextKeys(appContext)
        return readFromSecureStorage(appContext)
    }

    fun saveApiKey(context: Context, apiKey: String): Boolean {
        val appContext = context.applicationContext
        val trimmed = apiKey.trim()
        return if (trimmed.isBlank()) {
            val removed = removeFromSecureStorage(appContext)
            if (removed) {
                clearLegacyPlaintextKeys(appContext)
            }
            removed
        } else {
            val verified = writeAndVerifySecureStorage(appContext, trimmed)
            if (verified) {
                clearLegacyPlaintextKeys(appContext)
                consumeReentryPrompt()
            } else if (!isRobolectricEnvironment()) {
                notifyKeyReentryRequired(appContext)
            }
            verified
        }
    }

    fun clearApiKey(context: Context) {
        val appContext = context.applicationContext
        clearLegacyPlaintextKeys(appContext)
        removeFromSecureStorage(appContext)
    }

    private fun clearLegacyPlaintextKeys(appContext: Context) {
        for (fileName in LEGACY_PREF_FILES) {
            val legacyPrefs = appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            if (legacyPrefs.contains("api_key") || legacyPrefs.contains("gemini_api_key")) {
                legacyPrefs.edit()
                    .remove("api_key")
                    .remove("gemini_api_key")
                    .commit()
            }
        }
    }

    private fun readFromSecureStorage(appContext: Context): String {
        val encPrefs = getEncryptedPrefsOrNull(appContext)
        return if (encPrefs != null) {
            try {
                encPrefs.getString(KEY_GEMINI_API_KEY, null)?.trim().orEmpty()
            } catch (_: Throwable) {
                cachedEncryptedPrefs = null
                if (!isRobolectricEnvironment()) {
                    notifyKeyReentryRequired(appContext)
                }
                ""
            }
        } else if (isRobolectricEnvironment()) {
            jvmFallbackMemoryStore[KEY_GEMINI_API_KEY].orEmpty()
        } else {
            ""
        }
    }

    private fun writeAndVerifySecureStorage(appContext: Context, value: String): Boolean {
        val encPrefs = getEncryptedPrefsOrNull(appContext)
        return if (encPrefs != null) {
            try {
                val committed = encPrefs.edit()
                    .putString(KEY_GEMINI_API_KEY, value)
                    .commit()
                if (!committed) return false
                val verifiedValue = encPrefs.getString(KEY_GEMINI_API_KEY, null)?.trim()
                verifiedValue == value
            } catch (_: Throwable) {
                cachedEncryptedPrefs = null
                if (!isRobolectricEnvironment()) {
                    notifyKeyReentryRequired(appContext)
                }
                false
            }
        } else if (isRobolectricEnvironment()) {
            jvmFallbackMemoryStore[KEY_GEMINI_API_KEY] = value
            jvmFallbackMemoryStore[KEY_GEMINI_API_KEY] == value
        } else {
            false
        }
    }

    private fun removeFromSecureStorage(appContext: Context): Boolean {
        val encPrefs = getEncryptedPrefsOrNull(appContext)
        return if (encPrefs != null) {
            try {
                encPrefs.edit().remove(KEY_GEMINI_API_KEY).commit()
            } catch (_: Throwable) {
                cachedEncryptedPrefs = null
                false
            }
        } else if (isRobolectricEnvironment()) {
            jvmFallbackMemoryStore.remove(KEY_GEMINI_API_KEY)
            true
        } else {
            false
        }
    }
}
