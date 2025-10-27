package com.example.demoplayvideo

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video Encoder cho live streaming
 * Hỗ trợ: H.264 (AVC), H.265 (HEVC)
 */
class VideoEncoder(
    private val config: VideoEncoderConfig
) {
    var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderJob: Job? = null
    private var frameIndex = 0L

    private var firstFrameTimeUs = 0L

    companion object {
        private const val TAG = "VideoEncoder"
        private const val TIMEOUT_US = 10000L
        private const val IFRAME_INTERVAL = 0 // Keyframe mỗi 2 giây
    }

    data class VideoEncoderConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val bitrate: Int = 2_000_000, // 2 Mbps
        val frameRate: Int = 30,
        val iFrameInterval: Int = IFRAME_INTERVAL,
        val codec: CodecType = CodecType.H264,
        val bitrateMode: BitrateMode = BitrateMode.VBR,
        val profile: Int = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        val level: Int = MediaCodecInfo.CodecProfileLevel.AVCLevel4
    )

    enum class CodecType(val mimeType: String) {
        H264(MediaFormat.MIMETYPE_VIDEO_AVC),
        H265(MediaFormat.MIMETYPE_VIDEO_HEVC),
        VP8(MediaFormat.MIMETYPE_VIDEO_VP8),
        VP9(MediaFormat.MIMETYPE_VIDEO_VP9)
    }

    enum class BitrateMode(val value: Int) {
        CBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
        VBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
        CQ(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
    }

    data class EncodedFrame(
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val timestamp: Long,
        val flags: Int
    )

    fun initialize(): Surface {
        try {
            Log.d(TAG, "Initializing encoder: ${config.codec.mimeType}, ${config.width}x${config.height}, ${config.bitrate}bps")

            // Tạo MediaCodec
            codec = MediaCodec.createEncoderByType(config.codec.mimeType)

            // Tạo MediaFormat
            val format = MediaFormat.createVideoFormat(
                config.codec.mimeType,
                config.width,
                config.height
            ).apply {
                // Bitrate settings
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateMode.value)

                // Frame rate
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)

                // I-frame interval
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameInterval)

                // Color format (Surface input)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )

                // Profile và Level (cho H.264/H.265)
                if (config.codec == CodecType.H264 || config.codec == CodecType.H265) {
                    setInteger(MediaFormat.KEY_PROFILE, config.profile)
                    setInteger(MediaFormat.KEY_LEVEL, config.level)
                }

                // Tối ưu cho low latency
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority

                // Repeat previous frame nếu không có input
                setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / config.frameRate)
            }

            // Configure encoder
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Lấy input surface để vẽ lên
            inputSurface = codec?.createInputSurface()

            // Start encoder
            codec?.start()
            isRunning.set(true)

            Log.d(TAG, "Encoder initialized successfully")
            return inputSurface!!
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            throw e
        }
    }

    /**
     * Start encoding và callback khi có frame mới
     */
    fun startEncoding(
        scope: CoroutineScope,
        onFrameEncoded: (EncodedFrame) -> Unit
    ) {
        encoderJob = scope.launch(Dispatchers.Default) {
            val codec = this@VideoEncoder.codec ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()
            val format = codec.outputFormat
            if (format.containsKey("rotation-degrees")) {
                val rotation = format.getInteger("rotation-degrees")
                Log.d(TAG, "Output rotation: $rotation°")
            }

            while (isRunning.get() && isActive) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                            outputBuffer?.let { buffer ->
                                // Kiểm tra nếu là config data (SPS/PPS/VPS)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    Log.d(TAG, "Received codec config data: ${bufferInfo.size} bytes")
                                    // Lưu config để gửi cùng với keyframe đầu tiên
                                }

                                // Copy data
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                val data = ByteArray(bufferInfo.size)
                                buffer.get(data)

                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                // Log keyframe
                                if (isKeyFrame) {
                                    Log.d(TAG, "Encoded keyframe #$frameIndex: ${data.size} bytes")
                                }

                                // Callback
                                if (frameIndex == 0L) {
                                    firstFrameTimeUs = bufferInfo.presentationTimeUs
                                }
                                val timestamp = (bufferInfo.presentationTimeUs - firstFrameTimeUs) / 1000 // ms
//                                val timestamp = bufferInfo.presentationTimeUs
                                val avcc = annexBToAvcc(data)
                                val size = getIntFromFirst4Bytes(avcc)
                                Log.d(TAG, ">>>>>> Encoded frame #$frameIndex: size=$size  data=${avcc.size}")
                                val frame = EncodedFrame(
                                    data = avcc,
                                    isKeyFrame = isKeyFrame,
                                    timestamp = timestamp,
                                    flags = bufferInfo.flags
                                )
//                                Log.e(TAG, "startEncoding: frameIndex=$frameIndex size=${dataFrames.size} data=${data.size} isKeyFrame=$isKeyFrame timestamp=${timestamp}", )
//                                prettyHexDump(data)
                                onFrameEncoded(frame)
                                frameIndex++
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "End of stream")
                                break
                            }
                        }
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            Log.d(TAG, "Output format changed: $newFormat")
                        }
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            delay(5)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error encoding frame", e)
                    if (e is IllegalStateException) {
                        break
                    }
                }
            }
        }
    }

    fun annexBToAvcc(annexB: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(annexB)
        val output = ByteArrayOutputStream()

        while (input.remaining() > 4) {
            // 🔍 Tìm start code (0x00000001 hoặc 0x000001)
            var startCodeOffset = -1
            var startCodeLength = 0
            val pos = input.position()

            while (input.remaining() >= 3) {
                if (input.get(input.position()).toInt() == 0x00 &&
                    input.get(input.position() + 1).toInt() == 0x00 &&
                    input.get(input.position() + 2).toInt() == 0x01) {
                    startCodeOffset = input.position()
                    startCodeLength = 3
                    break
                } else if (input.remaining() >= 4 &&
                    input.get(input.position()).toInt() == 0x00 &&
                    input.get(input.position() + 1).toInt() == 0x00 &&
                    input.get(input.position() + 2).toInt() == 0x00 &&
                    input.get(input.position() + 3).toInt() == 0x01) {
                    startCodeOffset = input.position()
                    startCodeLength = 4
                    break
                }
                input.position(input.position() + 1)
            }

            if (startCodeOffset == -1) break // không còn start code

            // Skip start code
            input.position(startCodeOffset + startCodeLength)
            val naluStart = input.position()

            // 🔍 Tìm start code tiếp theo để xác định độ dài NALU
            var nextStartCodeOffset = -1
            while (input.remaining() >= 3) {
                if ((input.get(input.position()).toInt() == 0x00 &&
                            input.get(input.position() + 1).toInt() == 0x00 &&
                            ((input.get(input.position() + 2).toInt() == 0x01) ||
                                    (input.remaining() >= 4 &&
                                            input.get(input.position() + 2).toInt() == 0x00 &&
                                            input.get(input.position() + 3).toInt() == 0x01)))) {
                    nextStartCodeOffset = input.position()
                    break
                }
                input.position(input.position() + 1)
            }

            val naluEnd =
                if (nextStartCodeOffset != -1) nextStartCodeOffset else annexB.size
            val naluLength = naluEnd - naluStart

            // 🔢 Ghi 4-byte length prefix
            val lengthBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(naluLength)
                .array()
            output.write(lengthBytes)

            // 🧩 Ghi dữ liệu NALU
            output.write(annexB, naluStart, naluLength)

            if (nextStartCodeOffset == -1) break // hết dữ liệu
            input.position(nextStartCodeOffset)
        }

        return output.toByteArray()
    }
    fun getIntFromFirst4Bytes(data: ByteArray): Int {
        require(data.size >= 4) { "ByteArray phải có ít nhất 4 byte" }

        return ByteBuffer.wrap(data, 0, 4)
            .order(ByteOrder.BIG_ENDIAN) // hoặc LITTLE_ENDIAN tuỳ format
            .int
    }

    fun parseLengthPrefixedNALUs(data: ByteArray): List<ByteArray> {
        val nalus = mutableListOf<ByteArray>()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        while (buffer.remaining() > 4) {
            // Đọc 4 bytes đầu để lấy length của NALU
            val length = buffer.getInt()
            Log.d(TAG, ">>>>>>> length=${length} data=${data.size}")
            if (length <= 0 || length > buffer.remaining()) {
                break // dữ liệu lỗi hoặc hết buffer
            }

            // Đọc NALU data theo độ dài vừa lấy
            val nalu = ByteArray(length)
            buffer.get(nalu)

            nalus.add(nalu)
        }

        return nalus
    }

    private fun prettyHexDump(data: ByteArray, bytesPerLine: Int = 16) {
        val sb = StringBuilder()
        for (i in data.indices step bytesPerLine) {
            val line = data
                .slice(i until minOf(i + bytesPerLine, data.size))
                .joinToString(" ") { String.format("%02X", it) }
            sb.append(String.format("%04X: %s\n", i, line))
        }
        Log.d("HexDump", "\n$sb")
    }

    /**
     * Request keyframe ngay lập tức
     */
    fun requestKeyFrame() {
        try {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
            Log.d(TAG, "Keyframe requested")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting keyframe", e)
        }
    }

    /**
     * Điều chỉnh bitrate động (adaptive bitrate)
     */
    fun adjustBitrate(newBitrate: Int) {
        try {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                }
            )
            Log.d(TAG, "Bitrate adjusted to: $newBitrate bps")
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting bitrate", e)
        }
    }

    /**
     * Điều chỉnh frame rate
     */
    fun adjustFrameRate(newFrameRate: Int) {
        try {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, config.bitrate)
//                    putFloat(MediaCodec.PARAMETER_KEY_FRAME_RATE, newFrameRate.toFloat())
                }
            )
            Log.d(TAG, "Frame rate adjusted to: $newFrameRate fps")
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting frame rate", e)
        }
    }

    fun release() {
        isRunning.set(false)
        encoderJob?.cancel()

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

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing surface", e)
        }

        codec = null
        inputSurface = null
        Log.d(TAG, "Encoder released")
    }
}

