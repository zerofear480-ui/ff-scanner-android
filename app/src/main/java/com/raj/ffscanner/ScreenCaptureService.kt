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
import android.os.Environment
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
import java.io.File
import java.io.FileOutputStream

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
            OverlayService.addLog("Projection stopped")
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
            OverlayService.addLog("Permission denied")
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
            val reader = imageReader ?: run {
                OverlayService.addLog("ImageReader null")
                return
            }

            val image = reader.acquireLatestImage() ?: run {
                OverlayService.addLog("No image yet")
                return
            }

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

            val savedScreenW = prefs.getInt("screen_w", bitmap.width)
            val savedScreenH = prefs.getInt("screen_h", bitmap.height)

            val scaleX = bitmap.width.toFloat() / savedScreenW.toFloat()
            val scaleY = bitmap.height.toFloat() / savedScreenH.toFloat()

            val savedX = prefs.getInt("x", 120)
            val savedY = prefs.getInt("y", 220)
            val savedW = prefs.getInt("w", 600)
            val savedH = prefs.getInt("h", 600)

            val x = (savedX * scaleX).toInt().coerceAtLeast(0).coerceAtMost(bitmap.width - 1)
            val y = (savedY * scaleY).toInt().coerceAtLeast(0).coerceAtMost(bitmap.height - 1)
            val w = (savedW * scaleX).toInt().coerceAtLeast(100).coerceAtMost(bitmap.width - x)
            val h = (savedH * scaleY).toInt().coerceAtLeast(100).coerceAtMost(bitmap.height - y)

            OverlayService.addLog("Scale sx=$scaleX sy=$scaleY")
            OverlayService.addLog("Crop x=$x y=$y w=$w h=$h")

            val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)

            try {
                val file = File("/storage/emulated/0/Download/crop_debug.png")
                FileOutputStream(file).use { out ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                OverlayService.addLog("Crop saved: Downloads/crop_debug.png")
            } catch (e: Exception) {
                OverlayService.addLog("Crop save error: ${e.message}")
            }

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
                    OverlayService.addLog("OCR error: ${it.message}")
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
            override fun onFailure(call: Call, e: java.io.IOException) {
                OverlayService.addLog("Debug send failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                OverlayService.addLog("Debug sent ${response.code}")
                response.close()
            }
        })
    }

    
private fun parsePlayers(text: String): List<PlayerData> {
        val players = mutableListOf<PlayerData>()
        var slot = 1

        val ignore = listOf(
            "players", "safe", "zone", "bermuda", "game", "hp", "ep",
            "alive", "spectating", "permission", "projection", "starting",
            "debug", "sent", "capture", "crop", "scale", "ocr", "length",
            "booyah", "paid", "app", "ok", "final", "round", "service",
            "destroyed", "stopped", "tick"
        )

        text.lines().forEach { raw ->
            var line = raw.trim()
                .replace("|", " ")
                .replace(">", "")
                .replace(")", "")
                .replace("(", "")
                .replace("  ", " ")

            if (line.length < 3) return@forEach
            if (line.all { it.isDigit() }) return@forEach

            val lower = line.lowercase()
            if (ignore.any { lower.contains(it) }) return@forEach

            var kills = 0
            val m = Regex("""^(.+?)\s+(\d{1,2})$""").find(line)

            if (m != null) {
                line = m.groupValues[1].trim()
                kills = m.groupValues[2].toIntOrNull() ?: 0
            }

            line = line.replace(Regex("""[^A-Za-z0-9_ .!'₹-]"""), "").trim()

            if (line.length < 3) return@forEach
            if (line.split(" ").size > 4) return@forEach

            players.add(PlayerData(slot, line, kills))
            slot++
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
            override fun onFailure(call: Call, e: java.io.IOException) {
                OverlayService.addLog("Players send failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                OverlayService.addLog("Players sent ${response.code}")
                response.close()
            }
        })
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
        OverlayService.addLog("Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    data class PlayerData(val slot: Int, val name: String, val kills: Int)
}
