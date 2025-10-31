package com.example.demoplayvideo.config

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.demoplayvideo.VideoEncoder
import com.example.demoplayvideo.decoder.AudioDecoderConfig
import com.example.demoplayvideo.decoder.VideoDecoder
import com.example.demoplayvideo.decoder.VideoDecoderConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trích xuất DecoderConfigs từ MediaCodec
 */
class DecoderConfigExtractor {

    companion object {
        private const val TAG = "ConfigExtractor"
    }

    /**
     * Trích xuất video config từ encoder
     */
    fun extractVideoConfig(
        codec: MediaCodec,
        videoConfig: VideoEncoder.VideoEncoderConfig
    ): VideoDecoderConfig? {
        return try {
            val outputFormat = codec.outputFormat
            Log.d(TAG, "Converting MediaFormat to VideoConfig...")
            Log.d(TAG, "Input format: $outputFormat")

            // Extract basic info
            val width = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = outputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            val mimeType = outputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"

            Log.d(TAG, "Extracted: ${width}x${height} @ ${frameRate}fps, mime=$mimeType")

            // Extract and combine CSD
            val csd = extractAndCombineCSD(outputFormat, mimeType)
            if (csd == null) {
                Log.e(TAG, "Failed to extract CSD")
                return null
            }

            Log.d(TAG, "CSD extracted: ${csd.size} bytes")
            Log.d(TAG, "CSD hex: ${csd.take(20).joinToString(" ") { "%02x".format(it) }}...")

            // Generate codec string
            val codecString = generateCodecString(mimeType, outputFormat, csd, videoConfig)
            Log.d(TAG, "Codec string: $codecString")

            // Base64 encode CSD
            val description = Base64.encodeToString(csd, Base64.NO_WRAP)
            Log.d(TAG, "Description (base64): ${description.take(50)}...")

            val config = VideoDecoderConfig(
                codec = codecString,
                codedWidth = width,
                codedHeight = height,
                frameRate = frameRate,
                description = description
            )

            Log.d(TAG, "✅ Conversion successful!")
            return config

        } catch (e: Exception) {
            Log.e(TAG, "Error converting format", e)
            null
        }
    }

    /**
     * Extract và combine CSD buffers
     */
    private fun extractAndCombineCSD(format: MediaFormat, mimeType: String): ByteArray? {
        return when {
            mimeType.contains("avc") -> {
                // H.264: Combine csd-0 (SPS) và csd-1 (PPS) thành avcC format
                val csd0 = format.getByteBuffer("csd-0") ?: run {
                    Log.e(TAG, "No csd-0 for H.264")
                    return null
                }
                val csd1 = format.getByteBuffer("csd-1") ?: run {
                    Log.e(TAG, "No csd-1 for H.264")
                    return null
                }
                Log.e(TAG, "")
                combineH264CSD(csd0, csd1)
            }

            mimeType.contains("hevc") -> {
                // H.265: csd-0 chứa VPS+SPS+PPS
                val csd0 = format.getByteBuffer("csd-0") ?: run {
                    Log.e(TAG, "No csd-0 for H.265")
                    return null
                }

                ByteArray(csd0.remaining()).also {
                    csd0.get(it)
                    csd0.rewind()
                }
            }

            else -> {
                Log.w(TAG, "Unsupported mime type: $mimeType")
                null
            }
        }
    }

    /**
     * Combine H.264 SPS và PPS thành avcC format
     * avcC structure:
     * [1] version = 1
     * [1] profile
     * [1] compatibility
     * [1] level
     * [1] reserved (6 bits) + NAL length size - 1 (2 bits)
     * [1] reserved (3 bits) + num SPS (5 bits)
     * [2] SPS length
     * [n] SPS data
     * [1] num PPS
     * [2] PPS length
     * [m] PPS data
     */
    private fun combineH264CSD(spsBuffer: ByteBuffer, ppsBuffer: ByteBuffer): ByteArray {
        // Extract SPS
        val sps1 = ByteArray(spsBuffer.remaining())
        spsBuffer.get(sps1)
        spsBuffer.rewind()
        val sps = removeNALStartCode(sps1)

        // Extract PPS
        val pps1 = ByteArray(ppsBuffer.remaining())
        ppsBuffer.get(pps1)
        ppsBuffer.rewind()
        val pps = removeNALStartCode(pps1)

        // Parse profile, level từ SPS
        // SPS format: [NAL header][profile][constraint][level]...
        val profile = if (sps.size > 1) sps[1] else 0x64.toByte() // High profile default
        val compatibility = if (sps.size > 2) sps[2] else 0x00.toByte()
        val level = if (sps.size > 3) sps[3] else 0x28.toByte() // Level 4.0

        Log.e(
            TAG,
            "H.264 Profile: 0x${profile.toString(16)}, compatibility=${compatibility.toString(16)} Level: 0x${
                level.toString(16)
            }"
        )

