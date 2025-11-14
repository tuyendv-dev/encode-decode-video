package com.example.demoplayvideo.decoder

import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.demoplayvideo.ErmisCallEndpoint
import com.example.demoplayvideo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
//    private val endpoint = ErmisCallEndpoint(listOf("https://test-iroh.ermis.network.:8443"))

    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        surfaceView = findViewById(R.id.surfaceView)
        val btnSend: Button = findViewById(R.id.btnSend)
        btnSend.setOnClickListener {
            sendData()
        }
        lifecycleScope.launch {
//            serverMode()
            clientMode("hfHHSB5xNNOSjwmPWnEei/nccZxCTTYEKz3ORvqN8HQDCgYmaHR0cHM6Ly90ZXN0LWlyb2guZXJtaXMubmV0d29yay46ODQ0My8AAMDeqPxEHmThAK2erZ4=")
        }
    }

    private fun sendData() {

    }

    suspend fun serverMode() = withContext(Dispatchers.IO) {

    }

    suspend fun clientMode(serverAddr: String) = withContext(Dispatchers.IO) {

    }
}