package com.example.demoplayvideo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.demoplayvideo.config.VideoAspectRatioFixer
import com.example.demoplayvideo.decoder.AudioDecoderConfig
import com.example.demoplayvideo.decoder.DecoderConfigs
import com.example.demoplayvideo.decoder.MediaDecoderManager
import com.example.demoplayvideo.decoder.VideoDecoderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LivestreamActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var remoteView: SurfaceView
    private var remoteViewWidth: Int = 0
    private var remoteViewHeight: Int = 0
    private lateinit var streamManager: LiveStreamManager
    private var mediaDecoderManager: MediaDecoderManager? = null
    private lateinit var webSocket: WebSocket

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordJob: Job? = null
    private lateinit var cameraManager: CameraManager
    private var videoOrientation: Int = 0

    companion object {
        private const val TAG = "LiveStreamActivity"
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_livestream)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        textureView = findViewById<TextureView>(R.id.localView)
        remoteView = findViewById<SurfaceView>(R.id.remoteView)
        val btnPhat = findViewById<Button>(R.id.btnPhat)
        val btnXem = findViewById<Button>(R.id.btnXem)
        setupWebSocket()
        remoteView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                remoteViewWidth = remoteView.width
                remoteViewHeight = remoteView.height
//                setupLiveStream()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }
        })
    }

    private fun setupLiveStream() {
        // 1. Cấu hình encoder
        val videoConfig = EncoderPresets.PRESET_720P_H265
//        val videoConfig = EncoderPresets.PRESET_720P_HIGH
//        val videoConfig = EncoderPresets.PRESET_720P_MEDIUM
//        val videoConfig = EncoderPresets.PRESET_480P_LOW
//        val videoConfig = EncoderPresets.PRESET_1080P

        val audioConfig = EncoderPresets.AUDIO_OPUS
//        val audioConfig = EncoderPresets.AUDIO_HIGH
//        val audioConfig = EncoderPresets.AUDIO_MEDIUM


        // 2. Setup WebSocket để gửi data
//        setupWebSocket()
        var count = 0
        // 3. Khởi tạo stream manager
        streamManager = LiveStreamManager(
            videoConfig = videoConfig,
            audioConfig = audioConfig,
            scope = lifecycleScope,
            onDataReady = { data, isVideo, timestamp ->
                count++
                if (count > 20) {
                    sendToServer(data, isVideo, timestamp)
                }
            },
            onDecoderConfig = { configs ->
                // Gửi decoder configs nếu cần
                Log.d(TAG, "onDecoderConfig Decoder configs ready: $configs")
                webSocket.send(buildConfigJSON(configs))
//                setupDecoder(configs)
            }
        )
        streamManager.initialize()

        // 4. Setup camera
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
//        remoteView.rotation = 90f

        // 5. Setup audio recording
        setupAudioRecording()
    }

    private fun setupDecoder(decoderConfigs: DecoderConfigs) {
        mediaDecoderManager?.release()
        mediaDecoderManager = MediaDecoderManager(
            decoderConfigs = decoderConfigs,
            videoSurface = remoteView.holder.surface,
            scope = lifecycleScope,
        )
        mediaDecoderManager!!.initialize()
        remoteView.post {
            VideoAspectRatioFixer.applySizeToSurfaceView(
                remoteView,
                remoteViewWidth,
                remoteViewHeight,
                decoderConfigs.videoConfig!!.codedWidth,
                decoderConfigs.videoConfig.codedHeight,
                decoderConfigs.videoConfig.orientation
            )
        }

    }

    private fun sendToServer(data: ByteArray, isVideo: Boolean, timestamp: Long) {
        val header = ByteBuffer.allocate(5).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt((timestamp and 0xFFFFFFFF).toInt()) // 4 bytes: payload size
            put((if (isVideo) 0 else 2).toByte()) // 8 bytes: timestamp
        }.array()
        val packet = header + data
        val bytes = ByteString.of(*packet)

        // Gửi bytes qua WebSocket
        webSocket.send(bytes)

        //Decoder
