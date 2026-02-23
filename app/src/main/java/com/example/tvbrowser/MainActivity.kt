package com.example.tvbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.util.Locale
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var shareButton: Button
    private lateinit var panicButton: Button
    private lateinit var ipText: TextView
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val SCREEN_CAPTURE_REQUEST_CODE = 1000
    private var isDimmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Mantiene la pantalla encendida lógicamente (aunque el brillo sea 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainLayout = findViewById(R.id.mainLayout) // Asegúrate que tu XML tenga este ID
        shareButton = findViewById(R.id.shareButton)
        panicButton = findViewById(R.id.panicButton)
        ipText = findViewById(R.id.ipText)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val ip = getLocalIpAddress()
        ipText.text = if (ip != null) "http://$ip:8080" else "Conecta Wi-Fi"

        shareButton.setOnClickListener {
            if (ip != null) {
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
            }
        }

        panicButton.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            finishAndRemoveTask()
            exitProcess(0)
        }

        // Tocar la pantalla restaura el brillo
        mainLayout.setOnClickListener {
            if (isDimmed) toggleDimMode(false)
        }
    }

    private fun toggleDimMode(activate: Boolean) {
        isDimmed = activate
        val params = window.attributes
        if (activate) {
            params.screenBrightness = 0.01f 
            mainLayout.setBackgroundColor(Color.BLACK)
            ipText.text = "TRANSMITIENDO... (Toca para ver)"
        } else {
            params.screenBrightness = -1f 
            mainLayout.setBackgroundColor(Color.parseColor("#1A1A1A"))
            ipText.text = "http://${getLocalIpAddress()}:8080"
        }
        window.attributes = params
    }

    private fun getLocalIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return if (ipAddress == 0) null else String.format(Locale.getDefault(), "%d.%d.%d.%d",
            (ipAddress and 0xff), (ipAddress shr 8 and 0xff),
            (ipAddress shr 16 and 0xff), (ipAddress shr 24 and 0xff))
    }

    override fun onBackPressed() {
        moveTaskToBack(true) // No cierra la app, la minimiza
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            startForegroundService(serviceIntent)
            toggleDimMode(true) // Activa ahorro al iniciar
        }
    }
}
