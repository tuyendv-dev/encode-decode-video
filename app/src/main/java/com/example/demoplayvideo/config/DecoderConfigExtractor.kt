package com.example.demoplayvideo.config

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.demoplayvideo.decoder.AudioDecoderConfig
import com.example.demoplayvideo.decoder.DecoderConfigs
import com.example.demoplayvideo.decoder.VideoDecoderConfig
import org.json.JSONObject
import java.nio.ByteBuffer

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
    fun extractVideoConfig(codec: MediaCodec, format: MediaFormat): VideoDecoderConfig? {
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
        val sps = ByteArray(spsBuffer.remaining())
        spsBuffer.get(sps)
        spsBuffer.rewind()

        // Extract PPS
        val pps = ByteArray(ppsBuffer.remaining())
        ppsBuffer.get(pps)
        ppsBuffer.rewind()

        Log.d(TAG, "SPS size: ${sps.size}, PPS size: ${pps.size}")

        // Parse profile, level từ SPS
        // SPS format: [NAL header][profile][constraint][level]...
        val profile = if (sps.size > 1) sps[1] else 0x64.toByte() // High profile default
        val compatibility = if (sps.size > 2) sps[2] else 0x00.toByte()
        val level = if (sps.size > 3) sps[3] else 0x28.toByte() // Level 4.0

        Log.d(TAG, "H.264 Profile: 0x${profile.toString(16)}, Level: 0x${level.toString(16)}")

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
        avcC[offset++] = 0xE1.toByte() // 3 bits reserved (111) + numOfSequenceParameterSets = 1 (00001)
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
    private fun generateCodecString(mimeType: String, format: MediaFormat, csd: ByteArray): String {
        return when {
            mimeType.contains("avc") -> {
                // avc1.PPCCLL
                val profile = if (csd.size > 1) csd[1].toInt() and 0xFF else 0x64
                val constraint = if (csd.size > 2) csd[2].toInt() and 0xFF else 0x00
                val level = if (csd.size > 3) csd[3].toInt() and 0xFF else 0x28

                "avc1.%02x%02x%02x".format(profile, constraint, level)
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
    fun extractAudioConfig(codec: MediaCodec, format: MediaFormat): AudioDecoderConfig? {
        try {
            val outputFormat = codec.outputFormat

            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mimeType = outputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            // Lấy CSD
            val csd = extractCSD(outputFormat, mimeType)
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
    private fun extractCSD(format: MediaFormat, mimeType: String): ByteArray? {
        return when {
            mimeType.contains("avc") -> {
                // H.264: Kết hợp csd-0 (SPS) và csd-1 (PPS)
                val csd0 = format.getByteBuffer("csd-0")
                val csd1 = format.getByteBuffer("csd-1")

                if (csd0 != null && csd1 != null) {
                    combineH264CSD(csd0, csd1)
                } else null
            }

            mimeType.contains("hevc") -> {
                // H.265: Lấy csd-0 (VPS + SPS + PPS)
                format.getByteBuffer("csd-0")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
            }

            mimeType.contains("aac") || mimeType.contains("mp4a-latm") -> {
                // AAC: Lấy csd-0 (AudioSpecificConfig)
                format.getByteBuffer("csd-0")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
            // Thêm support cho mp4a-latm
//            mimeType.contains("aac") || mimeType.contains("mp4a-latm") -> {
//                val csd0 = format.getByteBuffer("csd-0")
//
//                if (csd0 != null) {
//                    ByteArray(csd0.remaining()).also { csd0.get(it) }
//                } else {
//                    // Fallback: Generate AudioSpecificConfig
//                    generateAudioSpecificConfig(format)
//                }
//            }

            else -> null
        }
    }

    /**
     * Kết hợp SPS và PPS thành avcC format
     */
//    private fun combineH264CSD(sps: ByteBuffer, pps: ByteBuffer): ByteArray {
//        // avcC format:
//        // [1 byte version][1 byte profile][1 byte compatibility][1 byte level]
//        // [1 byte NAL length][1 byte numSPS][2 bytes SPS length][SPS data]
//        // [1 byte numPPS][2 bytes PPS length][PPS data]
//
//        val spsData = ByteArray(sps.remaining())
//        sps.get(spsData)
//        sps.rewind()
//
//        val ppsData = ByteArray(pps.remaining())
//        pps.get(ppsData)
//        pps.rewind()
//
//        // Parse SPS để lấy profile, level
//        val profile = if (spsData.size > 1) spsData[1] else 0x64 // High profile
//        val level = if (spsData.size > 3) spsData[3] else 0x1f // Level 3.1
//
//        val buffer = ByteBuffer.allocate(11 + spsData.size + ppsData.size)
//        buffer.put(0x01) // version
//        buffer.put(profile) // profile
//        buffer.put(0x00) // compatibility
//        buffer.put(level) // level
//        buffer.put(0xFF.toByte()) // NAL length size - 1 (4 bytes)
//        buffer.put(0xE1.toByte()) // numSPS = 1
//        buffer.putShort(spsData.size.toShort())
//        buffer.put(spsData)
//        buffer.put(0x01) // numPPS = 1
//        buffer.putShort(ppsData.size.toShort())
//        buffer.put(ppsData)
//
//        return buffer.array()
//    }

//    /**
//     * Generate codec string từ format
//     */
//    private fun generateCodecString(mimeType: String, format: MediaFormat, csd: ByteArray): String {
//        Log.e(TAG, "generateCodecString: format=$format", )
//        return when {
//            mimeType.contains("avc") -> {
//                // H.264: avc1.PPCCLL (Profile, Constraint, Level)
//                val profile = if (csd.size > 1) csd[1].toInt() and 0xFF else 0x64
//                val constraint = if (csd.size > 2) csd[2].toInt() and 0xFF else 0x00
//                val level = if (csd.size > 3) csd[3].toInt() and 0xFF else 0x1f
//
//                "avc1.%02x%02x%02x".format(profile, constraint, level)
//            }
//
//            mimeType.contains("hevc") -> {
//                // H.265: hev1 hoặc hvc1
//                // Format: hev1.PROFILE.FLAGS.LEVEL
//                try {
//                    val profile = format.getInteger(MediaFormat.KEY_PROFILE)
//                    val level = format.getInteger(MediaFormat.KEY_LEVEL)
//
//                    "hev1.1.%02x.L%d.b0".format(profile, level)
//                } catch (e: Exception) {
//                    "hev1.1.06.L93.b0" // Default
//                }
//            }
//
//            else -> "avc1.640028"
//        }
//    }



    /**
     * Tạo VideoDecoderConfig từ MediaFormat (của video/avc)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createVideoDecoderConfigFromFormat(format: MediaFormat): VideoDecoderConfig {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE, 30)

        // Lấy profile + level để tạo chuỗi codec (vd: avc1.640028)
        val profile = format.getInteger(MediaFormat.KEY_PROFILE, -1)
        val level = format.getInteger(MediaFormat.KEY_LEVEL, -1)
        val codecString = getAvcCodecString(mime, profile, level)

        // Lấy csd-0 và csd-1 (AVCDecoderConfigurationRecord)
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        val descriptionBytes = combineCsdBuffers(csd0, csd1)
//        val descriptionBase64 = Base64.getEncoder().encodeToString(descriptionBytes)

        return VideoDecoderConfig(
            codec = codecString,
            codedWidth = width,
            codedHeight = height,
            frameRate = frameRate,
            description = "descriptionBase64"
        )
    }

    /**
     * Gộp csd-0 và csd-1 thành 1 mảng bytes
     */
    fun combineCsdBuffers(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray {
        val out = ArrayList<Byte>()
        if (csd0 != null) {
            val bytes = ByteArray(csd0.remaining())
            csd0.get(bytes)
            out.addAll(bytes.toList())
        }
        if (csd1 != null) {
            val bytes = ByteArray(csd1.remaining())
            csd1.get(bytes)
            out.addAll(bytes.toList())
        }
        return out.toByteArray()
    }

    /**
     * Sinh chuỗi codec dạng RFC6381 cho AVC (H.264)
     */
    fun getAvcCodecString(mime: String, profile: Int, level: Int): String {
        if (mime != "video/avc") return mime

        val profileHex = when (profile) {
            1 -> "42"  // Baseline
            2 -> "4D"  // Main
            4 -> "58"  // Extended
            8, 65536 -> "64"  // High
            else -> "42"
        }

        val levelHex = when (level) {
            1 -> "0A"
            8 -> "14"
            16 -> "15"
            32 -> "1E"
            64 -> "1F"
            512 -> "28"
            1024 -> "29"
            else -> "1E"
        }

        return "avc1.${profileHex}00${levelHex}"
    }
}