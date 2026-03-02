package com.example.tvbrowser

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var shareButton: Button
    private lateinit var panicButton: Button
    private lateinit var ipText: TextView
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val SCREEN_CAPTURE_REQUEST_CODE = 1000
    private var isDimmed = false
    private var isServiceRunning = false
    
    // Almacenamos el Intent de captura para poder reiniciarlo remotamente
    private var projectionData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainLayout = findViewById(R.id.mainLayout)
        shareButton = findViewById(R.id.shareButton)
        panicButton = findViewById(R.id.panicButton)
        ipText = findViewById(R.id.ipText)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        updateIpDisplay()

        // 1. Iniciar el receptor de comandos remotos (Puerto 9001)
        listenForRemoteCommands()

        shareButton.setOnClickListener {
            if (!isServiceRunning) {
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
            }
        }

        panicButton.setOnClickListener {
            stopStreamingProcess()
            finishAndRemoveTask()
        }

        mainLayout.setOnClickListener {
            if (isDimmed) toggleDimMode(false)
        }
    }

    // Receptor para que la Tablet controle al Xiaomi
    private fun listenForRemoteCommands() {
        thread {
            try {
                val serverSocket = ServerSocket(9001)
                while (true) {
                    val client = serverSocket.accept()
                    val reader = client.getInputStream().bufferedReader()
                    val command = reader.readLine()
                    
                    runOnUiThread {
                        when (command) {
                            "START" -> if (!isServiceRunning) shareButton.performClick()
                            "STOP" -> stopStreamingProcess()
                        }
                    }
                    client.close()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun stopStreamingProcess() {
        stopKioskMode()
        stopService(Intent(this, ScreenCastService::class.java))
        toggleDimMode(false)
        isServiceRunning = false
    }

    private fun setupKioskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(adminName, arrayOf(packageName))
            try { startLockTask() } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun stopKioskMode() {
        try { stopLockTask() } catch (e: Exception) { e.printStackTrace() }
    }

    private fun toggleDimMode(activate: Boolean) {
        isDimmed = activate
        val params = window.attributes
        if (activate) {
            params.screenBrightness = 0.01f
            mainLayout.setBackgroundColor(Color.BLACK)
            ipText.text = "TRANSMITIENDO... (Toca para restaurar)"
            shareButton.visibility = View.INVISIBLE
            setupKioskMode()
            isServiceRunning = true
        } else {
            params.screenBrightness = -1f
            mainLayout.setBackgroundColor(Color.parseColor("#1A1A1A"))
            updateIpDisplay()
            shareButton.visibility = View.VISIBLE
            isServiceRunning = false
        }
        window.attributes = params
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            projectionData = data // Guardamos los datos para autoreconexión
            val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            startForegroundService(serviceIntent)
            toggleDimMode(true)
        }
    }

    private fun updateIpDisplay() {
        ipText.text = "CLIENTE: 192.168.100.2"
    }

    override fun onBackPressed() {
        if (!isServiceRunning) super.onBackPressed() else moveTaskToBack(true)
    }
}
