package com.example.demoplayvideo.decoder


import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class DecoderConfigs(
    val type: String,
    val videoConfig: VideoDecoderConfig?,
    val audioConfig: AudioDecoderConfig?
)

data class VideoDecoderConfig(
    val codec: String,
    val codedWidth: Int,
    val codedHeight: Int,
    val frameRate: Int,
    val description: String
)

data class AudioDecoderConfig(
    val sampleRate: Int,
    val numberOfChannels: Int,
    val codec: String,
    val description: String
)

class VideoDecoder(
    private val config: VideoDecoderConfig,
    private val surface: Surface? = null,
    private val maxInitDecoder: Int = 1
) {
    private var codec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var decoderJob: Job? = null

    private var initializeCount = 0

    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10000L
    }

    fun initialize() {
        initializeCount++
        try {
            val mimeType = getMimeType(config.codec)
            Log.d(TAG, "Initializing video decoder: $mimeType")

            codec = MediaCodec.createDecoderByType(mimeType)

            val format = MediaFormat.createVideoFormat(
                mimeType,
                config.codedWidth,
                config.codedHeight
            ).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)

                // Decode CSD (Codec Specific Data) từ description
                val csdData = Base64.decode(config.description, Base64.DEFAULT)

                when {
                    mimeType == MediaFormat.MIMETYPE_VIDEO_AVC -> {
                        // H.264/AVC: Tách SPS và PPS
                        val (sps, pps) = extractAvcCsd(csdData)
//                        setByteBuffer("csd-0", ByteBuffer.wrap(sps))
//                        setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                        setByteBuffer("csd-0", ByteBuffer.wrap(convertCsdToAnnexB(sps)))
                        setByteBuffer("csd-1", ByteBuffer.wrap(convertCsdToAnnexB(pps)))
                    }

                    mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                        // H.265/HEVC: Set CSD-0
                        setByteBuffer("csd-0", ByteBuffer.wrap(csdData))
                    }
                }

                // Cấu hình color format
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
            }

            codec?.configure(format, surface, null, 0)
            codec?.start()
            isRunning.set(true)

            Log.d(TAG, "Video decoder initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video decoder", e)
            throw e
        }
    }

    private fun getMimeType(codec: String): String {
        return when {
            codec.startsWith("avc1") -> MediaFormat.MIMETYPE_VIDEO_AVC
            codec.startsWith("hev1") || codec.startsWith("hvc1") -> MediaFormat.MIMETYPE_VIDEO_HEVC
            codec.startsWith("vp09") -> MediaFormat.MIMETYPE_VIDEO_VP9
            codec.startsWith("vp08") -> MediaFormat.MIMETYPE_VIDEO_VP8
            codec.startsWith("av01") -> "video/av01" // AV1
            else -> throw IllegalArgumentException("Unsupported codec: $codec")
        }
    }

    private fun extractAvcCsd(data: ByteArray): Pair<ByteArray, ByteArray> {
        // Parse avcC box để lấy SPS và PPS
        var offset = 0

        // Skip version, profile, compatibility, level (4 bytes)
        offset += 4

        // NAL unit length size
        offset += 1

        // Number of SPS
        val numSps = data[offset].toInt() and 0x1F
        offset += 1

        // SPS
        val spsLength =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val sps = data.copyOfRange(offset, offset + spsLength)
        offset += spsLength

        // Number of PPS
        val numPps = data[offset].toInt() and 0xFF
        offset += 1

        // PPS
        val ppsLength =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val pps = data.copyOfRange(offset, offset + ppsLength)
        Log.e(TAG, "CSD hex: ${sps.take(20).joinToString(" ") { "%02x".format(it) }}...")
        Log.e(TAG, "CSD hex: ${pps.take(20).joinToString(" ") { "%02x".format(it) }}...")
        return Pair(sps, pps)
    }

    fun convertCsdToAnnexB(csd: ByteArray): ByteArray {
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val output = ByteArrayOutputStream()

        // Thêm start code + csd
        output.write(startCode)
        output.write(csd)

        return output.toByteArray()
    }

    fun decode(encodedData: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean = false) {
        val codec = this.codec ?: return

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.apply {
                    clear()
                    put(encodedData)
                }

                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    encodedData.size,
                    presentationTimeUs,
                    flags
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding video frame", e)
            handleError(e)
        }
    }

    fun startRendering(scope: CoroutineScope, onFrameRendered: (Long) -> Unit = {}) {
        decoderJob = scope.launch(Dispatchers.Default) {
            val codec = this@VideoDecoder.codec ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get() && isActive) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            // Render frame
                            codec.releaseOutputBuffer(outputBufferIndex, true)
                            onFrameRendered(bufferInfo.presentationTimeUs)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "End of stream reached")
                                break
                            }
                        }

                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            Log.d(TAG, "Output format changed: $newFormat")
                        }

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output available yet
                            delay(10)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error rendering frame", e)
                    if (e is IllegalStateException) {
                        break
                    }
                }
            }
        }
    }

    private fun handleError(e: Exception) {
        if (e is IllegalStateException || e is MediaCodec.CodecException) {
            Log.w(TAG, "Codec error, attempting recovery")
            release()
            try {
                if (initializeCount > maxInitDecoder) {
                    Log.e(TAG, "Exceeded maximum initialization attempts")
                    return
                }
                initialize()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to recover codec", ex)
            }
        }
    }

    fun flush() {
        try {
            codec?.flush()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot flush codec in current state", e)
            handleError(e)
        }
    }

    fun release() {
        isRunning.set(false)
        decoderJob?.cancel()

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping codec", e)
        }

        try {
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing codec", e)
        }

        codec = null
        Log.d(TAG, "Video decoder released")
    }
}

