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
import android.net.wifi.WifiManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: ScreenServer? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Bloqueos para evitar desconexión al apagar pantalla
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopStreaming() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        // 1. Activar bloqueos de energía y Wi-Fi
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenShare::WakeLock")
        wakeLock?.acquire(10*60*1000L /*10 minutos o lo que desees*/)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenShare::WifiLock")
        wifiLock?.acquire()

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "SCREEN_CAST_CH")
            .setContentTitle("Transmisión Persistente Activa")
            .setContentText("El servidor sigue funcionando aunque apagues la pantalla")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        if (data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
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

        imageReader = ImageReader.newInstance(480, 854, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", 480, 854, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startServer() {
        server = ScreenServer(8080)
        try { server?.start() } catch (e: IOException) { e.printStackTrace() }
    }

    private inner class ScreenServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val outputStream = PipedOutputStream()
            val inputStream = PipedInputStream(outputStream)

            thread {
                try {
                    while (true) {
                        val image = imageReader?.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * 480

                            val bitmap = Bitmap.createBitmap(480 + rowPadding / pixelStride, 854, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                            val jpegData = baos.toByteArray()

                            outputStream.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpegData.size + "\r\n\r\n").toByteArray())
                            outputStream.write(jpegData)
                            outputStream.write("\r\n".toByteArray())
                            outputStream.flush()
                        }
                        Thread.sleep(150) 
                    }
                } catch (e: Exception) { }
            }
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", inputStream)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("SCREEN_CAST_CH", "Transmisión", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun stopStreaming() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        server?.stop()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopStreaming(); super.onDestroy() }
}
