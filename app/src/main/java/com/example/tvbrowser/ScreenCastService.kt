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
        override fun onStop() { stopStreaming() }
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
        if (wakeLock?.isHeld == false) wakeLock?.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenShare::WifiLock")
        if (wifiLock?.isHeld == false) wifiLock?.acquire()

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "SCREEN_CAST_CH")
            .setContentTitle("Servidor de Pantalla Activo")
            .setContentText("Transmitiendo en segundo plano...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX) // Máxima prioridad
            .build()
        
        startForeground(1, notification)

        if (data != null && mediaProjection == null) {
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
        if (server == null) {
            server = ScreenServer(8080)
            try { server?.start() } catch (e: IOException) { e.printStackTrace() }
        }
    }

    private inner class ScreenServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val outputStream = PipedOutputStream()
            val inputStream = PipedInputStream(outputStream)

            thread {
                try {
                    while (!isStopping) {
                        // SI LA PANTALLA ESTÁ ENCENDIDA, CAPTURAMOS NUEVOS FRAMES
                        if (isScreenOn.get()) {
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
                        }

                        // SIEMPRE ENVIAMOS EL ÚLTIMO FRAME (Esté la pantalla on u off)
                        // Esto engaña al sistema y mantiene la conexión viva en el bolsillo
                        lastJpegData?.let { data ->
                            try {
                                outputStream.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n").toByteArray())
                                outputStream.write(data)
                                outputStream.write("\r\n".toByteArray())
                                outputStream.flush()
                            } catch (e: Exception) {
                                // El cliente se desconectó
                                return@thread
                            }
                        }
                        
                        Thread.sleep(if (isScreenOn.get()) 100L else 500L)
                    }
                } catch (e: Exception) {
                    try { outputStream.close() } catch (ex: Exception) {}
                }
            }
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", inputStream)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("SCREEN_CAST_CH", "Transmisión", NotificationManager.IMPORTANCE_HIGH)
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