//        val dataGet = bytes.toByteArray()
//        val buffer = ByteBuffer.wrap(dataGet)
//        val timestampGet = buffer.getInt()
//        val frameType = buffer.get()
//        val frameData = ByteArray(dataGet.size - 5)
//        buffer.get(frameData)
//
//        if (frameType.toInt() == 2) {
//            mediaDecoderManager?.decodeAudio(frameData, 0)
//        } else {
//            val annexBFrame: ByteArray = convertAvccOrHvccToAnnexB(frameData, 4)
//            Log.e(
//                TAG,
//                "getToServer: timestamp=$timestampGet type=${frameType.toInt()} frameData=${frameData.size} annexBFrame=${annexBFrame.size}",
//            )
//            mediaDecoderManager?.decodeVideo(annexBFrame, 0)
//        }
    }

    fun convertAvccOrHvccToAnnexB(avcc: ByteArray, nalLengthSize: Int): ByteArray {
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

    private fun openCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0] // Back camera

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val encoderSurface = streamManager.encoderSurface ?: return

        try {
            // Preview surface
            val surfaceTexture = textureView.surfaceTexture!!
            surfaceTexture.setDefaultBufferSize(1280, 720)
            val previewSurface = Surface(surfaceTexture)

            // Tạo capture session với 2 surfaces: preview + encoder
            camera.createCaptureSession(
                listOf(previewSurface, encoderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(session, previewSurface, encoderSurface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure capture session")
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    private fun startPreview(
        session: CameraCaptureSession,
        previewSurface: Surface,
        encoderSurface: Surface
    ) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)//cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        Log.d(TAG, "startPreview: sensorOrientation=${sensorOrientation}")
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        Log.d(TAG, "startPreview: rotation=${rotation}")
        val deviceRotation = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        Log.d(TAG, "startPreview: deviceRotation=${deviceRotation}")
        videoOrientation = (sensorOrientation - deviceRotation + 360) % 360
        Log.d(TAG, "startPreview: jpegOrientation=${videoOrientation}")
        try {
            val captureRequest =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.apply {
                    addTarget(previewSurface)
                    addTarget(encoderSurface)

                    // Cấu hình cho video recording
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
// ✅ FIX ORIENTATION
//                    set(CaptureRequest.JPEG_ORIENTATION, 270)
//                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
//                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }?.build()

            captureRequest?.let {
                session.setRepeatingRequest(it, null, null)
            }

            Log.d(TAG, "Preview started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview", e)
        }
    }

    private fun setupAudioRecording() {
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize * 2

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()

        // Job để đọc audio data
        audioRecordJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            var timestamp = System.nanoTime() / 1000 // microseconds

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // Encode audio
                    streamManager.encodeAudio(buffer.copyOf(read), timestamp)
                    timestamp += (read * 1_000_000L) / (sampleRate * 2 * 2) // 2 channels, 2 bytes per sample
                }
            }
        }

        Log.d(TAG, "Audio recording started")
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop audio
        audioRecordJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()

        // Stop camera
        captureSession?.close()
        cameraDevice?.close()

        // Release encoder
        streamManager.release()

        // Close WebSocket
        webSocket.close(1000, "Normal closure")

        Log.d(TAG, "Cleanup completed")
    }

    private fun getDecoderConfigs(text: String): DecoderConfigs? {
        try {
            val json = JSONObject(text)
            if (json.getString("type") == "DecoderConfigs") {
                val videoConfigJSON = json.getJSONObject("videoConfig")
                val videoConfig = VideoDecoderConfig(
                    codec = videoConfigJSON.getString("codec"),
                    codedWidth = videoConfigJSON.getInt("codedWidth"),
                    codedHeight = videoConfigJSON.getInt("codedHeight"),
                    frameRate = videoConfigJSON.getInt("frameRate"),
                    description = videoConfigJSON.getString("description"),
                    orientation = videoConfigJSON.getInt("orientation"),
                )
                val audioConfigJSON = json.getJSONObject("audioConfig")
                val audioConfig = AudioDecoderConfig(
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
            Log.e(TAG, "getDecoderConfigs error : " + e)
            return null
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
                    put("orientation", videoOrientation)
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

    private fun setupWebSocket() {
        val client = OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
//            .url("wss://streaming.ermis.network/stream-gate/software/Ermis-streaming/a5d7a087-4c87-429c-9983-39189ef94829")
//            .url("wss://streaming.ermis.network/stream-gate/software/Ermis-streaming/941b688c-cd0e-4e25-8975-e84e28c50029")
//            .url("wss://4044.bandia.vn/publish/12345678901")
            .url("wss://4044.bandia.vn/consume/12345678901")
//            .url("wss://streaming.ermis.network/stream-gate/browser/Ermis-streaming/43fa06de-0e8e-4955-9ec8-daef6796634d")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.e(TAG, "onMessage: text= $text")
                val config = getDecoderConfigs(text)
                if (config == null) {
                    Log.e(TAG, "onMessage: Invalid DecoderConfigs")
                    return
                } else {
                    setupDecoder(config)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (mediaDecoderManager == null) {
                    Log.e(TAG, "onMessage: mediaDecoderManager not initialized")
                    return
                }
                Log.i(TAG, "onMessage: bytes size=${bytes.size} bytes=$bytes")
                val data = bytes.toByteArray()
                val buffer = ByteBuffer.wrap(data)
                val timestamp = buffer.getInt()
                val frameType = buffer.get()
                val frameData = ByteArray(data.size - 5)
                buffer.get(frameData)
                if (frameType.toInt() == 2) {
                    mediaDecoderManager!!.decodeAudio(frameData, 0)
                } else {
                    val annexBFrame: ByteArray = convertAvccOrHvccToAnnexB(frameData, 4)
                    mediaDecoderManager!!.decodeVideo(annexBFrame, 0)
                }
            }
        })
    }
}