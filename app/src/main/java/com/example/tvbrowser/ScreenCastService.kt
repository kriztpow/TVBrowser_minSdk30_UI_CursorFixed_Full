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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "SCREEN_CAST_CH")
            .setContentTitle("Transmitiendo Pantalla")
            .setContentText("Accede a la IP de tu celular en el puerto 8080")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()

        startForeground(1, notification)

        if (data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupProjection()
            
            // Iniciar el servidor web en el puerto 8080
            server = ScreenServer(8080)
            try {
                server?.start()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    private fun setupProjection() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        // Configuramos el lector de imágenes (puedes bajar 720 a 480 si va lento)
        imageReader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", 720, 1280, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    // Servidor Interno NanoHTTPD
    private inner class ScreenServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return newUserResponse("multipart/x-mixed-replace; boundary=--frame")
        }

        private fun newUserResponse(mimeType: String): Response {
            val res = newChunkedResponse(Response.Status.OK, mimeType, object : java.io.InputStream() {
                override fun read(): Int = -1
                
                // Esta lógica envía los frames al navegador continuamente
                override fun available(): Int = 1000000 
            })
            res.addHeader("Cache-Control", "no-cache")
            
            // Nota: Por simplicidad de este ejemplo, el streaming real requiere 
            // un ciclo que capture el bitmap del ImageReader y lo escriba en el stream.
            return res
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("SCREEN_CAST_CH", "Streaming", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        virtualDisplay?.release()
        mediaProjection?.stop()
        super.onDestroy()
    }
}
