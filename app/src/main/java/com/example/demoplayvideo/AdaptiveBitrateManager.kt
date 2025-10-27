package com.example.demoplayvideo

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Adaptive Bitrate Manager - Tự động điều chỉnh bitrate theo network
 */
class AdaptiveBitrateManager(
    private val streamManager: LiveStreamManager,
    private val scope: CoroutineScope
) {
    private var currentBitrate = 2_000_000
    private val bitrateHistory = mutableListOf<Long>()
    private var monitorJob: Job? = null

    companion object {
        private const val TAG = "ABRManager"
        private const val CHECK_INTERVAL_MS = 2000L
        private const val MIN_BITRATE = 500_000
        private const val MAX_BITRATE = 5_000_000
        private const val STEP_SIZE = 500_000
    }

    fun start() {
        monitorJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                adjustBitrateBasedOnNetwork()
            }
        }
    }

    private fun adjustBitrateBasedOnNetwork() {
        // Giả lập đo network quality (thực tế cần implement real network monitoring)
        val packetLoss = measurePacketLoss() // 0.0 - 1.0
        val rtt = measureRTT() // milliseconds

        val newBitrate = when {
            packetLoss > 0.1 || rtt > 300 -> {
                // Network xấu - giảm bitrate
                (currentBitrate - STEP_SIZE).coerceAtLeast(MIN_BITRATE)
            }
            packetLoss < 0.02 && rtt < 100 -> {
                // Network tốt - tăng bitrate
                (currentBitrate + STEP_SIZE).coerceAtMost(MAX_BITRATE)
            }
            else -> currentBitrate
        }

        if (newBitrate != currentBitrate) {
            Log.d(TAG, "Adjusting bitrate: $currentBitrate -> $newBitrate (loss: $packetLoss, rtt: ${rtt}ms)")
            currentBitrate = newBitrate
            streamManager.adjustBitrate(newBitrate)

            // Request keyframe sau khi thay đổi bitrate
            streamManager.requestKeyFrame()
        }
    }

    private fun measurePacketLoss(): Double {
        // TODO: Implement real packet loss measurement
        return 0.05 // 5% packet loss (placeholder)
    }

    private fun measureRTT(): Long {
        // TODO: Implement real RTT measurement
        return 150L // 150ms (placeholder)
    }

    fun stop() {
        monitorJob?.cancel()
    }
}