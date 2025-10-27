package com.example.demoplayvideo

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.example.demoplayvideo.config.DecoderConfigExtractor
import com.example.demoplayvideo.config.MediaFormatConverter
import com.example.demoplayvideo.decoder.AudioDecoderConfig
import com.example.demoplayvideo.decoder.DecoderConfigs
import com.example.demoplayvideo.decoder.VideoDecoderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live Streaming Manager - Quản lý encode và gửi data
 */
class LiveStreamManager(
    private val videoConfig: VideoEncoder.VideoEncoderConfig,
    private val audioConfig: AudioEncoder.AudioEncoderConfig,
    private val scope: CoroutineScope,
    private val onDataReady: (ByteArray, Boolean, Long) -> Unit, // data, isVideo, timestamp
    private val onDecoderConfig: (DecoderConfigs) -> Unit
) {
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private val configExtractor = DecoderConfigExtractor()

    private var videoDecoderConfig: VideoDecoderConfig? = null
    private var audioDecoderConfig: AudioDecoderConfig? = null
    private var configSent = false
    private var codecConfigReceived = false

    var encoderSurface: Surface? = null
        private set

    companion object {
        private const val TAG = "LiveStreamManager"
    }

    fun initialize() {
        // Init video encoder
        videoEncoder = VideoEncoder(videoConfig).apply {
            encoderSurface = initialize()
            startEncoding(scope) { frame ->
                handleEncodedFrame(frame, isVideo = true)
            }
        }

        // Init audio encoder
        audioEncoder = AudioEncoder(audioConfig).apply {
            initialize()
            startEncoding(scope) { audio ->
                handleEncodedAudio(audio)
            }
        }

        Log.d(TAG, "Live stream initialized")
    }

    private fun handleEncodedFrame(frame: VideoEncoder.EncodedFrame, isVideo: Boolean) {
        // Kiểm tra nếu là codec config data (SPS/PPS/VPS)
        if (frame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            codecConfigReceived = true
            Log.d(TAG, "Codec config received, extracting decoder configs...")
            // Trích xuất config từ encoder
            extractAndSendConfigs()
            return // Không gửi config data
        }

        // Đợi cho đến khi config được gửi
        if (!configSent) {
            Log.w(TAG, "Config not sent yet, buffering frame...")
            // Có thể buffer frame hoặc bỏ qua
            return
        }

        // Gửi video frame bình thường
        onDataReady(frame.data, true, frame.timestamp)
    }

    private fun handleEncodedAudio(audio: AudioEncoder.EncodedAudio) {
        if (!configSent) return
        // Gửi audio data
        onDataReady(audio.data, false, audio.timestamp)
    }

    /**
     * Trích xuất và gửi decoder configs
     * GỌI SAU KHI NHẬN ĐƯỢC CODEC CONFIG BUFFER
     */
    private fun extractAndSendConfigs() {
        scope.launch {
            try {
                // Đợi một chút để encoder output format ổn định
                delay(100)

                // Trích xuất video config
                videoEncoder?.codec?.let { codec ->
//                    videoDecoderConfig = configExtractor.extractVideoConfig(
//                        codec,
//                        createVideoFormat()
//                    )
                    videoDecoderConfig = MediaFormatConverter.toVideoConfig(codec.outputFormat)
                }

                // Trích xuất audio config
                audioEncoder?.codec?.let { codec ->
                    audioDecoderConfig = configExtractor.extractAudioConfig(
                        codec,
                        createAudioFormat()
                    )
                }

                // Gửi configs
                if (videoDecoderConfig != null || audioDecoderConfig != null) {
                    val decoderConfigs = DecoderConfigs(
                        type = "DecoderConfigs",
                        videoConfig = videoDecoderConfig,
                        audioConfig = audioDecoderConfig
                    )

                    // Gửi qua WebSocket (dưới dạng JSON text)
                    onDecoderConfig.invoke(decoderConfigs)
                    configSent = true

                    Log.d(
                        TAG,
                        "Decoder configs sent successfully!"
                    )
                } else {
                    Log.e(TAG, "Failed to extract configs")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in config extraction", e)
            }
        }
    }

    private fun createVideoFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(
            videoConfig.codec.mimeType,
            videoConfig.width,
            videoConfig.height
        ).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, videoConfig.frameRate)
        }
    }

    private fun createAudioFormat(): MediaFormat {
        return MediaFormat.createAudioFormat(
            audioConfig.codec.mimeType,
            audioConfig.sampleRate,
            audioConfig.channelCount
        )
    }

    fun encodeAudio(pcmData: ByteArray, timestampUs: Long) {
        audioEncoder?.encode(pcmData, timestampUs)
    }

    fun requestKeyFrame() {
        videoEncoder?.requestKeyFrame()
    }

    fun adjustBitrate(bitrate: Int) {
        videoEncoder?.adjustBitrate(bitrate)
    }

    fun release() {
        videoEncoder?.release()
        audioEncoder?.release()
        Log.d(TAG, "Live stream released")
    }





}