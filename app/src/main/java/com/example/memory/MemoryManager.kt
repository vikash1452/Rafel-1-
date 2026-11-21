package com.example.memory

import android.content.Context
import com.example.voice.RafelLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Intelligent User Memory Manager for RAFEL.
 *
 * Extracts meaningful user facts from natural Hindi, Hinglish, and English conversation,
 * persists them to [RafelMemoryDatabase], and retrieves ONLY contextually relevant memories
 * for the current conversation topic so memories are never awkwardly forced into unrelated turns.
 */
class MemoryManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = RafelMemoryDatabase.getInstance(appContext)
    private val memoryDao = db.memoryDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val memoryCache = ConcurrentHashMap<String, UserMemoryEntity>()

    init {
        loadFromDatabase()
    }

    private fun loadFromDatabase() {
        scope.launch {
            try {
                val all = memoryDao.getAllMemories()
                all.forEach { entity ->
                    memoryCache[entity.key] = entity
                }
            } catch (e: Exception) {
                RafelLogger.logError("Failed to load user memories from Room", e)
            }
        }
    }

    /**
     * Inspects a user utterance and automatically extracts personal facts
     * (name, city, study/work/exam context, food/entertainment preferences) without
     * requiring explicit "remember this" commands.
     */
    fun observeUserUtterance(userText: String) {
        val clean = userText.trim()
        if (clean.length < 4) return
        val lower = clean.lowercase(Locale.ROOT)

        // 1. Extract User Name ("Mera naam X hai", "My name is X", "Mujhe X bulao")
        val nameRegexes = listOf(
            Regex("(?i)\\bmera naam\\s+([A-Za-z\\u0900-\\u097F]{2,20})(?:\\s+hai)?\\b"),
            Regex("(?i)\\bmy name is\\s+([A-Za-z]{2,20})\\b"),
            Regex("(?i)\\bmujhe\\s+([A-Za-z]{2,20})\\s+bulao\\b"),
            Regex("(?i)\\bcall me\\s+([A-Za-z]{2,20})\\b")
        )
        for (regex in nameRegexes) {
            val match = regex.find(clean)
            if (match != null) {
                val rawName = match.groupValues[1].trim()
                val ignored = setOf(
                    "kya", "nahi", "nhi", "toh", "bhi", "what", "yaad", "pata",
                    "maloom", "batao", "bata", "hai", "tha", "mat", "bhool"
                )
                if (rawName.lowercase(Locale.ROOT) !in ignored && !clean.endsWith("?")) {
                    val formattedName = rawName.replaceFirstChar { it.uppercase() }
                    saveFact("user_name", formattedName, "identity")
                    break
                }
            }
        }

        // 2. Extract City / Location ("Main Delhi mein rehta hu", "I live in Mumbai")
        val cityRegexes = listOf(
            Regex("(?i)\\bmain\\s+([A-Za-z]{3,20})\\s+mein\\s+reh(?:ta|ti)\\s+(?:hoon|hu)\\b"),
            Regex("(?i)\\bi\\s+(?:live|stay)\\s+in\\s+([A-Za-z]{3,20})\\b"),
            Regex("(?i)\\bi\\s+am\\s+from\\s+([A-Za-z]{3,20})\\b")
        )
        for (regex in cityRegexes) {
            val match = regex.find(clean)
            if (match != null) {
                val city = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                saveFact("user_city", city, "location")
                break
            }
        }

        // 3. Extract Study / Exam / Project / Important Event context
        if (lower.contains("mera exam") || lower.contains("mere exams") || lower.contains("my exam") ||
            lower.contains("exam hai") || lower.contains("paper hai")
        ) {
            saveFact("upcoming_event", clean, "study_work")
        } else if (lower.contains("mera interview") || lower.contains("my interview") || lower.contains("interview hai")) {
            saveFact("upcoming_event", clean, "study_work")
        } else if (lower.contains("mera project") || lower.contains("my project") ||
            lower.contains("project deadline") || lower.contains("project submission") ||
            lower.contains("project pe kaam") || lower.contains("working on a project")
        ) {
            saveFact("upcoming_event", clean, "study_work")
        } else if (lower.contains("mera presentation") || lower.contains("my presentation") ||
            lower.contains("important event") || lower.contains("important meeting") ||
            lower.contains("kal meeting hai") || lower.contains("demo hai")
        ) {
            saveFact("upcoming_event", clean, "study_work")
        }

        // 4. Extract Preferences ("Mujhe X pasand hai", "I love X", "Mera favorite X hai")
        val prefRegexes = listOf(
            Regex("(?i)\\bmujhe\\s+(.{3,35}?)\\s+(?:bahut\\s+)?pasand\\s+hai\\b"),
            Regex("(?i)\\bi\\s+(?:really\\s+)?love\\s+(.{3,35})\\b"),
            Regex("(?i)\\bmera\\s+fav(?:ourite|orite)\\s+(.{3,35})\\s+hai\\b")
        )
        for (regex in prefRegexes) {
            val match = regex.find(clean)
            if (match != null) {
                val pref = match.groupValues[1].trim()
                saveFact("user_preference", pref, "preference")
                break
            }
        }
    }

    fun saveFact(key: String, value: String, category: String = "general") {
        val cleanKey = key.trim()
        val cleanVal = value.trim()
        if (cleanKey.isBlank() || cleanVal.isBlank()) return

        val entity = UserMemoryEntity(
            key = cleanKey,
            value = cleanVal,
            category = category,
            updatedAt = System.currentTimeMillis()
        )
        memoryCache[cleanKey] = entity
        scope.launch {
            try {
                val existing = memoryDao.getMemoryByKey(cleanKey)
                if (existing != null) {
                    memoryDao.saveMemory(entity.copy(id = existing.id))
                } else {
                    memoryDao.saveMemory(entity)
                }
            } catch (e: Exception) {
                RafelLogger.logError("Failed to save memory key=$cleanKey", e)
            }
        }
    }

    fun getFactValue(key: String): String? = memoryCache[key]?.value

    fun getAllMemoriesMap(): Map<String, String> =
        memoryCache.mapValues { it.value.value }

    /**
     * Returns ONLY memories that are contextually relevant to [userQuery] or [recentTopicText].
     * Unrelated memories are omitted so RAFEL never forces stored facts into unrelated casual turns.
     */
    fun getRelevantMemories(userQuery: String, recentTopicText: String = ""): Map<String, String> {
        if (memoryCache.isEmpty()) return emptyMap()
        val combined = "$userQuery $recentTopicText".lowercase(Locale.ROOT)
        val result = mutableMapOf<String, String>()

        for ((key, entity) in memoryCache) {
            val isRelevant = when (entity.category) {
                "identity" -> {
                    combined.contains("naam") || combined.contains("name") ||
                        combined.contains("kaun") || combined.contains("who am i") ||
                        combined.contains("jaanti ho") || combined.contains("yaad")
                }
                "location" -> {
                    combined.contains("sheher") || combined.contains("city") ||
                        combined.contains("kahan") || combined.contains("where") ||
                        combined.contains("mausam") || combined.contains("weather") ||
                        combined.contains(entity.value.lowercase(Locale.ROOT))
                }
                "study_work" -> {
                    combined.contains("exam") || combined.contains("padhai") ||
                        combined.contains("study") || combined.contains("college") ||
                        combined.contains("interview") || combined.contains("office") ||
                        combined.contains("kaam") || combined.contains("subject") ||
                        combined.contains("preparation") || combined.contains("taiyari")
                }
                "preference" -> {
                    val prefWords = entity.value.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 3 }
                    combined.contains("pasand") || combined.contains("favorite") ||
                        combined.contains("favourite") || combined.contains("bore") ||
                        combined.contains("chai") || combined.contains("coffee") ||
                        combined.contains("movie") || combined.contains("music") ||
                        combined.contains("khana") || prefWords.any { combined.contains(it) }
                }
                else -> {
                    val valueWords = entity.value.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 4 }
                    valueWords.any { combined.contains(it) }
                }
            }
            if (isRelevant) {
                result[key] = entity.value
            }
        }
        return result
    }

    /**
     * Formats relevant memories into a concise prompt line only when relevant memories exist.
     */
    fun formatRelevantMemoryPrompt(userQuery: String, recentTopicText: String = ""): String {
        val relevant = getRelevantMemories(userQuery, recentTopicText)
        if (relevant.isEmpty()) return ""
        return relevant.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    fun clearAllMemories() {
        val keys = memoryCache.keys.toList()
        memoryCache.clear()
        scope.launch {
            keys.forEach { k ->
                try {
                    memoryDao.deleteMemory(k)
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: MemoryManager? = null

        fun getInstance(context: Context): MemoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
