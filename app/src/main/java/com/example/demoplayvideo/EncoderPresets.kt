package com.example.demoplayvideo

import android.media.MediaCodecInfo

/**
 * Các preset cấu hình phổ biến
 */
object EncoderPresets {

    // 720p 30fps - Chất lượng cao
    val PRESET_720P_HIGH = VideoEncoder.VideoEncoderConfig(
        width = 1280,
        height = 720,
        bitrate = 3_000_000, // 3 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
    )

    // 720p 30fps - Chất lượng trung bình
    val PRESET_720P_MEDIUM = VideoEncoder.VideoEncoderConfig(
        width = 1280,
        height = 720,
        bitrate = 2_000_000, // 2 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
    )

    // 480p 30fps - Chất lượng thấp (tiết kiệm băng thông)
    val PRESET_480P_LOW = VideoEncoder.VideoEncoderConfig(
        width = 854,
        height = 480,
        bitrate = 1_000_000, // 1 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.CBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel3
    )

    // 1080p 30fps - Full HD
    val PRESET_1080P = VideoEncoder.VideoEncoderConfig(
        width = 1920,
        height = 1080,
        bitrate = 5_000_000, // 5 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel4
    )

    // H.265 - Chất lượng cao, hiệu quả hơn
    val PRESET_720P_H265 = VideoEncoder.VideoEncoderConfig(
        width = 1280,
        height = 720,
        bitrate = 1_500_000, // 1.5 Mbps (thấp hơn H.264 nhưng chất lượng tương đương)
        frameRate = 30,
        codec = VideoEncoder.CodecType.H265,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4
    )

    // Audio presets
    val AUDIO_HIGH = AudioEncoder.AudioEncoderConfig(
        sampleRate = 48000,
        channelCount = 2,
        bitrate = 192_000, // 192 kbps
        codec = AudioEncoder.CodecType.AAC
    )

    val AUDIO_MEDIUM = AudioEncoder.AudioEncoderConfig(
        sampleRate = 44100,
        channelCount = 2,
        bitrate = 128_000, // 128 kbps
        codec = AudioEncoder.CodecType.AAC
    )

    val AUDIO_LOW = AudioEncoder.AudioEncoderConfig(
        sampleRate = 24000,
        channelCount = 1,
        bitrate = 64_000, // 64 kbps
        codec = AudioEncoder.CodecType.AAC
    )

    val AUDIO_OPUS = AudioEncoder.AudioEncoderConfig(
        sampleRate = 48000,
        channelCount = 1,
        bitrate = 64_000,
        codec = AudioEncoder.CodecType.OPUS
    )
}