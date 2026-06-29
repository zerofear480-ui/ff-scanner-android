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
import android.os.HandlerThread
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
        private const val BACKEND_URL = "http://13.203.102.124:8000"
        private const val WS_URL = "ws://13.203.102.124:8000/ws/live-crop"
        private const val FRAME_INTERVAL_MS = 200L
        private const val JPEG_QUALITY = 55
        private const val MAX_FRAME_WIDTH = 480
        private var instance: ScreenCaptureService? = null
    }

    private val channelId = "ff_scanner_channel"
    private val notificationId = 101
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val frameRunnable = object : Runnable {
        override fun run() {
            captureTick()
        }
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var webSocket: WebSocket? = null
    @Volatile private var liveRunning = false
    @Volatile private var frameBusy = false
    @Volatile private var wsConnected = false
    private var displayWidth = 0
    private var displayHeight = 0
    private var sentSinceSummary = 0
    private var droppedSinceSummary = 0
    private var lastFrameBytes = 0
    private var lastCrop: CropRect? = null
    private var summaryWindowStartMs = 0L
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
        captureThread = HandlerThread("LiveCropStream")
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
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
            captureHandler
        )
    }

    private fun startLive() {
        if (liveRunning) return
        liveRunning = true
        frameBusy = false
        wsConnected = false
        sentSinceSummary = 0
        droppedSinceSummary = 0
        lastFrameBytes = 0
        lastCrop = null
        summaryWindowStartMs = System.currentTimeMillis()
        OverlayService.addLog("LIVE_START")
        openWebSocket()
        scheduleNextFrame(200L)
    }

    private fun openWebSocket() {
        OverlayService.addLog("WS_CONNECTING")
        OverlayService.addLog("BACKEND_URL=$BACKEND_URL")
        OverlayService.addLog("WS_URL=$WS_URL")
        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
                OverlayService.addLog("WS_OPEN")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post {
                    handleCommandMessage(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsConnected = false
                val responseText = response?.let { " code=${it.code} message=${it.message}" } ?: ""
                OverlayService.addLog("WS_FAILURE url=$WS_URL$responseText error=${t.message ?: "unknown"}")
                if (response?.code == 404) {
                    OverlayService.addLog("WS_404_URL=$WS_URL")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected = false
                OverlayService.addLog("WS_CLOSED code=$code reason=$reason")
            }
        })
    }

    private fun scheduleNextFrame(delayMs: Long = FRAME_INTERVAL_MS) {
        if (!liveRunning) return
        captureHandler.postDelayed(frameRunnable, delayMs)
    }

    private fun captureTick() {
        if (!liveRunning) return
        if (frameBusy) {
            droppedSinceSummary++
            logSummaryIfDue()
            scheduleNextFrame()
            return
        }

        frameBusy = true
        try {
            if (!wsConnected || webSocket == null) {
                droppedSinceSummary++
                return
            }

            val reader = imageReader ?: run {
                droppedSinceSummary++
                return
            }

            val image = reader.acquireLatestImage() ?: run {
                droppedSinceSummary++
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

            val crop = currentCrop(captureWidth, captureHeight) ?: run {
                droppedSinceSummary++
                bitmap.recycle()
                return
            }

            val cropped = Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.width, crop.height)
            bitmap.recycle()
            val frameBitmap = downscaleIfNeeded(cropped)
            if (frameBitmap !== cropped) {
                cropped.recycle()
            }

            val bos = ByteArrayOutputStream()
            frameBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            val bytes = bos.toByteArray()
            frameBitmap.recycle()

            if (sendFrame(bytes, crop)) {
                sentSinceSummary++
                lastFrameBytes = bytes.size
                lastCrop = crop
            } else {
                droppedSinceSummary++
            }
        } catch (e: Exception) {
            droppedSinceSummary++
            OverlayService.addLog("WS_FAILURE frame=${e.message ?: "unknown"}")
        } finally {
            frameBusy = false
            logSummaryIfDue()
            scheduleNextFrame()
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

    private fun downscaleIfNeeded(src: Bitmap): Bitmap {
        if (src.width <= MAX_FRAME_WIDTH) return src
        val targetHeight = maxOf(1, (src.height * (MAX_FRAME_WIDTH.toFloat() / src.width)).toInt())
        return Bitmap.createScaledBitmap(src, MAX_FRAME_WIDTH, targetHeight, true)
    }

    private fun sendFrame(bytes: ByteArray, crop: CropRect): Boolean {
        val socket = webSocket ?: return false
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
        return socket.send(root.toString())
    }

    private fun logSummaryIfDue() {
        val now = System.currentTimeMillis()
        val elapsed = now - summaryWindowStartMs
        if (elapsed < 1000L) return

        val fps = if (elapsed > 0) ((sentSinceSummary * 1000L) / elapsed).toInt() else 0
        val crop = lastCrop
        val cropText = if (crop != null) {
            "${crop.x},${crop.y},${crop.width},${crop.height}"
        } else {
            "none"
        }
        OverlayService.addLog(
            "LIVE_SUMMARY fps=$fps sent=$sentSinceSummary dropped=$droppedSinceSummary " +
                "ws=${if (wsConnected) "open" else "closed"} bytes=$lastFrameBytes crop=$cropText"
        )
        sentSinceSummary = 0
        droppedSinceSummary = 0
        summaryWindowStartMs = now
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
        if (::captureHandler.isInitialized) {
            captureHandler.removeCallbacks(frameRunnable)
        }
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
        if (::captureThread.isInitialized) {
            captureThread.quitSafely()
        }
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
