package com.example.tvbrowser

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: ScreenServer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var lastJpegData: ByteArray? = null
    private val isScreenOn = AtomicBoolean(true)
    private var isStopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopStreaming()
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> isScreenOn.set(false)
                Intent.ACTION_SCREEN_ON -> isScreenOn.set(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenShare::WakeLock")
        wakeLock?.acquire(10*60*1000L)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenShare::WifiLock")
        wifiLock?.acquire()

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "SCREEN_CAST_CH")
            .setContentTitle("Transmisión Activa")
            .setContentText("El servidor sigue funcionando con pantalla apagada")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        startForeground(1, notification)

        if (data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            // Registro obligatorio para evitar Crash en Android 14+
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

        val targetWidth = 480
        val targetHeight = 854

        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", targetWidth, targetHeight, metrics.densityDpi,
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
            val outputStream = PipedOutputStream()
            val inputStream = PipedInputStream(outputStream)

            thread {
                try {
                    while (!isStopping) {
                        val image = imageReader?.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val width = image.width
                            val height = image.height
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                            lastJpegData = baos.toByteArray()
                            bitmap.recycle()
                        }

                        lastJpegData?.let { data ->
                            outputStream.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n").toByteArray())
                            outputStream.write(data)
                            outputStream.write("\r\n".toByteArray())
                            outputStream.flush()
                        }

                        Thread.sleep(if (isScreenOn.get()) 150L else 1000L)
                    }
                } catch (e: Exception) {
                    try { outputStream.close() } catch (ex: Exception) {}
                }
            }
            // Corregido: Uso de newChunkedResponse para evitar error de parámetros
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", inputStream)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("SCREEN_CAST_CH", "Transmisión", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun stopStreaming() {
        if (isStopping) return
        isStopping = true
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        server?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) {}
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