        // Build avcC
        val avcC = ByteArray(11 + sps.size + pps.size)
        var offset = 0

        // Header
        avcC[offset++] = 0x01 // version
        avcC[offset++] = profile
        avcC[offset++] = compatibility
        avcC[offset++] = level
        avcC[offset++] = 0xFF.toByte() // 6 bits reserved (111111) + lengthSizeMinusOne = 3 (11)

        // SPS
        avcC[offset++] =
            0xE1.toByte() // 3 bits reserved (111) + numOfSequenceParameterSets = 1 (00001)
        avcC[offset++] = ((sps.size shr 8) and 0xFF).toByte() // SPS length high byte
        avcC[offset++] = (sps.size and 0xFF).toByte() // SPS length low byte
        System.arraycopy(sps, 0, avcC, offset, sps.size)
        offset += sps.size

        // PPS
        avcC[offset++] = 0x01 // numOfPictureParameterSets
        avcC[offset++] = ((pps.size shr 8) and 0xFF).toByte() // PPS length high byte
        avcC[offset++] = (pps.size and 0xFF).toByte() // PPS length low byte
        System.arraycopy(pps, 0, avcC, offset, pps.size)

        Log.d(TAG, "avcC created: ${avcC.size} bytes")

        return avcC
    }

    /**
     * Generate codec string
     */
    private fun generateCodecString(
        mimeType: String,
        format: MediaFormat,
        csd: ByteArray,
        videoConfig: VideoEncoder.VideoEncoderConfig
    ): String {
        return when {
            mimeType.contains("avc") -> {

                // Lấy profile + level để tạo chuỗi codec (vd: avc1.640028)
                val profile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    format.getInteger(MediaFormat.KEY_PROFILE, videoConfig.profile)
                } else {
                    videoConfig.profile
                }
                val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    format.getInteger(MediaFormat.KEY_LEVEL, videoConfig.level)
                } else {
                    videoConfig.level
                }
                getAvcCodecString(mimeType, profile, level)
            }

            mimeType.contains("hevc") -> {
                // hev1.PROFILE.FLAGS.LEVEL.TIER
                var profile = 1 // Main profile
                var level = 93 // Level 3.1

                try {
                    if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                        profile = format.getInteger(MediaFormat.KEY_PROFILE)
                    }
                    if (format.containsKey(MediaFormat.KEY_LEVEL)) {
                        level = format.getInteger(MediaFormat.KEY_LEVEL)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get profile/level", e)
                }

                "hev1.$profile.06.L$level.b0"
            }

            else -> "avc1.640028"
        }
    }

    /**
     * Trích xuất audio config từ encoder
     */
    fun extractAudioConfig(codec: MediaCodec): AudioDecoderConfig? {
        try {
            val outputFormat = codec.outputFormat

            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mimeType = outputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            // Lấy CSD
            val csd = extractAudioCSD(outputFormat, mimeType)
            if (csd == null) {
                Log.w(TAG, "No audio CSD found")
                return null
            }
            // AAC codec string
            val codecString = when {
                mimeType.contains("aac") -> "mp4a.40.2" // AAC-LC
                mimeType.contains("opus") -> "opus"
                else -> "mp4a.40.2"
            }

            val description = Base64.encodeToString(csd, Base64.NO_WRAP)

            Log.d(TAG, "Audio config extracted: $codecString, ${sampleRate}Hz, ${channelCount}ch")

            return AudioDecoderConfig(
                sampleRate = sampleRate,
                numberOfChannels = channelCount,
                codec = codecString,
                description = description
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio config", e)
            return null
        }
    }

    /**
     * Trích xuất CSD từ MediaFormat hoặc codec config buffer
     */
    private fun extractAudioCSD(format: MediaFormat, mimeType: String): ByteArray? {
        when {
            mimeType.contains("aac") || mimeType.contains("mp4a-latm") -> {
                // AAC: Lấy csd-0 (AudioSpecificConfig)
                return format.getByteBuffer("csd-0")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
            }

            mimeType.contains("opus") -> {
                val csd0 = format.getByteBuffer("csd-0")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } ?: return null
                val opusHead = extractOpusHead(csd0) ?: return null
                Log.e(
                    TAG,
                    "extractAudioCSD opusHead size=${opusHead.size} hex: ${
                        opusHead.joinToString(" ") {
                            "%02x".format(it)
                        }
                    }..."
                )
                return opusHead
            }

            else -> return null
        }
    }

    /**
     * Parse full csd-0 (Codec Specific Data) from Android MediaCodec Opus encoder.
     * It extracts only the real "OpusHead" block and ignores "AOPUSHDR", "AOPUSDLY", "AOPUSPRL".
     *
     * @param csd0 The full ByteArray of csd-0 from MediaCodec
     * @return A ByteArray containing only the valid OpusHead header (19 bytes), or null if not found.
     */
    fun extractOpusHead(csd0: ByteArray): ByteArray? {
        val opusMagic = "OpusHead".encodeToByteArray()
        val start = indexOfSequence(csd0, opusMagic)
        if (start == -1) {
            Log.e(TAG, "OpusHead not found in csd-0")
            return null
        }

        // Header length per RFC 7845 (OpusHead) is at least 19 bytes
        val headerLength = 19
        if (csd0.size < start + headerLength) {
            Log.e(TAG, "Invalid csd-0: too short for OpusHead header")
            return null
        }

        val header = csd0.copyOfRange(start, start + headerLength)
        Log.d(TAG, "Extracted OpusHead header (${header.size} bytes)")
        Log.d(TAG, "OpusHead (Base64): ${Base64.encodeToString(header, Base64.NO_WRAP)}")

        return header
    }

    /**
     * Utility: find the start index of a byte sequence inside another ByteArray.
     */
    private fun indexOfSequence(data: ByteArray, sequence: ByteArray): Int {
        outer@ for (i in 0..data.size - sequence.size) {
            for (j in sequence.indices) {
                if (data[i + j] != sequence[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Generate OpusHead nếu không có CSD
     *
     * OpusHead structure (19 bytes minimum):
     * [8 bytes] "OpusHead" magic signature
     * [1 byte]  Version (always 1)
     * [1 byte]  Channel Count
     * [2 bytes] Pre-skip (little-endian)
     * [4 bytes] Sample Rate (little-endian)
     * [2 bytes] Output Gain (little-endian, default 0)
     * [1 byte]  Channel Mapping Family (0 = mono/stereo)
     */
    fun generateOpusHead(sampleRate: Int, channelCount: Int): ByteArray {
        val buffer = ByteBuffer.allocate(19)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Magic signature "OpusHead"
        buffer.put("OpusHead".toByteArray(Charsets.US_ASCII))

        // Version
        buffer.put(1)

        // Channel count
        buffer.put(channelCount.toByte())

        // Pre-skip (312 samples for 48kHz, scale for other rates)
        val preSkip = (312 * sampleRate) / 48000
        buffer.putShort(preSkip.toShort())

        // Input sample rate (original sample rate)
        buffer.putInt(sampleRate)

        // Output gain (0 dB)
        buffer.putShort(0)

        // Channel mapping family (0 for mono/stereo)
        buffer.put(0)

        val opusHead = buffer.array()

        Log.d(TAG, "Generated OpusHead: ${opusHead.size} bytes")
        Log.d(TAG, "  Sample Rate: ${sampleRate}Hz")
        Log.d(TAG, "  Channels: $channelCount")
        Log.d(TAG, "  Pre-skip: $preSkip")

        return opusHead
    }

    /**
     * Generate AVC codec string
     *
     * @param mime MIME type (should be video/avc)
     * @param profile MediaCodecInfo profile constant
     * @param level MediaCodecInfo level constant
     * @return codec string (e.g., "avc1.640028")
     */
    fun getAvcCodecString(mime: String, profile: Int, level: Int): String {
        if (!mime.contains("avc")) {
            Log.w(TAG, "Not an AVC MIME type: $mime")
            return "avc1.640028" // Default fallback
        }
        // Convert MediaCodecInfo constants to hex values
        val profileHex = getProfileHex(profile)
        val constraintHex = getConstraintFlags(profile)
        val levelHex = getLevelHex(level)

        val codecString = "avc1.%02x%02x%02x".format(profileHex, constraintHex, levelHex)

        Log.d(TAG, "Generated codec string: $codecString")
        return codecString
    }

    /**
     * Get profile hex value from MediaCodecInfo constant
     */
    private fun getProfileHex(profile: Int): Int {
        return when (profile) {
            // Baseline Profile (0x42)
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> 0x42

            // Main Profile (0x4D)
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> 0x4D

            // Extended Profile (0x58)
            MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> 0x58

            // High Profile (0x64)
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> 0x64

            // High 10 Profile (0x6E)
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> 0x6E

            // High 4:2:2 Profile (0x7A)
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> 0x7A

            // High 4:4:4 Predictive Profile (0xF4)
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> 0xF4

            // Constrained Baseline (0x42 with constraint set 1)
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> 0x42

            // Constrained High (0x64 with constraints)
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> 0x64

            else -> {
                Log.w(TAG, "Unknown profile: $profile, using High (0x64)")
                0x64
            }
        }
    }

    /**
     * Get constraint flags based on profile
     *
     * Constraint flags (8 bits):
     * - Bit 7 (0x80): Reserved (always 0)
     * - Bit 6 (0x40): constraint_set0_flag (Baseline)
     * - Bit 5 (0x20): constraint_set1_flag (Main)
     * - Bit 4 (0x10): constraint_set2_flag (Extended)
     * - Bit 3 (0x08): constraint_set3_flag (High)
     * - Bit 2 (0x04): constraint_set4_flag
     * - Bit 1 (0x02): constraint_set5_flag
     * - Bit 0 (0x01): Reserved (always 0)
     */
    private fun getConstraintFlags(profile: Int): Int {
        return when (profile) {
            // Constrained Baseline: Sets constraint_set0_flag (0x40) and constraint_set1_flag (0x20)
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> 0xC0

            // Constrained High: Sets constraint_set4_flag and constraint_set5_flag
            MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> 0x0C

            // Baseline: constraint_set0_flag (0x40)
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> 0x00

            // Main: constraint_set1_flag (0x20)
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> 0x00

            // Extended: constraint_set2_flag (0x10)
            MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> 0x00

            // High profiles: Usually no constraint flags
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> 0x00

            else -> 0x00
        }
    }

    /**
     * Get level hex value from MediaCodecInfo constant
     */
    private fun getLevelHex(level: Int): Int {
        return when (level) {
            // Level 1
            MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> 0x0A

            // Level 1b
            MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> 0x09

            // Level 1.1
            MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> 0x0B

            // Level 1.2
            MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> 0x0C

            // Level 1.3
            MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> 0x0D

            // Level 2
            MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> 0x14

            // Level 2.1
            MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> 0x15

            // Level 2.2
            MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> 0x16

            // Level 3
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> 0x1E

            // Level 3.1
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> 0x1F

            // Level 3.2
            MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> 0x20

            // Level 4
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> 0x28

            // Level 4.1
            MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> 0x29

            // Level 4.2
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> 0x2A

            // Level 5
            MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> 0x32

            // Level 5.1
            MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> 0x33

            // Level 5.2
            MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> 0x34

            // Level 6.0 (Android 11+)
            MediaCodecInfo.CodecProfileLevel.AVCLevel6 -> 0x3C

            // Level 6.1 (Android 11+)
            MediaCodecInfo.CodecProfileLevel.AVCLevel61 -> 0x3D

            // Level 6.2 (Android 11+)
            MediaCodecInfo.CodecProfileLevel.AVCLevel62 -> 0x3E

            else -> {
                Log.w(TAG, "Unknown level: $level, using Level 4.0 (0x28)")
                0x28
            }
        }
    }

    fun removeNALStartCode(data: ByteArray): ByteArray {
        return when {
            // Remove 4-byte start code
            data.size >= 4 &&
                    data[0] == 0x00.toByte() &&
                    data[1] == 0x00.toByte() &&
                    data[2] == 0x00.toByte() &&
                    data[3] == 0x01.toByte() -> {
                data.copyOfRange(4, data.size)
            }

            // Remove 3-byte start code
            data.size >= 3 &&
                    data[0] == 0x00.toByte() &&
                    data[1] == 0x00.toByte() &&
                    data[2] == 0x01.toByte() -> {
                data.copyOfRange(3, data.size)
            }

            // No start code, return as-is
            else -> data
        }
    }
}