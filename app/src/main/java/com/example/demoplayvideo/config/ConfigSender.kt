package com.example.demoplayvideo.config

import android.util.Log
import com.example.demoplayvideo.decoder.DecoderConfigs
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Config Sender - Gửi DecoderConfigs qua WebSocket
 */
class ConfigSender(
    private val webSocket: WebSocket
) {
    companion object {
        private const val TAG = "ConfigSender"

        // Packet types
        private const val PACKET_TYPE_CONFIG = 0xFF.toByte()
        private const val PACKET_TYPE_VIDEO = 0x00.toByte()
        private const val PACKET_TYPE_AUDIO = 0x01.toByte()
    }

    /**
     * Gửi DecoderConfigs dưới dạng JSON
     */
    fun sendDecoderConfigs(configs: DecoderConfigs) {
        try {
            val json = JSONObject().apply {
                put("type", configs.type)

                configs.videoConfig?.let { video ->
                    put("videoConfig", JSONObject().apply {
                        put("codec", video.codec)
                        put("codedWidth", video.codedWidth)
                        put("codedHeight", video.codedHeight)
                        put("frameRate", video.frameRate)
                        put("description", video.description)
                    })
                }

                configs.audioConfig?.let { audio ->
                    put("audioConfig", JSONObject().apply {
                        put("sampleRate", audio.sampleRate)
                        put("numberOfChannels", audio.numberOfChannels)
                        put("codec", audio.codec)
                        put("description", audio.description)
                    })
                }
            }

            // Gửi dưới dạng text message
            webSocket.send(json.toString())
            Log.d(TAG, "Sent decoder configs: ${json.toString()}")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending configs", e)
        }
    }

    /**
     * Gửi config dưới dạng binary packet
     * Format: [1 byte type=0xFF][4 bytes JSON length][JSON data]
     */
    fun sendDecoderConfigsBinary(configs: DecoderConfigs) {
        try {
            val json = buildConfigJSON(configs)
            val jsonBytes = json.toByteArray(Charsets.UTF_8)

            val packet = ByteBuffer.allocate(5 + jsonBytes.size).apply {
                put(PACKET_TYPE_CONFIG)
                putInt(jsonBytes.size)
                put(jsonBytes)
            }.array()

            webSocket.send(ByteString.of(*packet))
            Log.d(TAG, "Sent decoder configs (binary): ${jsonBytes.size} bytes")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending binary configs", e)
        }
    }

    private fun buildConfigJSON(configs: DecoderConfigs): String {
        return JSONObject().apply {
            put("type", configs.type)
            configs.videoConfig?.let { video ->
                put("videoConfig", JSONObject().apply {
                    put("codec", video.codec)
                    put("codedWidth", video.codedWidth)
                    put("codedHeight", video.codedHeight)
                    put("frameRate", video.frameRate)
                    put("description", video.description)
                })
            }
            configs.audioConfig?.let { audio ->
                put("audioConfig", JSONObject().apply {
                    put("sampleRate", audio.sampleRate)
                    put("numberOfChannels", audio.numberOfChannels)
                    put("codec", audio.codec)
                    put("description", audio.description)
                })
            }
        }.toString()
    }
}