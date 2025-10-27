package com.example.demoplayvideo

import android.view.Surface
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.demoplayvideo.decoder.DecoderConfigs

/**
 * Ví dụ sử dụng đơn giản
 */
class SimpleStreamExample {

    fun startSimpleLiveStream(activity: AppCompatActivity, surfaceView: SurfaceView) {
        // 1. Cấu hình đơn giản
        val videoConfig = EncoderPresets.PRESET_720P_MEDIUM
        val audioConfig = EncoderPresets.AUDIO_MEDIUM

        // 2. Khởi tạo stream manager
        val streamManager = LiveStreamManager(
            videoConfig = videoConfig,
            audioConfig = audioConfig,
            scope = activity.lifecycleScope,
            onDataReady = { data, isVideo, timestamp ->
                // Gửi data đi (WebSocket, RTMP, v.v.)
                sendData(data, isVideo, timestamp)
            },
            onDecoderConfig = { configs ->
                // Gửi decoder configs đi
                sendDecoderConfigs(configs)
            }
        )
        streamManager.initialize()

        // 3. Setup camera vào surface của encoder
        setupCameraWithEncoder(activity, streamManager.encoderSurface!!)

        // 4. Setup audio
        setupAudioRecording(activity, streamManager)

        // 5. Adaptive bitrate (optional)
        val abrManager = AdaptiveBitrateManager(streamManager, activity.lifecycleScope)
        abrManager.start()
    }

    private fun sendData(data: ByteArray, isVideo: Boolean, timestamp: Long) {
        // Implement gửi data qua network
        // VD: WebSocket, RTMP, HLS, v.v.
    }

    private fun sendDecoderConfigs(configs: DecoderConfigs) {
        // Implement gửi data qua network
        // VD: WebSocket, RTMP, HLS, v.v.
    }

    private fun setupCameraWithEncoder(activity: AppCompatActivity, surface: Surface) {
        // Implement camera setup
    }

    private fun setupAudioRecording(activity: AppCompatActivity, streamManager: LiveStreamManager) {
        // Implement audio recording
    }
}