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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var shareButton: Button
    private lateinit var panicButton: Button
    private lateinit var ipText: TextView
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val SCREEN_CAPTURE_REQUEST_CODE = 1000
    private var isDimmed = false
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Evitar que la pantalla se apague mientras estamos en la actividad (solo cuando no transmitimos)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainLayout = findViewById(R.id.mainLayout)
        shareButton = findViewById(R.id.shareButton)
        panicButton = findViewById(R.id.panicButton)
        ipText = findViewById(R.id.ipText)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        updateIpDisplay()

        shareButton.setOnClickListener {
            if (!isServiceRunning) {
                startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    SCREEN_CAPTURE_REQUEST_CODE
                )
            }
        }

        panicButton.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            finishAndRemoveTask()
        }

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
            ipText.text = "TRANSMITIENDO... (Toca para restaurar)"
            shareButton.visibility = android.view.View.INVISIBLE
            // Permitir que la pantalla se apague automáticamente (el servicio mantiene la CPU con wake lock)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            isServiceRunning = true
        } else {
            params.screenBrightness = -1f
            mainLayout.setBackgroundColor(Color.parseColor("#1A1A1A"))
            ipText.text = "http://${getLocalIpAddress()}:8080"
            shareButton.visibility = android.view.View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            isServiceRunning = false
        }
        window.attributes = params
    }

    private fun updateIpDisplay() {
        val ip = getLocalIpAddress()
        ipText.text = if (ip != null) "http://$ip:8080" else "Sin conexión de red"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            startForegroundService(serviceIntent)
            toggleDimMode(true)
        }
    }

    // Cuando la actividad vuelve a primer plano, actualizamos la IP por si cambió
    override fun onResume() {
        super.onResume()
        updateIpDisplay()
    }
}
