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
import java.net.ServerSocket
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
        wakeLock?.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenShare::WifiLock")
        wifiLock?.acquire()

        startForeground(1, createNotification())

        if (data != null && mediaProjection == null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, handler)
            setupProjection()
            startSocketServer()
        }
        return START_STICKY
    }

    private fun setupProjection() {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
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
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val rowStride = planes[0].rowStride
                        val pixelStride = planes[0].pixelStride
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
                Thread.sleep(150)
            }
        }
    }

    private fun startSocketServer() {
        thread {
            try {
                val serverSocket = ServerSocket(8080)
                while (!isStopping) {
                    val client = serverSocket.accept()
                    handleClient(client)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun handleClient(socket: Socket) {
        thread {
            try {
                val out = DataOutputStream(socket.getOutputStream())
                while (socket.isConnected && !isStopping) {
                    val data = lastJpegData
                    if (data != null) {
                        out.writeInt(data.size)
                        out.write(data)
                        out.flush()
                    }
                    Thread.sleep(if (isScreenOn.get()) 100 else 500)
                }
            } catch (e: Exception) { socket.close() }
        }
    }

    private fun createNotification(): Notification {
        val chan = NotificationChannel("SC_CH", "Transmisión", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        return NotificationCompat.Builder(this, "SC_CH")
            .setContentTitle("Servidor de Pantalla")
            .setContentText("Listo para conexión de cliente")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }

    private fun stopStreaming() {
        isStopping = true
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(screenStateReceiver)
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
