package com.example.demoplayvideo.config

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

data class VideoCodecInfo(
    val name: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val isSoftwareOnly: Boolean,
    val isVendor: Boolean,
    val supportedProfiles: List<String>,
    val supportedLevels: List<String>,
    val colorFormats: List<Int>,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    val supportedBitrates: String
)

class VideoCodecDetector {
    companion object {
        private const val TAG = "VideoCodecDetector"

        // Các MIME type video phổ biến
        val VIDEO_MIME_TYPES = listOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,      // H.264
            MediaFormat.MIMETYPE_VIDEO_HEVC,     // H.265
            MediaFormat.MIMETYPE_VIDEO_VP8,      // VP8
            MediaFormat.MIMETYPE_VIDEO_VP9,      // VP9
            MediaFormat.MIMETYPE_VIDEO_AV1,      // AV1 (Android 10+)
            MediaFormat.MIMETYPE_VIDEO_MPEG4,    // MPEG-4
            MediaFormat.MIMETYPE_VIDEO_H263      // H.263
        )
    }

    /**
     * Lấy tất cả video encoders hỗ trợ
     */
    fun getAllVideoEncoders(): List<VideoCodecInfo> {
        val encoders = mutableListOf<VideoCodecInfo>()

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        for (codecInfo in codecList.codecInfos) {
            // Chỉ lấy encoder
            if (!codecInfo.isEncoder) continue

            // Lọc các video codec
            val supportedTypes = codecInfo.supportedTypes
            for (type in supportedTypes) {
                if (type.startsWith("video/")) {
                    val info = extractCodecInfo(codecInfo, type)
                    encoders.add(info)
                }
            }
        }

        return encoders
    }

    /**
     * Lấy video encoders theo MIME type cụ thể
     */
    fun getEncodersByMimeType(mimeType: String): List<VideoCodecInfo> {
        val encoders = mutableListOf<VideoCodecInfo>()

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue

            if (codecInfo.supportedTypes.contains(mimeType)) {
                val info = extractCodecInfo(codecInfo, mimeType)
                encoders.add(info)
            }
        }

        return encoders
    }

    /**
     * Lấy hardware encoders (tối ưu hiệu suất)
     */
    fun getHardwareEncoders(): List<VideoCodecInfo> {
        return getAllVideoEncoders().filter { it.isHardwareAccelerated }
    }

    /**
     * Tìm encoder tốt nhất cho MIME type
     */
    fun findBestEncoder(mimeType: String): VideoCodecInfo? {
        val encoders = getEncodersByMimeType(mimeType)

        // Ưu tiên hardware encoder
        return encoders.firstOrNull { it.isHardwareAccelerated }
            ?: encoders.firstOrNull()
    }

    /**
     * Trích xuất thông tin chi tiết của codec
     */
    private fun extractCodecInfo(codecInfo: MediaCodecInfo, mimeType: String): VideoCodecInfo {
        val capabilities = codecInfo.getCapabilitiesForType(mimeType)
        val videoCapabilities = capabilities.videoCapabilities

        return VideoCodecInfo(
            name = codecInfo.name,
            mimeType = mimeType,
            isHardwareAccelerated = isHardwareAccelerated(codecInfo),
            isSoftwareOnly = isSoftwareOnly(codecInfo),
            isVendor = isVendor(codecInfo),
            supportedProfiles = getProfileNames(capabilities.profileLevels),
            supportedLevels = getLevelNames(capabilities.profileLevels),
            colorFormats = capabilities.colorFormats.toList(),
            maxWidth = videoCapabilities?.supportedWidths?.upper ?: 0,
            maxHeight = videoCapabilities?.supportedHeights?.upper ?: 0,
            maxFrameRate = videoCapabilities?.supportedFrameRates?.upper?.toInt() ?: 0,
            supportedBitrates = getBitrateRange(videoCapabilities)
        )
    }

    /**
     * Kiểm tra codec có phải hardware không
     */
    private fun isHardwareAccelerated(codecInfo: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            codecInfo.isHardwareAccelerated
        } else {
            !codecInfo.name.startsWith("OMX.google.")
        }
    }

    /**
     * Kiểm tra codec có phải software không
     */
    private fun isSoftwareOnly(codecInfo: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            codecInfo.isSoftwareOnly
        } else {
            codecInfo.name.startsWith("OMX.google.")
        }
    }

    /**
     * Kiểm tra codec có phải vendor không
     */
    private fun isVendor(codecInfo: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            codecInfo.isVendor
        } else {
            !codecInfo.name.startsWith("OMX.google.")
        }
    }

    /**
     * Lấy tên các profile
     */
    private fun getProfileNames(profileLevels: Array<MediaCodecInfo.CodecProfileLevel>): List<String> {
        return profileLevels.map { "Profile: ${it.profile}" }.distinct()
    }

    /**
     * Lấy tên các level
     */
    private fun getLevelNames(profileLevels: Array<MediaCodecInfo.CodecProfileLevel>): List<String> {
        return profileLevels.map { "Level: ${it.level}" }.distinct()
    }

    /**
     * Lấy bitrate range
     */
    private fun getBitrateRange(videoCapabilities: MediaCodecInfo.VideoCapabilities?): String {
        return videoCapabilities?.bitrateRange?.let {
            "${it.lower / 1000}kbps - ${it.upper / 1000}kbps"
        } ?: "Unknown"
    }

    /**
     * Log tất cả codecs
     */
    fun logAllEncoders() {
        val encoders = getAllVideoEncoders()

        Log.d(TAG, "========== VIDEO ENCODERS ==========")
        Log.d(TAG, "Total encoders found: ${encoders.size}")

        for ((index, encoder) in encoders.withIndex()) {
            Log.d(TAG, "\n--- Encoder #${index + 1} ---")
            Log.d(TAG, "Name: ${encoder.name}")
            Log.d(TAG, "MIME Type: ${encoder.mimeType}")
            Log.d(TAG, "Hardware: ${encoder.isHardwareAccelerated}")
            Log.d(TAG, "Software: ${encoder.isSoftwareOnly}")
            Log.d(TAG, "Vendor: ${encoder.isVendor}")
            Log.d(TAG, "Max Resolution: ${encoder.maxWidth}x${encoder.maxHeight}")
            Log.d(TAG, "Max FPS: ${encoder.maxFrameRate}")
            Log.d(TAG, "Bitrate: ${encoder.supportedBitrates}")
            Log.d(TAG, "Profiles: ${encoder.supportedProfiles.joinToString(", ")}")
        }
    }

    /**
     * Lấy thông tin dạng text
     */
    fun getAllEncodersAsText(): String {
        val encoders = getAllVideoEncoders()

        return buildString {
            appendLine("========== VIDEO ENCODERS ==========")
            appendLine("Total: ${encoders.size}\n")

            // Group theo MIME type
            val grouped = encoders.groupBy { it.mimeType }

            for ((mimeType, codecList) in grouped) {
                appendLine("📹 $mimeType")
                appendLine("─".repeat(50))

                for (codec in codecList) {
                    val type = when {
                        codec.isHardwareAccelerated -> "🚀 Hardware"
                        codec.isSoftwareOnly -> "💻 Software"
                        else -> "❓ Unknown"
                    }

                    appendLine("  $type: ${codec.name}")
                    appendLine("    Resolution: ${codec.maxWidth}x${codec.maxHeight}")
                    appendLine("    FPS: ${codec.maxFrameRate}")
                    appendLine("    Bitrate: ${codec.supportedBitrates}")
                }
                appendLine()
            }
        }
    }
}