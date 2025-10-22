package com.example.demoplayvideo

import android.util.Log
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaSourceModule(
    private val holder: SurfaceHolder,
    private val scope: CoroutineScope,
) {
    private val tag: String = "NativeMediaSource"
    private lateinit var webSocket: WebSocket
    private lateinit var mediaDecoderManager: MediaDecoderManager

    fun startWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder()
//            .url("wss://streaming.ermis.network/stream-gate/software/Ermis-streaming/1bd1ce31-2542-4a9f-9ed3-54d213bcace1")
            .url("wss://streaming.ermis.network/stream-gate/software/Ermis-streaming/a5d7a087-4c87-429c-9983-39189ef94829")
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.e(tag, "onMessage: text= $text")
                val config = getDecoderConfigs(text)
                if (config == null) {
                    Log.e(tag, "onMessage: Invalid DecoderConfigs")
                    return
                } else {
                    mediaDecoderManager = MediaDecoderManager(
                        decoderConfigs = config,
                        videoSurface = holder.surface,
                        scope = scope,
                    )
                    mediaDecoderManager.initialize()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.i(tag, "onMessage: bytes size=${bytes.size} bytes=$bytes")
                val data = bytes.toByteArray()
                val buffer = ByteBuffer.wrap(data)
                val timestamp = buffer.getInt()
                val frameType = buffer.get()
                val frameData = ByteArray(data.size - 5)
                buffer.get(frameData)
                if (frameType.toInt() == 2) {
                    Log.e(tag, ">>>> decodeAudio: frameData=$frameData", )
                    mediaDecoderManager.decodeAudio(frameData, 0)
                } else {
                    val annexBFrame: ByteArray = convertAvcCToAnnexB(frameData, 4)
                    mediaDecoderManager.decodeVideo(annexBFrame, 0)
                }
            }
        })
    }


    private fun getDecoderConfigs(text: String): DecoderConfigs? {
        try {
            val json = JSONObject(text)
            if (json.getString("type") == "DecoderConfigs") {
                val videoConfigJSON = json.getJSONObject("videoConfig")
                val videoConfig = VideoConfig(
                    codec = videoConfigJSON.getString("codec"),
                    codedWidth = videoConfigJSON.getInt("codedWidth"),
                    codedHeight = videoConfigJSON.getInt("codedHeight"),
                    frameRate = videoConfigJSON.getInt("frameRate"),
                    description = videoConfigJSON.getString("description"),
                )
                val audioConfigJSON = json.getJSONObject("audioConfig")
                val audioConfig = AudioConfig(
                    sampleRate = audioConfigJSON.getInt("sampleRate"),
                    numberOfChannels = audioConfigJSON.getInt("numberOfChannels"),
                    codec = audioConfigJSON.getString("codec"),
                    description = audioConfigJSON.getString("description"),
                )
                return DecoderConfigs(
                    type = json.getString("type"),
                    videoConfig = videoConfig,
                    audioConfig = audioConfig,
                )
            } else {
                return null
            }
        } catch (e: Exception) {
            Log.e(tag, "getDecoderConfigs error : " + e)
            return null
        }
    }

    fun stopWebSocket() {
        webSocket.cancel()
        mediaDecoderManager.release()
    }

    fun convertAvcCToAnnexB(avcc: ByteArray, nalLengthSize: Int): ByteArray {
        val input = ByteBuffer.wrap(avcc).order(ByteOrder.BIG_ENDIAN)
        val output = ByteArrayOutputStream()

        while (input.remaining() > nalLengthSize) {
            var naluLength = 0
            for (i in 0..<nalLengthSize) {
                naluLength = (naluLength shl 8) or (input.get().toInt() and 0xFF)
            }
            if (naluLength <= 0 || naluLength > input.remaining()) {
                break
            }
            output.write(byteArrayOf(0, 0, 0, 1), 0, 4)
            val nalu = ByteArray(naluLength)
            input.get(nalu)
            output.write(nalu, 0, naluLength)
        }

        return output.toByteArray()
    }
}
