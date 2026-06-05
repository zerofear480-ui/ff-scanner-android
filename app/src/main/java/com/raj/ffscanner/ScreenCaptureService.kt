package com.raj.ffscanner

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    private val channelId = "ff_scanner_channel"
    private val notificationId = 101
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var running = false
    private var apiUrl = "http://13.204.87.106:8000/api/ocr-scan"

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            running = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apiUrl = intent?.getStringExtra("apiUrl") ?: apiUrl

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

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            OverlayService.addLog("Permission OK, starting projection")
            startProjection(resultCode, data)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(projectionCallback, handler)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "FFScannerDisplay",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        OverlayService.addLog("Projection created")
        running = true
        scanLoop()
    }

    private fun scanLoop() {
        if (!running) return
        captureAndOcr()
        handler.postDelayed({ scanLoop() }, 4000)
    }

    private fun captureAndOcr() {
        OverlayService.addLog("Capture tick")
        try {
            val reader = imageReader ?: return
            val image = reader.acquireLatestImage() ?: return

            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val prefs = getSharedPreferences("ocr_box", MODE_PRIVATE)
            val x = prefs.getInt("x", 120).coerceAtLeast(0).coerceAtMost(bitmap.width - 1)
            val y = prefs.getInt("y", 220).coerceAtLeast(0).coerceAtMost(bitmap.height - 1)
            val w = prefs.getInt("w", 600).coerceAtLeast(100).coerceAtMost(bitmap.width - x)
            val h = prefs.getInt("h", 600).coerceAtLeast(100).coerceAtMost(bitmap.height - y)

            OverlayService.addLog("Crop x=$x y=$y w=$w h=$h")
            val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
            val inputImage = InputImage.fromBitmap(cropped, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    OverlayService.addLog("OCR length=${result.text.length}")
                    sendDebug(result.text, x, y, w, h)

                    val players = parsePlayers(result.text)
                    OverlayService.addLog("Players found=${players.size}")
                    if (players.isNotEmpty()) {
                        sendPlayers(players)
                    }
                }
                .addOnFailureListener {
                    sendDebug("OCR_ERROR: ${it.message}", x, y, w, h)
                }

        } catch (e: Exception) {
            OverlayService.addLog("Capture error: ${e.message}")
            sendDebug("CAPTURE_ERROR: ${e.message}", 0, 0, 0, 0)
        }
    }

    private fun sendDebug(text: String, x: Int, y: Int, w: Int, h: Int) {
        val root = JSONObject()
        root.put("ocr_text", text.take(2000))
        root.put("players", JSONArray())
        root.put("box_x", x)
        root.put("box_y", y)
        root.put("box_w", w)
        root.put("box_h", h)

        val debugUrl = apiUrl.replace("/api/ocr-scan", "/api/ocr-debug")
        val body = root.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(debugUrl).post(body).build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { OverlayService.addLog("Send failed: ${e.message}") }
            override fun onResponse(call: Call, response: Response) { OverlayService.addLog("Debug sent ${response.code}"); response.close() }
        })
    }

    private fun parsePlayers(text: String): List<PlayerData> {
        val players = mutableListOf<PlayerData>()
        var slot = 1

        text.lines().forEach { raw ->
            val line = raw.trim().replace("|", " ")
            val m = Regex("""^(.+?)\s+(\d{1,2})$""").find(line)
            if (m != null) {
                val name = m.groupValues[1].trim()
                val kills = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (name.length >= 2 && kills in 0..99) {
                    players.add(PlayerData(slot, name, kills))
                    slot++
                }
            }
        }
        return players.take(20)
    }

    private fun sendPlayers(players: List<PlayerData>) {
        val arr = JSONArray()
        players.forEach {
            val obj = JSONObject()
            obj.put("slot", it.slot)
            obj.put("name", it.name)
            obj.put("kills", it.kills)
            arr.put(obj)
        }

        val root = JSONObject()
        root.put("players", arr)

        val body = root.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(apiUrl).post(body).build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FF Scanner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("FF Scanner is running")
                .setContentText("Capturing OCR box")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("FF Scanner is running")
                .setContentText("Capturing OCR box")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    data class PlayerData(val slot: Int, val name: String, val kills: Int)
}
