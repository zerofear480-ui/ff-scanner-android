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
            stopScanner()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apiUrl = intent?.getStringExtra("apiUrl") ?: apiUrl

        createChannel()
        val notification = buildNotification("Capturing screen for OCR")

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

        running = true
        scanLoop()
    }

    private fun scanLoop() {
        if (!running) return

        captureAndOcr()

        handler.postDelayed({
            scanLoop()
        }, 4000)
    }

    private fun captureAndOcr() {
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

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            val inputImage = InputImage.fromBitmap(cropped, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val players = parsePlayers(result.text)
                    if (players.isNotEmpty()) {
                        sendPlayers(players)
                    }
                }
        } catch (e: Exception) {
            // keep service alive
        }
    }

    private fun parsePlayers(text: String): List<PlayerData> {
        val players = mutableListOf<PlayerData>()
        val regex = Regex("""^\s*(\d{1,2})\s+(.+?)\s+(\d{1,2})\s*$""")

        text.lines().forEach { line ->
            val m = regex.find(line.trim())
            if (m != null) {
                val slot = m.groupValues[1].toIntOrNull() ?: return@forEach
                val name = m.groupValues[2].trim()
                val kills = m.groupValues[3].toIntOrNull() ?: 0

                if (slot in 1..50) {
                    players.add(PlayerData(slot, name, kills))
                }
            }
        }

        return players
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

        val req = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun stopScanner() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        stopScanner()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "FF Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("FF Scanner is running")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("FF Scanner is running")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class PlayerData(
        val slot: Int,
        val name: String,
        val kills: Int
    )
}
