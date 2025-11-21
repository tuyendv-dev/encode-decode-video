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
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.demoplayvideo.config.VideoAspectRatioFixer
import com.example.demoplayvideo.config.VideoCodecDetector
import com.example.demoplayvideo.decoder.AudioDecoderConfig
import com.example.demoplayvideo.decoder.DecoderConfigs
import com.example.demoplayvideo.decoder.MediaDecoderManager
import com.example.demoplayvideo.decoder.VideoDecoderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordJob: Job? = null
    private lateinit var cameraManager: CameraManager
    private var videoOrientation: Int = 0

    private val isServer = 2
    private val andressServer = "eK0PKL5ZGgsewxyq5PLtoN+NHhyEuV71TPVXIYaLDWcBJmh0dHBzOi8vdGVzdC1pcm9oLmVybWlzLm5ldHdvcmsuOjg0NDMvAgAAwN6o/EQeDOEALMMsww=="
    private var canSend = false
    private val endpoint = if (isServer != 1) ErmisCallEndpoint(relayUrls = listOf("https://test-iroh.ermis.network.:8443"), secretKey = null) else ErmisCallEndpoint(relayUrls = listOf("https://test-iroh.ermis.network.:8443"), secretKey = null)
    val monitorSend = SendDataMonitor()
    val monitorReceiver = SendDataMonitor()

    private var localDecoderConfigs: DecoderConfigs? = null
    private var remoteDecoderConfigs: DecoderConfigs? = null

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
        btnPhat.setOnClickListener {
            sendConfigToServer(localDecoderConfigs!!)
            canSend = true
        }
        btnXem.setOnClickListener {
             val codecDetector = VideoCodecDetector()
            val h265Encoders = codecDetector.getEncodersByMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            if (h265Encoders.isNotEmpty()) {
                Log.d("MainActivity", "H.265 supported!")
                h265Encoders.forEach { h265Encoder ->
                    Log.d("MainActivity", "Encoder: ${h265Encoder}")
                }
            }
        }
        remoteView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                remoteViewWidth = remoteView.width
                remoteViewHeight = remoteView.height
                setupLiveStream()
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

        monitorSend.startMonitoring(lifecycleScope, "Send")
        monitorReceiver.startMonitoring(lifecycleScope, "Receiver")
        lifecycleScope.launch {
            if (isServer == 1) {
                serverMode()
            } else {
                clientMode(andressServer)
            }
        }
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

        // 3. Khởi tạo stream manager
        streamManager = LiveStreamManager(
            videoConfig = videoConfig,
            audioConfig = audioConfig,
            scope = lifecycleScope,
            onDataReady = { data, frameType, timestamp ->
                if (canSend) {
                    sendFrameToServer(data, frameType, timestamp)
                }
//                decodeFrame(data, isVideo, timestamp)
            },
            onDecoderConfig = { configs ->
                // Gửi decoder configs nếu cần
                Log.d(TAG, "onDecoderConfig Decoder configs ready: $configs")
//                setupDecoder(decoderConfigs)
//                setupDecoder(configs)
                localDecoderConfigs = configs
                if (isServer != 1) {
                    sendConfigToServer(localDecoderConfigs!!)
                    canSend = true
                }
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
        remoteDecoderConfigs = decoderConfigs
        mediaDecoderManager?.release()
        mediaDecoderManager = MediaDecoderManager(
            decoderConfigs = decoderConfigs,
            videoSurface = remoteView.holder.surface,
            scope = lifecycleScope,
        )
        mediaDecoderManager!!.initialize()
        setupLayoutRemoteView()
    }

    private fun setupLayoutRemoteView() {
        remoteDecoderConfigs ?: return
        remoteView.post {
            VideoAspectRatioFixer.applySizeToSurfaceView(
                remoteView,
                remoteViewWidth,
                remoteViewHeight,
                remoteDecoderConfigs!!.videoConfig!!.codedWidth,
                remoteDecoderConfigs!!.videoConfig!!.codedHeight,
                remoteDecoderConfigs!!.videoConfig!!.orientation
            )
        }
//        remoteView.scaleX = -1f // Mirror the remote view
    }

    private fun sendConfigToServer(config: DecoderConfigs) {
        Log.e(TAG, "sendConfigToServer: config=$config", )
        val header = ByteBuffer.allocate(1).apply {
            put(0.toByte())
        }.array()
        val packet = header + buildConfigJSON(config).toByteArray(Charsets.UTF_8)
        val data = ByteString.of(*packet).toByteArray()
        sendData(data)
    }

    private fun sendFrameToServer(data: ByteArray, frameType: VideoEncoder.FrameType, timestamp: Long) {
        val header = ByteBuffer.allocate(5).apply {
            put(frameType.value.toByte())
            order(ByteOrder.BIG_ENDIAN)
            putInt((timestamp and 0xFFFFFFFF).toInt()) // 4 bytes: payload size
        }.array()
        val packet = header + data
        val bytes = ByteString.of(*packet)

        // Gửi bytes qua WebSocket
//        webSocket.send(bytes)
        sendData(bytes.toByteArray(), frameType != VideoEncoder.FrameType.AUDIO_FRAME)
    }

    private fun decodeFrame(data: ByteArray, isVideo: Boolean, timestamp: Long) {
        if (mediaDecoderManager == null) {
            Log.e(TAG, "decodeFrame: mediaDecoderManager not initialized")
            return
        }
        if (isVideo) {
            val annexBFrame: ByteArray = convertAvccOrHvccToAnnexB(data, 4)
            mediaDecoderManager!!.decodeVideo(annexBFrame, timestamp)
        } else {
            mediaDecoderManager!!.decodeAudio(data, timestamp)
        }
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
        val cameraId = getFrontCameraId(this) ?: cameraManager.cameraIdList[0] // Back camera

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

    private fun getFrontCameraId(context: Context): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return cameraId // <-- Trả về ID của camera trước
            }
        }
        return null // Không có camera trước
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
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
//        if (NoiseSuppressor.isAvailable()) {
//            NoiseSuppressor.create(audioRecord!!.audioSessionId)
//        }
//        if (AcousticEchoCanceler.isAvailable()) {
//            AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
//        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioRecord!!.audioSessionId)
        }

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

        Log.d(TAG, "Cleanup completed")
    }

    private fun getDecoderConfigs(text: String): DecoderConfigs? {
        try {
            val json = JSONObject(text)
            if (json.getString("type") == "DecoderConfigs") {
                val videoConfigJSON = json.getJSONObject("videoConfig")
                val orientation = when (videoConfigJSON.getInt("orientation")) {
                    0 -> {
                        0
                    }
                    1 -> {
                        90
                    }
                    2 -> {
                        180
                    }
                    3 -> {
                        270
                    }
                    else -> 0
                }
                val videoConfig = VideoDecoderConfig(
                    codec = videoConfigJSON.getString("codec"),
                    codedWidth = videoConfigJSON.getInt("codedWidth"),
                    codedHeight = videoConfigJSON.getInt("codedHeight"),
                    frameRate = videoConfigJSON.getInt("frameRate"),
                    description = videoConfigJSON.getString("description"),
                    orientation = orientation,
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
        val configString =  JSONObject().apply {
            put("type", configs.type)
            val orientation = when (videoOrientation) {
                0 -> {
                    0
                }
                90 -> {
                    1
                }
                180 -> {
                    2
                }
                270 -> {
                    3
                }
                else -> 0
            }
            configs.videoConfig?.let { video ->
                put("videoConfig", JSONObject().apply {
                    put("codec", video.codec)
                    put("codedWidth", video.codedWidth)
                    put("codedHeight", video.codedHeight)
                    put("frameRate", video.frameRate)
                    put("description", video.description)
                    put("orientation", orientation)
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
        Log.e(TAG, "buildConfigJSON= $configString", )
        return configString
    }

    private fun decoderByteArray(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        val dataType = buffer.get()
        when (dataType.toInt()) {
            VideoEncoder.FrameType.CONFIG_DECODER.value -> {
                val frameData = ByteArray(data.size - 1)
                buffer.get(frameData)
                val config = getDecoderConfigs(String(frameData))
                Log.e(TAG, "decoderByteArray VideoEncoder.FrameType.CONFIG_DECODER config=${config} ", )
                if (config == null) {
                    Log.e(TAG, "decoderByteArray: Invalid DecoderConfigs")
                    return
                } else {
                    setupDecoder(config)
                }
            }
            VideoEncoder.FrameType.AUDIO_FRAME.value -> {
                val timestamp = buffer.getInt()
                val frameData = ByteArray(data.size - 5)
                buffer.get(frameData)
                if (mediaDecoderManager == null) {
                    Log.e(TAG, "onMessage: mediaDecoderManager not initialized")
                    return
                }
                mediaDecoderManager!!.decodeAudio(frameData, 0)
            }
            VideoEncoder.FrameType.VIDEO_KEY_FRAME.value,
            VideoEncoder.FrameType.VIDEO_DELTA_FRAME.value-> {
                val timestamp = buffer.getInt()
                val frameData = ByteArray(data.size - 5)
                buffer.get(frameData)
                if (mediaDecoderManager == null) {
                    Log.e(TAG, "onMessage: mediaDecoderManager not initialized")
                    return
                }
                val annexBFrame: ByteArray = convertAvccOrHvccToAnnexB(frameData, 4)
                mediaDecoderManager!!.decodeVideo(annexBFrame, 0)
                lifecycleScope.launch {
                    monitorReceiver.sendData()
                }
            }
            4 -> {
                // orientation preview remote
                val frameData = ByteArray(data.size - 1)
                buffer.get(frameData)
                val orientation = when (frameData[0].toInt()) {
                    0 -> {
                        0
                    }
                    1 -> {
                        90
                    }
                    2 -> {
                        180
                    }
                    3 -> {
                        270
                    }
                    else -> 0
                }
                Log.e(TAG, "decoderByteArray: orientation=${orientation}", )
                remoteDecoderConfigs = remoteDecoderConfigs?.copy(videoConfig = remoteDecoderConfigs?.videoConfig?.copy(orientation = orientation))
                setupLayoutRemoteView()
            }
            else -> Log.e(TAG, "decoderByteArray: Unknown Frame Type=${dataType}")
        }
    }

    suspend fun serverMode() = withContext(Dispatchers.IO) {
        // Get local address to share with clients
        val localAddr = endpoint.getLocalEndpointAddr()
        Log.e(TAG, "Server listening at: $localAddr")
        // Share this address with clients...

        // Accept incoming connection (blocking)
        Log.d(TAG, "Waiting for connection...")
        endpoint.acceptConnection()
        Log.d(TAG, "✓ Client connected")

        // Accept bidirectional stream
        endpoint.acceptBidiStream()
        Log.d(TAG, "✓ Server accept Bidistream")
        if (isServer == 1) {
            sendConfigToServer(localDecoderConfigs!!)
            canSend = true
        }
        // Communication loop
        while (endpoint.isConnected()) {
            try {
                val data = endpoint.recv()
                decoderByteArray(data)
                Log.d(TAG, "Received: size=${data.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Communication error", e)
                break
            }
        }
    }

    suspend fun clientMode(serverAddr: String) = withContext(Dispatchers.IO) {
        // Connect to server
        Log.d(TAG, "Connecting to server...")
        endpoint.connect(serverAddr)
        Log.d(TAG, "✓ Connected")

        // Open stream
        endpoint.openBidiStream()
        Log.d(TAG, "✓ openBidiStream  isConneacted=${endpoint.isConnected()}")
//        setupDecoder(decoderConfigs)
        // Communication loop
        while (endpoint.isConnected()) {
            try {
                val data = endpoint.recv()
                Log.d(TAG, "Received: size=${data.size}")
                decoderByteArray(data)
            } catch (e: Exception) {
                Log.e(TAG, "Communication error", e)
                break
            }
        }

    }

    private fun sendData(data: ByteArray, isVideo: Boolean = false) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    endpoint.send(data)
                    if (isVideo) {
                        monitorSend.sendData()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Send error", e)
                }
            }
        }
    }

    private fun xemVideo() {
        setupDecoder(localDecoderConfigs!!)
        var recvCount = 0
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Communication loop
                while (endpoint.isConnected()) {
                    try {
                        val data = endpoint.recv()
                        Log.d(TAG, "Received: size=${data.size}")
                        recvCount ++
                        if (recvCount > 100) {
                            decoderByteArray(data)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Communication error", e)
                        break
                    }
                }
            }
        }
    }
}