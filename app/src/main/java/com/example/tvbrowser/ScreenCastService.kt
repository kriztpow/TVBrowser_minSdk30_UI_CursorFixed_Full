package com.example.tvbrowser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Valores de ejemplo, asegúrate de que coincidan con tu lógica de captura
    private val width = 1080
    private val height = 2400
    private val dpi = 440
    private var surface: Surface? = null 

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("DATA")

        if (resultCode != -1 && data != null) {
            setupProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val mProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mProjectionManager.getMediaProjection(resultCode, data)

        // SOLUCIÓN AL ERROR: Registrar el callback antes de iniciar la captura
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                virtualDisplay?.release()
                mediaProjection = null
                stopSelf()
            }
        }

        // Se registra el callback obligatoriamente
        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        // Ahora el VirtualDisplay no lanzará la excepción
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                            val jpegData = baos.toByteArray()

                            outputStream.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegData.size}\r\n\r\n").toByteArray())
                            outputStream.write(jpegData)
                            outputStream.write("\r\n".toByteArray())
                            outputStream.flush()
                        } else {
                            Thread.sleep(300) // Pantalla apagada: esperamos con calma
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

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        server?.stop()
        virtualDisplay?.release()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
