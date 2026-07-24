package com.raj.ffscanner

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START_LIVE = "com.raj.ffscanner.START_LIVE"
        const val ACTION_STOP_LIVE = "com.raj.ffscanner.STOP_LIVE"
        private const val CAPTURE_INTERVAL_MS = 1000L
        private const val JPEG_QUALITY = 65
        private var instance: ScreenCaptureService? = null
    }

    private val channelId = "ff_scanner_channel"
    private val notificationId = 101
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val captureRunnable = object : Runnable {
        override fun run() {
            captureAndSendScreenshot()
        }
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var liveRunning = false
    private var displayWidth = 0
    private var displayHeight = 0
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopLive(logStop = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(notificationId, notification)
        }

        when (intent?.action) {
            ACTION_START_LIVE -> {
                val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra("data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>("data")
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (projection == null) {
                        startProjection(resultCode, data)
                    }
                    startLive()
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP_LIVE -> {
                stopLive(logStop = true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(projectionCallback, mainHandler)
        createCapturePipeline()
    }

    private fun createCapturePipeline() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels

        imageReader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "FFScannerLiveCrop",
            displayWidth,
            displayHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun startLive() {
        if (liveRunning) return
        liveRunning = true
        OverlayService.addLog("LIVE_STREAM_STARTED")
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.postDelayed(captureRunnable, CAPTURE_INTERVAL_MS)
    }

    private fun captureAndSendScreenshot() {
        if (!liveRunning) return

        try {
            val reader = imageReader ?: return
            val image = reader.acquireLatestImage() ?: return
            val captureWidth = image.width
            val captureHeight = image.height
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth
            val bitmap = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )

            try {
                bitmap.copyPixelsFromBuffer(buffer)
            } finally {
                image.close()
            }

            val crop = currentCrop(captureWidth, captureHeight) ?: run {
                bitmap.recycle()
                return
            }
            val cropped = Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.width, crop.height)
            bitmap.recycle()

            val output = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            cropped.recycle()
            sendScreenshot(output.toByteArray())
        } catch (e: Exception) {
            OverlayService.addLog("WS_FAILURE frame=${e.message ?: "unknown"}")
        } finally {
            if (liveRunning) {
                mainHandler.postDelayed(captureRunnable, CAPTURE_INTERVAL_MS)
            }
        }
    }

    private fun currentCrop(captureWidth: Int, captureHeight: Int): CropRect? {
        val rect = OverlayService.getBoxRect() ?: return null
        val x = rect.left.coerceAtLeast(0).coerceAtMost(captureWidth - 1)
        val y = rect.top.coerceAtLeast(0).coerceAtMost(captureHeight - 1)
        val width = rect.width().coerceAtLeast(1).coerceAtMost(captureWidth - x)
        val height = rect.height().coerceAtLeast(1).coerceAtMost(captureHeight - y)
        return CropRect(x, y, width, height)
    }

    private fun sendScreenshot(bytes: ByteArray) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "crop_debug.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url(OcrServerSettings.getUrl(this))
            .addHeader("X-Scan-Id", System.currentTimeMillis().toString())
            .addHeader("X-Upload-Attempt", "1")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                OverlayService.addLog("WS_FAILURE frame=${e.message ?: "unknown"}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = try {
                    response.body?.string() ?: ""
                } finally {
                    response.close()
                }
                mainHandler.post {
                    handleCommandMessage(responseText)
                }
            }
        })
    }

    private fun handleCommandMessage(text: String) {
        try {
            val root = JSONObject(text)
            if (!root.optString("type", "").equals("COMMAND", ignoreCase = true)) return

            val command = root.optString("command", "").uppercase(java.util.Locale.US)
            val reason = root.optString("reason", "none")
            OverlayService.addLog("COMMAND_RECEIVED command=$command reason=$reason")

            when (command) {
                "SWIPE" -> executeSwipe(root)
                "STOP" -> {
                    OverlayService.addLog("COMMAND_STOP reason=$reason")
                    stopLive(logStop = true)
                    stopSelf()
                }
                else -> OverlayService.addLog("COMMAND_IGNORED reason=unknown_command_$command")
            }
        } catch (e: Exception) {
            OverlayService.addLog("WS_FAILURE command_parse=${e.message ?: "unknown"}")
        }
    }

    private fun executeSwipe(root: JSONObject) {
        val service = AutoScrollAccessibilityService.instance
        if (service == null) {
            OverlayService.addLog("ACCESSIBILITY_PERMISSION_MISSING")
            OverlayService.addLog("COMMAND_WAIT reason=accessibility_permission_missing")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }

        service.executeSwipe(
            x = root.optInt("x", displayWidth / 2),
            startY = root.optInt("startY", (displayHeight * 0.70f).toInt()),
            endY = root.optInt("endY", (displayHeight * 0.30f).toInt()),
            durationMs = root.optLong("durationMs", 350L)
        )
        OverlayService.addLog("STREAM_CONTINUING")
    }

    private fun stopLive(logStop: Boolean) {
        val wasRunning = liveRunning
        liveRunning = false
        mainHandler.removeCallbacks(captureRunnable)
        AutoScrollAccessibilityService.instance?.stopCommandExecution()
        if (logStop && wasRunning) {
            OverlayService.addLog("STOP_STREAM")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "FF Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("FF Scanner live crop stream")
                .setContentText("Streaming overlay crop only")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("FF Scanner live crop stream")
                .setContentText("Streaming overlay crop only")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        stopLive(logStop = true)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class CropRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )
}
