package com.example.demoplayvideo.decoder

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.demoplayvideo.decoder.MediaSourceModule
import com.example.demoplayvideo.R

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var mediaSourceModule: MediaSourceModule

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
        val holder = surfaceView.holder
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.e("MainActivity", "holder.addCallback Surface created!")
                mediaSourceModule = MediaSourceModule(holder, lifecycleScope)
                mediaSourceModule.startWebSocket()

//                demoSource = DemoSource(holder)
//                demoSource.startWebSocket()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.e("MainActivity", "holder.addCallback Surface changed: $width x $height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.e("MainActivity", "holder.addCallback Surface destroyed!")
            }
        })
    }

    override fun onDestroy() {
        mediaSourceModule.stopWebSocket()
//        demoSource.stopWebSocket()
        super.onDestroy()
    }
}