/**
 * Audio Encoder cho live streaming
 */
class AudioEncoder(
    private val config: AudioEncoderConfig
) {
    var codec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderJob: Job? = null
    private var frameIndex = 0L

    private var firstFrameTimeUs = 0L

    companion object {
        private const val TAG = "AudioEncoder"
        private const val TIMEOUT_US = 10000L
    }

    data class AudioEncoderConfig(
        val sampleRate: Int = 48000,
        val channelCount: Int = 2,
        val bitrate: Int = 128_000, // 128 kbps
        val codec: CodecType = CodecType.AAC
    )

    enum class CodecType(val mimeType: String) {
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC),
        OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS)
    }

    data class EncodedAudio(
        val data: ByteArray,
        val timestamp: Long
    )

    fun initialize() {
        try {
            Log.d(TAG, "Initializing audio encoder: ${config.codec.mimeType}, ${config.sampleRate}Hz, ${config.channelCount}ch")

            codec = MediaCodec.createEncoderByType(config.codec.mimeType)

            val format = MediaFormat.createAudioFormat(
                config.codec.mimeType,
                config.sampleRate,
                config.channelCount
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

                if (config.codec == CodecType.AAC) {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                }
            }

            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()
            isRunning.set(true)

            Log.d(TAG, "Audio encoder initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio encoder", e)
            throw e
        }
    }

    /**
     * Encode PCM audio data
     */
    fun encode(pcmData: ByteArray, presentationTimeUs: Long) {
        val codec = this.codec ?: return

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.apply {
                    clear()
                    put(pcmData)
                }

                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    pcmData.size,
                    presentationTimeUs,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding audio", e)
        }
    }

    fun startEncoding(
        scope: CoroutineScope,
        onAudioEncoded: (EncodedAudio) -> Unit
    ) {
        encoderJob = scope.launch(Dispatchers.Default) {
            val codec = this@AudioEncoder.codec ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get() && isActive) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                            outputBuffer?.let { buffer ->
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    val data = ByteArray(bufferInfo.size)
                                    buffer.get(data)
                                    if (frameIndex == 0L) {
                                        firstFrameTimeUs = bufferInfo.presentationTimeUs
                                    }
                                    val timestamp = (bufferInfo.presentationTimeUs - firstFrameTimeUs) / 1000 // ms
                                    onAudioEncoded(
                                        EncodedAudio(
                                            data = data,
                                            timestamp = timestamp
                                        )
                                    )
                                    frameIndex++
                                }
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            delay(5)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing audio", e)
                    if (e is IllegalStateException) break
                }
            }
        }
    }

    fun release() {
        isRunning.set(false)
        encoderJob?.cancel()

        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio encoder", e)
        }

        codec = null
        Log.d(TAG, "Audio encoder released")
    }
}