class AudioDecoder(
    private val config: AudioDecoderConfig,
    private val maxInitDecoder: Int = 1,
) {
    private var mediaCodec: MediaCodec? = null
    private var opusDecoder: OpusDecoder? = null
    private val isRunning = AtomicBoolean(false)
    private var decoderJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var initializeCount = 0

    companion object {
        private const val TAG = "AudioDecoder"
        private const val TIMEOUT_US = 10000L
    }

    fun initialize() {
        try {
            initializeCount++
            val mimeType = getMimeType(config.codec)
            Log.d(TAG, "Initializing audio decoder: $mimeType")

            if (mimeType == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                // Khởi tạo Opus decoder
                opusDecoder = OpusDecoder(
                    sampleRate = config.sampleRate,
                    channels = config.numberOfChannels
                )
                mediaCodec = null
            } else {
                opusDecoder = null
                mediaCodec = MediaCodec.createDecoderByType(mimeType)

                val format = MediaFormat.createAudioFormat(
                    mimeType,
                    config.sampleRate,
                    config.numberOfChannels
                ).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384) //16KB

                    // Decode CSD từ description
                    val csdData = Base64.decode(config.description, Base64.DEFAULT)
                    setByteBuffer("csd-0", ByteBuffer.wrap(csdData))

                    // AAC profile
                    if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        setInteger(
                            MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC
                        )
                    }
                }

                mediaCodec?.configure(format, null, null, 0)
                mediaCodec?.start()
            }

            isRunning.set(true)
            // Khởi tạo AudioTrack
            initAudioTrack()
            Log.d(TAG, "Audio decoder initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio decoder", e)
            throw e
        }
    }

    private fun initAudioTrack() {
        try {
            val channelConfig = if (config.numberOfChannels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val encoding = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioTrack.getMinBufferSize(
                config.sampleRate,
                channelConfig,
                encoding
            )

            // Tạo buffer lớn hơn để tránh underrun
            val bufferSize = minBufferSize * 4

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()

            Log.d(
                TAG,
                "AudioTrack initialized: sampleRate=${config.sampleRate}, channels=${config.numberOfChannels}, bufferSize=$bufferSize"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            throw e
        }
    }

    private fun getMimeType(codec: String): String {
        return when {
            codec.startsWith("mp4a") -> MediaFormat.MIMETYPE_AUDIO_AAC
            codec.startsWith("opus") -> MediaFormat.MIMETYPE_AUDIO_OPUS
            codec.startsWith("vorbis") -> MediaFormat.MIMETYPE_AUDIO_VORBIS
            else -> throw IllegalArgumentException("Unsupported codec: $codec")
        }
    }

    fun decode(encodedData: ByteArray, presentationTimeUs: Long) {
        // If using Opus wrapper, decode to PCM and write to AudioTrack directly
        opusDecoder?.let { wrapper ->
            val pcm = wrapper.decode(encodedData)
            if (pcm.isNotEmpty()) {
                try {
                    val written = audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) ?: 0
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack write error (opus): $written")
                    } else if (written != pcm.size) {
                        Log.w(TAG, "AudioTrack underrun (opus): written=$written, expected=${pcm.size}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing opus PCM to AudioTrack", e)
                }
            }
            return
        }

        val codec = this.mediaCodec ?: return
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.apply {
                    clear()
                    put(encodedData)
                }

                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    encodedData.size,
                    presentationTimeUs,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio frame", e)
            handleError(e)
        }
    }

    fun startDecoding(scope: CoroutineScope) {
        // If using Opus wrapper, we don't need codec output loop
        if (opusDecoder != null) {
            // no-op; audio is written directly in decode(...)
            return
        }

        decoderJob = scope.launch(Dispatchers.Default) {
            val codec = this@AudioDecoder.mediaCodec ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get() && isActive) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            outputBuffer?.let { buffer ->
                                // Play audio qua AudioTrack
                                playAudio(buffer, bufferInfo)
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "End of stream reached")
                                break
                            }
                        }

                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            Log.d(TAG, "Output format changed: $newFormat")

                            // Update AudioTrack nếu format thay đổi
                            val sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                            if (sampleRate != config.sampleRate || channels != config.numberOfChannels) {
                                Log.w(TAG, "Format changed! Reinitializing AudioTrack...")
                                audioTrack?.stop()
                                audioTrack?.release()
                                initAudioTrack()
                            }
                        }

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            delay(10)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing audio", e)
                    if (e is IllegalStateException) {
                        break
                    }
                }
            }
        }
    }

    private fun playAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val track = audioTrack ?: return

        try {
            // Đọc PCM data từ buffer
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)

            val pcmData = ByteArray(info.size)
            buffer.get(pcmData)

            // Ghi vào AudioTrack
            val written = track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)

            if (written < 0) {
                Log.e(TAG, "AudioTrack write error: $written")
            } else if (written != pcmData.size) {
                Log.w(TAG, "AudioTrack underrun: written=$written, expected=${pcmData.size}")
            }

            // Log thông tin
            if (info.presentationTimeUs % 1000000 < 50000) { // Log mỗi giây
                Log.d(TAG, "Audio playing: ${pcmData.size} bytes, pts=${info.presentationTimeUs}us")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
        }
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun pause() {
        try {
            audioTrack?.pause()
            Log.d(TAG, "Audio paused")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing audio", e)
        }
    }

    fun resume() {
        try {
            audioTrack?.play()
            Log.d(TAG, "Audio resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming audio", e)
        }
    }

    private fun handleError(e: Exception) {
        if (e is IllegalStateException) {
            Log.w(TAG, "Codec error, attempting recovery")
            release()
            try {
                if (initializeCount > maxInitDecoder) {
                    Log.e(TAG, "Exceeded maximum initialization attempts")
                    return
                }
                initialize()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to recover codec", ex)
            }
        }
    }

    fun flush() {
        try {
            mediaCodec?.flush()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot flush codec in current state", e)
            handleError(e)
        }
    }

    fun release() {
        isRunning.set(false)
        decoderJob?.cancel()

        // Stop và release AudioTrack
        try {
            audioTrack?.stop()
            audioTrack?.flush()
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "AudioTrack released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }

        // Release Opus wrapper if used
        try {
            opusDecoder?.release()
            opusDecoder = null
            Log.d(TAG, "Opus decoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Opus decoder", e)
        }

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping codec", e)
        }

        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing codec", e)
        }

        mediaCodec = null
        Log.d(TAG, "Audio decoder released")
    }
}

