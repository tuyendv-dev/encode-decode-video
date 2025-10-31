package com.example.demoplayvideo.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.collections.withIndex
import kotlin.let

class OpusAudioPlayer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1
) {
    private var decoder: OpusDecoder? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var isPlaying = false

    companion object {
        private const val TAG = "OpusAudioPlayer"
        const val FRAME_SIZE = 960 // 20ms at 48kHz
    }

    fun initialize() {
        try {
            // Create decoder
            decoder = OpusDecoder(sampleRate, channels)

            // Create AudioTrack
            val channelConfig = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.d(TAG, "Player initialized: ${sampleRate}Hz, $channels channel(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize player", e)
            throw e
        }
    }

    /**
     * Play a list of Opus frames
     */
    fun playFrames(frames: List<ByteArray>, onComplete: (() -> Unit)? = null) {
        if (isPlaying) {
            stop()
        }

        isPlaying = true
        audioTrack?.play()

        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Playing ${frames.size} frames...")

                for ((index, frame) in frames.withIndex()) {
                    if (!isPlaying || !isActive) break

                    try {
                        val pcmData = decoder?.decode(frame, FRAME_SIZE)
                        pcmData?.let {
                            val written = audioTrack?.write(it, 0, it.size)
                            if (written != it.size) {
                                Log.w(TAG, "Frame $index: wrote $written/${it.size} samples")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decoding frame $index", e)
                    }
                }

                // Wait for playback to finish
                delay(100)

                Log.d(TAG, "Playback completed")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
            } finally {
                withContext(Dispatchers.Main) {
                    stop()
                }
            }
        }
    }

    /**
     * Play a single Opus frame
     */
    fun playFrame(opusFrame: ByteArray) {
        try {
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }

            val pcmData = decoder?.decode(opusFrame, FRAME_SIZE)
            pcmData?.let {
                audioTrack?.write(it, 0, it.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing frame", e)
        }
    }

    fun pause() {
        audioTrack?.pause()
        isPlaying = false
        Log.d(TAG, "Paused")
    }

    fun resume() {
        audioTrack?.play()
        isPlaying = true
        Log.d(TAG, "Resumed")
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.flush()
        Log.d(TAG, "Stopped")
    }

    fun release() {
        stop()
        audioTrack?.release()
        decoder?.release()
        audioTrack = null
        decoder = null
        Log.d(TAG, "Released")
    }

    fun isPlaying() = isPlaying
}