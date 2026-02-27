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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var lastJpegData: ByteArray? = null
    private val isScreenOn = AtomicBoolean(true)
    private var isStopping = false

    private val CLIENT_IP = "192.168.100.2"
    private val CLIENT_PORT = 9000

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

        startForeground(1, createNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReverseCast::Lock")
        if (wakeLock?.isHeld == false) wakeLock?.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReverseCast::WifiLock")
        if (wifiLock?.isHeld == false) wifiLock?.acquire()

        if (data != null && mediaProjection == null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, handler)

            setupProjection()
            startReverseConnection()
        }
        return START_STICKY
    }

    private fun setupProjection() {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        
        imageReader = ImageReader.newInstance(480, 854, PixelFormat.RGBA_8888, 2)

        // CAMBIO CRÍTICO: Flags para que no se detenga al apagar pantalla
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or 
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or 
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", 480, 854, metrics.densityDpi,
            flags, imageReader?.surface, null, null
        )

        thread {
            while (!isStopping) {
                // Quitamos el check de isScreenOn para intentar capturar siempre
                val image = imageReader?.acquireLatestImage()
                image?.let {
                    val planes = it.planes
                    val buffer = planes[0].buffer
                    val width = it.width
                    val height = it.height
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    it.close()
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                    lastJpegData = baos.toByteArray()
                    bitmap.recycle()
                }
                Thread.sleep(150)
            }
        }
    }

    private fun startReverseConnection() {
        thread {
            while (!isStopping) {
                try {
                    val socket = Socket(CLIENT_IP, CLIENT_PORT)
                    socket.tcpNoDelay = true
                    socket.keepAlive = true // Mantiene el socket vivo a nivel TCP
                    val out = DataOutputStream(socket.getOutputStream())

                    while (socket.isConnected && !isStopping) {
                        val data = lastJpegData
                        if (data != null) {
                            out.writeInt(data.size)
                            out.write(data)
                            out.flush()
                        } else {
                            // Si no hay imagen, enviamos un "ping" para que el socket no se cierre
                            out.writeInt(-1)
                            out.flush()
                        }
                        // Enviamos más lento si la pantalla está apagada para ahorrar recursos
                        Thread.sleep(if (isScreenOn.get()) 100L else 500L)
                    }
                } catch (e: Exception) {
                    Thread.sleep(3000)
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val chan = NotificationChannel("REV_CH", "Reverse Stream", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        return NotificationCompat.Builder(this, "REV_CH")
            .setContentTitle("Servidor de Pantalla Activo")
            .setContentText("Transmitiendo a $CLIENT_IP")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun stopStreaming() {
        isStopping = true
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
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

    override fun onBind(intent: Intent?) = null
}