// Sử dụng
class MediaDecoderManager(
    private val decoderConfigs: DecoderConfigs,
    private val videoSurface: Surface,
    private val scope: CoroutineScope,
    private val maxInitDecoder: Int = 1
) {
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null

    fun initialize() {
        decoderConfigs.videoConfig?.let { config ->
            videoDecoder = VideoDecoder(config, videoSurface, maxInitDecoder).apply {
                initialize()
                startRendering(scope) { pts ->
                    Log.d("MediaDecoder", "Frame rendered at $pts us")
                }
            }
        }

        decoderConfigs.audioConfig?.let { config ->
            audioDecoder = AudioDecoder(config, maxInitDecoder).apply {
                initialize()
                startDecoding(scope)
            }
        }
    }

    fun decodeVideo(data: ByteArray, pts: Long, isKeyFrame: Boolean = false) {
        videoDecoder?.decode(data, pts, isKeyFrame)
    }

    fun decodeAudio(data: ByteArray, pts: Long) {
        audioDecoder?.decode(data, pts)
    }

    fun setAudioVolume(volume: Float) {
        audioDecoder?.setVolume(volume)
    }

    fun pauseAudio() {
        audioDecoder?.pause()
    }

    fun resumeAudio() {
        audioDecoder?.resume()
    }

    fun release() {
        videoDecoder?.release()
        audioDecoder?.release()
    }
}