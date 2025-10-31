package com.example.demoplayvideo.decoder

import android.util.Log
import kotlin.collections.copyOf

class OpusDecoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1
) {
    private var decoderHandle: Long = 0

    companion object {
        private const val TAG = "OpusDecoder"

        init {
            try {
                System.loadLibrary("opusdemo")
                Log.d(TAG, "✓ Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "✗ Failed to load native library", e)
                throw e
            }
        }
    }

    init {
        decoderHandle = nativeCreateDecoder(sampleRate, channels)
        if (decoderHandle == 0L) {
            throw kotlin.RuntimeException("Failed to create Opus decoder")
        }
        Log.d(TAG, "Decoder created: ${sampleRate}Hz, $channels channel(s)")
    }

    /**
     * Decode Opus frame to PCM
     * @param opusData Encoded Opus frame
     * @param frameSize Number of samples per channel (typically 960 for 20ms at 48kHz)
     * @return PCM samples as ShortArray
     */
    fun decode(opusData: ByteArray, frameSize: Int = 960): ShortArray {
        if (decoderHandle == 0L) {
            throw kotlin.IllegalStateException("Decoder has been released")
        }

        val pcmData = ShortArray(frameSize * channels)
        val samplesDecoded = nativeDecode(
            decoderHandle,
            opusData,
            opusData.size,
            pcmData,
            frameSize
        )

        if (samplesDecoded < 0) {
            throw kotlin.RuntimeException("Decode error code: $samplesDecoded")
        }

        return pcmData.copyOf(samplesDecoded * channels)
    }

    /**
     * Reset decoder state
     */
    fun reset() {
        if (decoderHandle != 0L) {
            nativeResetDecoder(decoderHandle)
            Log.d(TAG, "Decoder reset")
        }
    }

    /**
     * Release decoder resources
     */
    fun release() {
        if (decoderHandle != 0L) {
            nativeDestroyDecoder(decoderHandle)
            decoderHandle = 0
            Log.d(TAG, "Decoder released")
        }
    }

    // Native methods
    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(
        decoder: Long,
        opusData: ByteArray,
        opusLength: Int,
        pcmData: ShortArray,
        frameSize: Int
    ): Int
    private external fun nativeResetDecoder(decoder: Long)
    private external fun nativeDestroyDecoder(decoder: Long)

    protected fun finalize() {
        release()
    }
}