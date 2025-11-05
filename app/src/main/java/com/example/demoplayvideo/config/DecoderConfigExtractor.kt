package com.example.demoplayvideo.config

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import com.example.demoplayvideo.decoder.AudioDecoderConfig
import com.example.demoplayvideo.decoder.VideoDecoderConfig
import java.nio.ByteBuffer
import kotlin.collections.take

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
    fun extractVideoConfig(codec: MediaCodec): VideoDecoderConfig? {
        val outputFormat = codec.outputFormat
        return try {
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
            val codecString = generateCodecString(mimeType, outputFormat, csd)
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
                combineH264CSD(csd0, csd1)
            }

            mimeType.contains("hevc") -> {
                // H.265: csd-0 chứa VPS+SPS+PPS
                val csd0 = format.getByteBuffer("csd-0") ?: run {
                    Log.e(TAG, "No csd-0 for H.265")
                    return null
                }
                val csd = ByteArray(csd0.remaining()).also {
                    csd0.get(it)
                    csd0.rewind()
                }
                HevcCsdConverter.convertAnnexBToHvcCsd(csd)
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
        val sps = ByteArray(spsBuffer.remaining())
        spsBuffer.get(sps)
        spsBuffer.rewind()

        // Extract PPS
        val pps = ByteArray(ppsBuffer.remaining())
        ppsBuffer.get(pps)
        ppsBuffer.rewind()

        Log.d(TAG, "SPS size: ${sps.size}, PPS size: ${pps.size}")
        Log.d(TAG, "SPS hex: ${sps.take(10).joinToString(" ") { "%02x".format(it) }}")
        Log.d(TAG, "PPS hex: ${pps.take(10).joinToString(" ") { "%02x".format(it) }}")

        // ⚠️ QUAN TRỌNG: Check nếu SPS/PPS có NAL start code (00 00 00 01 hoặc 00 00 01)
        // Nếu có thì bỏ start code đi
        val cleanSPS = removeNALStartCode(sps)
        val cleanPPS = removeNALStartCode(pps)
//        val cleanSPS = sps
//        val cleanPPS = pps

        Log.d(TAG, "Clean SPS size: ${cleanSPS.size}, Clean PPS size: ${cleanPPS.size}")

        // Parse profile, level từ SPS (sau NAL header)
        // SPS NAL format: [NAL type byte][profile][constraint][level]...
        var profile = 0x64 // High profile default
        var compatibility = 0x00
        var level = 0x28 // Level 4.0 default

        if (cleanSPS.size >= 4) {
            // Skip NAL header byte (index 0), lấy profile/constraint/level
            profile = cleanSPS[1].toInt() and 0xFF
            compatibility = cleanSPS[2].toInt() and 0xFF
            level = cleanSPS[3].toInt() and 0xFF

            Log.d(TAG, "Parsed from SPS: profile=0x${profile.toString(16)}, compatibility=0x${compatibility.toString(16)}, level=0x${level.toString(16)}")
        } else {
            Log.w(TAG, "SPS too short (${cleanSPS.size} bytes), using defaults")
        }

        // Build avcC
        val avcC = ByteArray(11 + cleanSPS.size + cleanPPS.size)
        var offset = 0

        // Header
        avcC[offset++] = 0x01 // version
        avcC[offset++] = profile.toByte()
        avcC[offset++] = compatibility.toByte()
        avcC[offset++] = level.toByte()
        avcC[offset++] = 0xFF.toByte() // 6 bits reserved (111111) + lengthSizeMinusOne = 3 (11)

        // SPS
        avcC[offset++] = 0xE1.toByte() // 3 bits reserved (111) + numOfSequenceParameterSets = 1 (00001)
        avcC[offset++] = ((cleanSPS.size shr 8) and 0xFF).toByte() // SPS length high byte
        avcC[offset++] = (cleanSPS.size and 0xFF).toByte() // SPS length low byte
        System.arraycopy(cleanSPS, 0, avcC, offset, cleanSPS.size)
        offset += cleanSPS.size

        // PPS
        avcC[offset++] = 0x01 // numOfPictureParameterSets
        avcC[offset++] = ((cleanPPS.size shr 8) and 0xFF).toByte() // PPS length high byte
        avcC[offset++] = (cleanPPS.size and 0xFF).toByte() // PPS length low byte
        System.arraycopy(cleanPPS, 0, avcC, offset, cleanPPS.size)

        Log.d(TAG, "avcC created: ${avcC.size} bytes")
        Log.d(TAG, "avcC header: ${avcC.take(11).joinToString(" ") { "%02x".format(it) }}")

        return avcC
    }

    /**
     * Generate codec string
     */
    private fun generateCodecString(mimeType: String, format: MediaFormat, csd: ByteArray): String {
        return when {
            mimeType.contains("avc") -> {
                // avc1.PPCCLL
                // CSD format: [version][profile][compatibility][level][...]

                var profile = 0x64 // High profile default
                var constraint = 0x00
                var level = 0x28 // Level 4.0 default

                // Parse từ avcC header
                if (csd.size >= 4) {
                    // avcC format: [1:version][1:profile][1:compatibility][1:level]...
                    profile = csd[1].toInt() and 0xFF
                    constraint = csd[2].toInt() and 0xFF
                    level = csd[3].toInt() and 0xFF

                    Log.d(TAG, "H.264 codec string from CSD:")
                    Log.d(TAG, "  Profile: 0x${profile.toString(16).padStart(2, '0')} (${getH264ProfileName(profile)})")
                    Log.d(TAG, "  Constraint: 0x${constraint.toString(16).padStart(2, '0')}")
                    Log.d(TAG, "  Level: 0x${level.toString(16).padStart(2, '0')} (${getH264LevelName(level)})")
                } else {
                    Log.w(TAG, "CSD too short for H.264: ${csd.size} bytes, using defaults")
                }

                val codecString = "avc1.%02x%02x%02x".format(profile, constraint, level)
                Log.d(TAG, "Generated codec string: $codecString")

                codecString
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
     * Get H.264 profile name for debugging
     */
    private fun getH264ProfileName(profile: Int): String {
        return when (profile) {
            0x42 -> "Baseline"
            0x4D -> "Main"
            0x58 -> "Extended"
            0x64 -> "High"
            0x6E -> "High 10"
            0x7A -> "High 4:2:2"
            0xF4 -> "High 4:4:4"
            else -> "Unknown"
        }
    }

    /**
     * Get H.264 level name for debugging
     */
    private fun getH264LevelName(level: Int): String {
        return when (level) {
            0x0A -> "1.0"
            0x0B -> "1.1"
            0x0C -> "1.2"
            0x0D -> "1.3"
            0x14 -> "2.0"
            0x15 -> "2.1"
            0x16 -> "2.2"
            0x1E -> "3.0"
            0x1F -> "3.1"
            0x20 -> "3.2"
            0x28 -> "4.0"
            0x29 -> "4.1"
            0x2A -> "4.2"
            0x32 -> "5.0"
            0x33 -> "5.1"
            0x34 -> "5.2"
            else -> "Unknown (0x${level.toString(16)})"
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