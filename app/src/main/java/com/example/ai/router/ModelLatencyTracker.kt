package com.example.ai.router

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * Rolling performance and latency tracker for all registered AI models.
 *
 * Tracks per-model:
 * - Connection time (WebSocket handshake / setupComplete or HTTP connection)
 * - Time to first response (TTFR)
 * - Time to first audio chunk (TTFA)
 * - Total response time
 * - Rolling error rate & request counts
 */
class ModelLatencyTracker(
    private val maxRollingSamples: Int = 20
) {

    private class RollingMetricBucket(private val maxSize: Int) {
        val connectTimes = ConcurrentLinkedDeque<Long>()
        val ttfrTimes = ConcurrentLinkedDeque<Long>()
        val ttfaTimes = ConcurrentLinkedDeque<Long>()
        val totalResponseTimes = ConcurrentLinkedDeque<Long>()
        val recentOutcomes = ConcurrentLinkedDeque<Boolean>() // true = success, false = failure

        val totalRequests = AtomicInteger(0)
        val successfulRequests = AtomicInteger(0)
        val failedRequests = AtomicInteger(0)
        val lastLatencyMs = AtomicLong(0L)
        val lastUpdatedMs = AtomicLong(0L)

        fun addSample(deque: ConcurrentLinkedDeque<Long>, valueMs: Long) {
            if (valueMs < 0L) return
            deque.addLast(valueMs)
            while (deque.size > maxSize) {
                deque.pollFirst()
            }
            lastUpdatedMs.set(System.currentTimeMillis())
        }

        fun recordOutcome(success: Boolean) {
            totalRequests.incrementAndGet()
            if (success) {
                successfulRequests.incrementAndGet()
            } else {
                failedRequests.incrementAndGet()
            }
            recentOutcomes.addLast(success)
            while (recentOutcomes.size > maxSize) {
                recentOutcomes.pollFirst()
            }
            lastUpdatedMs.set(System.currentTimeMillis())
        }

        fun averageOf(deque: ConcurrentLinkedDeque<Long>): Long {
            val snapshot = deque.toList()
            if (snapshot.isEmpty()) return 0L
            return snapshot.average().roundToLong()
        }

        fun rollingErrorRate(): Double {
            val snapshot = recentOutcomes.toList()
            if (snapshot.isEmpty()) return 0.0
            val failures = snapshot.count { !it }
            return failures.toDouble() / snapshot.size.toDouble()
        }
    }

    private val buckets = ConcurrentHashMap<String, RollingMetricBucket>()

    private fun bucketFor(modelId: String): RollingMetricBucket {
        val key = normalizeModelId(modelId)
        return buckets.getOrPut(key) { RollingMetricBucket(maxRollingSamples) }
    }

    private fun normalizeModelId(modelId: String): String {
        return modelId.trim()
    }

    /**
     * Records connection handshake time (e.g., WebSocket connect -> setupComplete).
     */
    fun recordConnectionTime(modelId: String, connectTimeMs: Long) {
        val bucket = bucketFor(modelId)
        bucket.addSample(bucket.connectTimes, connectTimeMs)
        bucket.lastLatencyMs.set(connectTimeMs)
    }

    /**
     * Records Time To First Response (TTFR) in milliseconds.
     */
    fun recordTimeToFirstResponse(modelId: String, ttfrMs: Long) {
        val bucket = bucketFor(modelId)
        bucket.addSample(bucket.ttfrTimes, ttfrMs)
        bucket.lastLatencyMs.set(ttfrMs)
    }

    /**
     * Records Time To First Audio Chunk (TTFA) in milliseconds for Live Audio streaming.
     */
    fun recordTimeToFirstAudioChunk(modelId: String, ttfaMs: Long) {
        val bucket = bucketFor(modelId)
        bucket.addSample(bucket.ttfaTimes, ttfaMs)
        bucket.lastLatencyMs.set(ttfaMs)
    }

    /**
     * Records a completed request/turn with total response duration and marks success.
     */
    fun recordSuccess(
        modelId: String,
        totalResponseTimeMs: Long,
        ttfrMs: Long? = null,
        ttfaMs: Long? = null,
        connectTimeMs: Long? = null
    ) {
        val bucket = bucketFor(modelId)
        connectTimeMs?.let { bucket.addSample(bucket.connectTimes, it) }
        ttfrMs?.let { bucket.addSample(bucket.ttfrTimes, it) }
        ttfaMs?.let { bucket.addSample(bucket.ttfaTimes, it) }
        if (totalResponseTimeMs >= 0L) {
            bucket.addSample(bucket.totalResponseTimes, totalResponseTimeMs)
            bucket.lastLatencyMs.set(ttfaMs ?: ttfrMs ?: totalResponseTimeMs)
        }
        bucket.recordOutcome(true)
    }

    /**
     * Records a failed or timed-out request/turn.
     */
    fun recordFailure(modelId: String, elapsedBeforeFailureMs: Long = 0L) {
        val bucket = bucketFor(modelId)
        if (elapsedBeforeFailureMs > 0L) {
            bucket.lastLatencyMs.set(elapsedBeforeFailureMs)
        }
        bucket.recordOutcome(false)
    }

    /**
     * Returns true only when real runtime latency samples have been recorded for [modelId].
     */
    fun hasMeasuredLatency(modelId: String): Boolean {
        val bucket = buckets[normalizeModelId(modelId)] ?: return false
        return bucket.connectTimes.isNotEmpty() ||
            bucket.ttfrTimes.isNotEmpty() ||
            bucket.ttfaTimes.isNotEmpty() ||
            bucket.totalResponseTimes.isNotEmpty()
    }

    /**
     * Computes the effective composite latency score (lower is better/faster) for routing.
     *
     * Uses ONLY real rolling measured latency (TTFA + connect weight for LIVE_AUDIO;
     * TTFR / total response time for TEXT_CHAT) and applies an error-rate penalty so
     * flaky models rank behind reliable fast models. Returns 0L when no real measurements
     * have been recorded yet (never substitutes hardcoded benchmark numbers).
     */
    fun getEffectiveScoreMs(
        model: AIModelDescriptor,
        taskType: ModelTaskType = ModelTaskType.TEXT_CHAT,
        availabilityPercent: Double = 100.0
    ): Long {
        val bucket = buckets[normalizeModelId(model.modelId)] ?: return 0L

        val avgConnect = bucket.averageOf(bucket.connectTimes)
        val avgTtfr = bucket.averageOf(bucket.ttfrTimes)
        val avgTtfa = bucket.averageOf(bucket.ttfaTimes)
        val avgTotal = bucket.averageOf(bucket.totalResponseTimes)

        val measuredBaseMs = when (taskType) {
            ModelTaskType.LIVE_AUDIO -> {
                when {
                    avgTtfa > 0L && avgConnect > 0L -> (avgTtfa * 0.75 + avgConnect * 0.25).roundToLong()
                    avgTtfa > 0L -> avgTtfa
                    avgTtfr > 0L -> avgTtfr
                    avgConnect > 0L -> avgConnect
                    avgTotal > 0L -> avgTotal
                    else -> 0L
                }
            }
            else -> {
                when {
                    avgTtfr > 0L && avgTotal > 0L -> (avgTtfr * 0.65 + avgTotal * 0.35).roundToLong()
                    avgTtfr > 0L -> avgTtfr
                    avgTotal > 0L -> avgTotal
                    avgConnect > 0L -> avgConnect
                    else -> 0L
                }
            }
        }

        if (measuredBaseMs <= 0L) {
            return 0L
        }

        // Apply error-rate penalty: each 10% error rate adds +200ms effective penalty
        val errorRate = bucket.rollingErrorRate()
        val errorPenaltyMs = (errorRate * 2000.0).roundToLong()

        // Apply availability penalty if degraded
        val availabilityPenaltyMs = if (availabilityPercent < 95.0) {
            ((100.0 - availabilityPercent) * 15.0).roundToLong()
        } else {
            0L
        }

        return measuredBaseMs + errorPenaltyMs + availabilityPenaltyMs
    }

    /**
     * Returns full rolling statistics for a single model based strictly on real runtime measurements.
     */
    fun getStatsForModel(
        model: AIModelDescriptor,
        availabilityPercent: Double = 100.0
    ): ModelPerformanceStats {
        val bucket = buckets[normalizeModelId(model.modelId)]
        if (bucket == null) {
            return ModelPerformanceStats(
                modelId = model.modelId,
                availabilityPercent = availabilityPercent,
                compositeScoreMs = 0L
            )
        }
        val taskForScore = if (model.providerType == ModelProviderType.GEMINI_LIVE_WS) {
            ModelTaskType.LIVE_AUDIO
        } else {
            ModelTaskType.TEXT_CHAT
        }
        return ModelPerformanceStats(
            modelId = model.modelId,
            avgConnectTimeMs = bucket.averageOf(bucket.connectTimes),
            avgTimeToFirstResponseMs = bucket.averageOf(bucket.ttfrTimes),
            avgTimeToFirstAudioChunkMs = bucket.averageOf(bucket.ttfaTimes),
            avgTotalResponseTimeMs = bucket.averageOf(bucket.totalResponseTimes),
            lastResponseLatencyMs = bucket.lastLatencyMs.get(),
            totalRequests = bucket.totalRequests.get(),
            successfulRequests = bucket.successfulRequests.get(),
            failedRequests = bucket.failedRequests.get(),
            errorRate = bucket.rollingErrorRate(),
            availabilityPercent = availabilityPercent,
            compositeScoreMs = getEffectiveScoreMs(model, taskForScore, availabilityPercent),
            lastUpdatedMs = bucket.lastUpdatedMs.get()
        )
    }

    fun resetAll() {
        buckets.clear()
    }
}
