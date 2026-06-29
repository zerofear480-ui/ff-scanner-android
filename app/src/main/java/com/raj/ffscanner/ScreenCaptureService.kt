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
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START_LIVE = "com.raj.ffscanner.START_LIVE"
        const val ACTION_STOP_LIVE = "com.raj.ffscanner.STOP_LIVE"
        private const val WS_URL = "ws://13.203.102.124:8000/ws/live-crop"
        private const val FRAME_INTERVAL_MS = 75L
        private var instance: ScreenCaptureService? = null
    }

    private val channelId = "ff_scanner_channel"
    private val notificationId = 101
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val frameRunnable = object : Runnable {
        override fun run() {
            captureAndSendFrame()
        }
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var webSocket: WebSocket? = null
    private var liveRunning = false
    private var frameBusy = false
    private var wsConnected = false
    private var displayWidth = 0
    private var displayHeight = 0
    private var frameCount = 0
    private var fpsWindowStartMs = 0L
    private var lastFps = 0
    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android"
    }

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
        projection?.registerCallback(projectionCallback, handler)
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
            handler
        )
    }

    private fun startLive() {
        if (liveRunning) return
        liveRunning = true
        frameBusy = false
        wsConnected = false
        frameCount = 0
        fpsWindowStartMs = System.currentTimeMillis()
        OverlayService.addLog("LIVE_START")
        openWebSocket()
        scheduleNextFrame(150L)
    }

    private fun openWebSocket() {
        OverlayService.addLog("WS_CONNECTING")
        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    wsConnected = true
                    OverlayService.addLog("WS_CONNECTED")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post {
                    handleCommandMessage(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    wsConnected = false
                    OverlayService.addLog("WS_ERROR error=${t.message ?: "unknown"}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post {
                    wsConnected = false
                    OverlayService.addLog("WS_CLOSED code=$code reason=$reason")
                }
            }
        })
    }

    private fun scheduleNextFrame(delayMs: Long = FRAME_INTERVAL_MS) {
        if (!liveRunning) return
        handler.postDelayed(frameRunnable, delayMs)
    }

    private fun captureAndSendFrame() {
        if (!liveRunning) return
        if (frameBusy) {
            scheduleNextFrame()
            return
        }
        frameBusy = true

        try {
            if (!wsConnected || webSocket == null) {
                frameBusy = false
                scheduleNextFrame()
                return
            }

            val reader = imageReader ?: run {
                frameBusy = false
                scheduleNextFrame()
                return
            }

            val image = reader.acquireLatestImage() ?: run {
                frameBusy = false
                scheduleNextFrame()
                return
            }

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

            val crop = currentCrop(captureWidth, captureHeight)
            val cropped = Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.width, crop.height)
            val bos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 65, bos)
            val bytes = bos.toByteArray()
            cropped.recycle()
            bitmap.recycle()

            sendFrame(bytes, crop)
        } catch (e: Exception) {
            OverlayService.addLog("WS_ERROR frame=${e.message ?: "unknown"}")
        } finally {
            frameBusy = false
            scheduleNextFrame()
        }
    }

    private fun currentCrop(captureWidth: Int, captureHeight: Int): CropRect {
        val rect = OverlayService.getBoxRect()
        val prefs = getSharedPreferences("ocr_box", MODE_PRIVATE)
        val savedX = rect?.left ?: prefs.getInt("x", 120)
        val savedY = rect?.top ?: prefs.getInt("y", 220)
        val savedW = rect?.width() ?: prefs.getInt("w", 600)
        val savedH = rect?.height() ?: prefs.getInt("h", 600)
        val size = minOf(savedW, savedH).coerceAtLeast(1)

        val x = savedX.coerceAtLeast(0).coerceAtMost(captureWidth - 1)
        val y = savedY.coerceAtLeast(0).coerceAtMost(captureHeight - 1)
        val width = size.coerceAtMost(captureWidth - x)
        val height = size.coerceAtMost(captureHeight - y)
        return CropRect(x, y, width, height)
    }

    private fun sendFrame(bytes: ByteArray, crop: CropRect) {
        val root = JSONObject()
        root.put("type", "FRAME")
        root.put("deviceId", deviceId)
        root.put("timestamp", System.currentTimeMillis())
        root.put("format", "jpeg")
        root.put("crop", JSONObject().apply {
            put("x", crop.x)
            put("y", crop.y)
            put("width", crop.width)
            put("height", crop.height)
        })
        root.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))

        val ok = webSocket?.send(root.toString()) == true
        if (ok) {
            val fps = updateFps()
            OverlayService.addLog(
                "FRAME_SENT fps=$fps bytes=${bytes.size} crop=${crop.x},${crop.y},${crop.width},${crop.height}"
            )
        } else {
            OverlayService.addLog("WS_ERROR frame_send_failed")
        }
    }

    private fun updateFps(): Int {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000L) {
            lastFps = ((frameCount * 1000L) / elapsed).toInt()
            frameCount = 0
            fpsWindowStartMs = now
        }
        return lastFps
    }

    private fun handleCommandMessage(text: String) {
        try {
            val root = JSONObject(text)
            if (!root.optString("type", "").equals("COMMAND", ignoreCase = true)) return

            val command = root.optString("command", "").uppercase(java.util.Locale.US)
            val reason = root.optString("reason", "none")
            OverlayService.addLog("COMMAND_RECEIVED $command reason=$reason")

            when (command) {
                "SWIPE" -> executeSwipe(root)
                "TAP" -> executeTap(root)
                "WAIT" -> OverlayService.addLog("COMMAND_WAIT reason=$reason")
                "STOP" -> {
                    OverlayService.addLog("COMMAND_STOP reason=$reason")
                    stopLive(logStop = true)
                    stopSelf()
                }
                else -> OverlayService.addLog("COMMAND_WAIT reason=unknown_command_$command")
            }
        } catch (e: Exception) {
            OverlayService.addLog("WS_ERROR command_parse=${e.message ?: "unknown"}")
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
        ) {}
    }

    private fun executeTap(root: JSONObject) {
        val service = AutoScrollAccessibilityService.instance
        if (service == null) {
            OverlayService.addLog("ACCESSIBILITY_PERMISSION_MISSING")
            OverlayService.addLog("COMMAND_WAIT reason=accessibility_permission_missing")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }

        service.executeTap(
            x = root.optInt("x", displayWidth / 2),
            y = root.optInt("y", displayHeight / 2),
            durationMs = root.optLong("durationMs", 80L)
        ) {}
    }

    private fun stopLive(logStop: Boolean) {
        val wasRunning = liveRunning
        liveRunning = false
        frameBusy = false
        wsConnected = false
        handler.removeCallbacks(frameRunnable)
        try { webSocket?.close(1000, "client_stop") } catch (_: Exception) {}
        webSocket = null
        AutoScrollAccessibilityService.instance?.stopCommandExecution()
        if (logStop && wasRunning) {
            OverlayService.addLog("LIVE_STOP")
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
