package com.example.tvbrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: ScreenServer? = null
    private val handler = Handler(Looper.getMainLooper())

    // Callback obligatorio para Android 14+ (Corrige el IllegalStateException)
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopStreaming()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "SCREEN_CAST_CH")
            .setContentTitle("Pantalla Compartida")
            .setContentText("Transmitiendo en el puerto 8080")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        if (data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            
            // Registro del callback antes de crear el VirtualDisplay
            mediaProjection?.registerCallback(projectionCallback, handler)
            
            setupProjection()
            startServer()
        }

        return START_STICKY
    }

    private fun setupProjection() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        // Usamos una resolución moderada (720p) para evitar lag
        imageReader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", 720, 1280, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startServer() {
        server = ScreenServer(8080)
        try {
            server?.start()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private inner class ScreenServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            // El navegador recibirá un flujo MJPEG
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", object : java.io.InputStream() {
                override fun read(): Int = -1 
            }).apply {
                addHeader("Cache-Control", "no-cache")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("SCREEN_CAST_CH", "Transmisión", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun stopStreaming() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        server?.stop()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
