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

    // CONFIGURACIÓN DE CONEXIÓN INVERSA
    // Pon aquí la IP del dispositivo que tiene la app CLIENTE abierta
    private val CLIENT_IP = "192.168.1.XX" 
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

        // 1. Iniciar Foreground inmediatamente para evitar que Android mate el servicio
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
            
            // 2. CORRECCIÓN: Registrar callback ANTES de crear el VirtualDisplay (Fix error Android 14)
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
        
        // Resolución optimizada para fluidez
        imageReader = ImageReader.newInstance(480, 854, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", 480, 854, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        
        thread {
            while (!isStopping) {
                if (isScreenOn.get()) {
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
                }
                Thread.sleep(150)
            }
        }
    }

    private fun startReverseConnection() {
        thread {
            while (!isStopping) {
                try {
                    // El servidor (Xiaomi) busca activamente al cliente
                    val socket = Socket(CLIENT_IP, CLIENT_PORT)
                    socket.tcpNoDelay = true
                    val out = DataOutputStream(socket.getOutputStream())
                    
                    while (socket.isConnected && !isStopping) {
                        val data = lastJpegData
                        if (data != null) {
                            out.writeInt(data.size)
                            out.write(data)
                            out.flush()
                        }
                        // Si la pantalla se apaga, seguimos enviando para no romper el socket
                        Thread.sleep(if (isScreenOn.get()) 100L else 1000L)
                    }
                } catch (e: Exception) {
                    // Si el cliente no está escuchando, reintenta en 3 segundos
                    Thread.sleep(3000)
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val chan = NotificationChannel("REV_CH", "Reverse Stream", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        return NotificationCompat.Builder(this, "REV_CH")
            .setContentTitle("Buscando Receptor...")
            .setContentText("Intentando conectar con el cliente en $CLIENT_IP")
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
