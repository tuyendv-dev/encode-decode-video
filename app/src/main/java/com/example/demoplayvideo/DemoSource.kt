package com.example.demoplayvideo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DemoSource(
    private val holder: SurfaceHolder
) {
    private val tag: String = "NativeMediaSource"
    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioPlayer: AudioTrack? = null

    private var webSocket: WebSocket? = null

    fun startWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder()
//            .url("wss://streaming.ermis.network/stream-gate/software/Ermis-streaming/1bd1ce31-2542-4a9f-9ed3-54d213bcace1")
            .url("wss://streaming.ermis.network/stream-gate/software/Ermis-streaming/a5d7a087-4c87-429c-9983-39189ef94829")
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    Log.i(tag, "onMessage  text : " + json)
                    if (json.getString("type") == "DecoderConfigs") {
                        setupDecoders(json)
                    }
                } catch (e: JSONException) {
                    Log.e(tag, "websocket error : " + e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.i(tag, "onMessage: bytes size=${bytes.size} bytes=$bytes", )
                val data = bytes.toByteArray()
                val buffer = ByteBuffer.wrap(data)
                val timestamp = buffer.getInt()
                val frameType = buffer.get()
                val frameData = ByteArray(data.size - 5)
                buffer.get(frameData)
                if (frameType.toInt() == 2) {
                    decodeAudio(frameData, 0, frameType.toInt() == 2)
                } else {
                    val annexBFrame: ByteArray = convertAvcCToAnnexB(frameData, 4)
                    decodeVideo(annexBFrame, timestamp, frameType.toInt() == 0)
                }
            }
        })
    }

    fun stopWebSocket() {
        try {
            webSocket?.cancel()
            videoDecoder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                release()
            }
            audioDecoder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                release()
            }
            audioPlayer?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping decoders: ${e.message}")
        }
    }

    private fun setupDecoders(config: JSONObject) {
        try {
            //setup Video encoder
            val videoConfig = config.getJSONObject("videoConfig")
            val videoCodec = "video/avc"
            val videoFormat = MediaFormat.createVideoFormat(
                videoCodec,
                videoConfig.getInt("codedWidth"),
                videoConfig.getInt("codedHeight")
            )

            val videoDescription = Base64.decode(
                videoConfig.getString("description"),
                Base64.DEFAULT
            )

            videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(videoDescription))
            videoFormat.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            )
            videoFormat.setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)

            videoDecoder = MediaCodec.createDecoderByType(videoCodec)
            if (holder?.surface == null || !holder!!.surface!!.isValid) {
                Log.e(tag, "Surface is not ready yet!")
                return
            }
            Log.d(tag, "VideoDecoder state before configure: $videoDecoder")
            videoDecoder!!.configure(videoFormat, holder?.surface, null, 0)
            videoDecoder!!.start()


            // Setup Audio Decoder
            val audioConfig = config.getJSONObject("audioConfig")
            val audioCodec = "audio/mp4a-latm"
            val audioFormat = MediaFormat.createAudioFormat(
                audioCodec,
                audioConfig.getInt("sampleRate"),
                audioConfig.getInt("numberOfChannels")
            )

            val audioDescription = Base64.decode(
                audioConfig.getString("description"),
                Base64.DEFAULT
            )
            audioFormat.setByteBuffer("csd-0", ByteBuffer.wrap(audioDescription))
            Log.i(tag, "format audioconfig: " + audioConfig)
            audioDecoder = MediaCodec.createDecoderByType(audioCodec)
            audioDecoder!!.configure(audioFormat, null, null, 0)
            audioDecoder!!.start()
            // dung audioTrack de play audio data sau khi decode xong
            setupAudioTrack(audioConfig)
        } catch (e: JSONException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun setupAudioTrack(config: JSONObject) {
        var sampleRate = 48000
        try {
            sampleRate = config.getInt("sampleRate")
        } catch (e: Exception) {
        }
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setSampleRate(sampleRate)
            .build()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioPlayer = AudioTrack(
            attributes,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioPlayer!!.play()
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

    private fun decodeAudio(buffer: ByteArray, timestamp: Int, isKey: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()

        val timeoutUs: Long = 0
        val inIndex = audioDecoder!!.dequeueInputBuffer(timeoutUs)
        if (inIndex >= 0) {
            val inputBuffer = audioDecoder!!.getInputBuffer(inIndex)

            inputBuffer!!.clear()
            inputBuffer.put(buffer)
            val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

            audioDecoder!!.queueInputBuffer(
                inIndex, 0, buffer.size,
                timestamp.toLong(), flags
            )
        }


        var outIndex = audioDecoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)


        while (outIndex >= 0) {
            val outputBuffer = audioDecoder!!.getOutputBuffer(outIndex)
            val chunk = ByteArray(bufferInfo.size)
            outputBuffer!!.get(chunk)
            outputBuffer.clear()

            audioPlayer!!.write(chunk, 0, chunk.size)
            audioDecoder!!.releaseOutputBuffer(outIndex, false)
            outIndex = audioDecoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
        }
    }

    private fun decodeVideo(buffer: ByteArray, timestamp: Int, isKey: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs: Long = 0
        val inIndex = videoDecoder!!.dequeueInputBuffer(timeoutUs)
        if (inIndex >= 0) {
            val inputBuffer = videoDecoder!!.getInputBuffer(inIndex)
            inputBuffer!!.clear()
            inputBuffer.put(buffer)
            val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

            videoDecoder!!.queueInputBuffer(
                inIndex, 0, buffer.size,
                timestamp.toLong(), flags
            )
        }

        //get OutputIndex of frame after decoded
        var outIndex = videoDecoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)

        while (outIndex >= 0) {
            videoDecoder!!.releaseOutputBuffer(outIndex, true)
            outIndex = videoDecoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
        }
    }
}