package com.example.tvbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var shareButton: Button
    private lateinit var panicButton: Button
    private lateinit var ipText: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val SCREEN_CAPTURE_REQUEST_CODE = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shareButton = findViewById(R.id.shareButton)
        panicButton = findViewById(R.id.panicButton)
        ipText = findViewById(R.id.ipText)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Mostrar la IP al abrir la app
        val ip = getLocalIpAddress()
        ipText.text = if (ip != null) "Dirección: http://$ip:8080" else "Conéctate al Wi-Fi"

        shareButton.setOnClickListener {
            if (ip != null) {
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
            } else {
                Toast.makeText(this, "No hay conexión Wi-Fi", Toast.LENGTH_SHORT).show()
            }
        }

        panicButton.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            finishAndRemoveTask()
            exitProcess(0)
        }
    }

    private fun getLocalIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return if (ipAddress == 0) null else String.format(
            Locale.getDefault(), "%d.%d.%d.%d",
            (ipAddress and 0xff), (ipAddress shr 8 and 0xff),
            (ipAddress shr 16 and 0xff), (ipAddress shr 24 and 0xff)
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Transmitiendo...", Toast.LENGTH_SHORT).show()
        }
    }
}
