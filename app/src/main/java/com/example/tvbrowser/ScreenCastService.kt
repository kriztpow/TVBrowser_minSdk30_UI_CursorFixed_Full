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
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: ScreenServer? = null
    private val handler = Handler(Looper.getMainLooper())

    // Locks para mantener CPU y Wi-Fi activos
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Último frame capturado (en formato JPEG)
    @Volatile
    private var lastJpegData: ByteArray? = null

    // Estado de la pantalla
    private val isScreenOn = AtomicBoolean(true)

    // Bandera para evitar doble liberación
    private var isStopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopStreaming()
        }
    }

    // Receptor para eventos de pantalla
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn.set(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn.set(true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Registrar receptor para encendido/apagado de pantalla
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        // Adquirir WakeLock sin timeout
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenShare::WakeLock")
        wakeLock?.acquire()

        // Adquirir WifiLock de alto rendimiento
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenShare::WifiLock")
        wifiLock?.acquire()

        // Crear canal de notificación y poner en foreground
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "SCREEN_CAST_CH")
            .setContentTitle("Transmisión en curso")
            .setContentText("El servidor sigue activo aunque la pantalla esté apagada")
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

        // Usamos una resolución moderada para equilibrar calidad/rendimiento
        val targetWidth = 720
        val targetHeight = (targetWidth * metrics.heightPixels) / metrics.widthPixels

        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            targetWidth,
            targetHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,  // Usamos !! porque imageReader ya fue inicializado
            null,
            null
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

    // Servidor HTTP que sirve MJPEG
    private inner class ScreenServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return object : Response(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", null) {
                override fun send(out: OutputStream) {
                    try {
                        // Mientras la conexión esté activa, enviamos frames
                        while (true) {
                            // Intentar obtener una imagen nueva
                            val image = imageReader?.acquireLatestImage()
                            if (image != null) {
                                // Convertir a JPEG y guardar como último frame
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                val pixelStride = planes[0].pixelStride
                                val rowStride = planes[0].rowStride
                                val width = image.width
                                val height = image.height
                                val rowPadding = rowStride - pixelStride * width

                                val bitmap = Bitmap.createBitmap(
                                    width + rowPadding / pixelStride,
                                    height,
                                    Bitmap.Config.ARGB_8888
                                )
                                bitmap.copyPixelsFromBuffer(buffer)

                                val baos = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                                val jpegData = baos.toByteArray()
                                lastJpegData = jpegData

                                image.close()
                                bitmap.recycle()
                            }

                            // Siempre enviamos el último frame disponible (nuevo o repetido)
                            lastJpegData?.let { data ->
                                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray())
                                out.write(data)
                                out.write("\r\n".toByteArray())
                                out.flush()
                            }

                            // Ajustar frecuencia según estado de la pantalla
                            val delay = if (isScreenOn.get()) 150L else 1000L
                            Thread.sleep(delay)
                        }
                    } catch (e: IOException) {
                        // Cliente cerró la conexión - salir del bucle normalmente
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        // Usamos nombres completamente calificados para evitar ambigüedades
        val channel = android.app.NotificationChannel(
            "SCREEN_CAST_CH",
            "Transmisión de pantalla",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun stopStreaming() {
        if (isStopping) return
        isStopping = true

        // Liberar locks
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()

        // Detener servidor
        server?.stop()
        server = null

        // Liberar recursos de captura
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        // Detener proyección
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(screenStateReceiver)
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
