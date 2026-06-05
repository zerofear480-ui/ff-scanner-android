package com.raj.ffscanner

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import java.io.ByteArrayOutputStream

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

            OverlayService.addLog("Saved box x=$savedX y=$savedY w=$savedW h=$savedH")
            OverlayService.addLog("Bitmap ${bitmap.width}x${bitmap.height} savedScreen ${savedScreenW}x${savedScreenH}")
            OverlayService.addLog("Scale sx=$scaleX sy=$scaleY")
            OverlayService.addLog("Crop x=$x y=$y w=$w h=$h")

            val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)

            OverlayService.addLog("Local crop save disabled")

            uploadCropDebug(cropped)

            val inputImage = InputImage.fromBitmap(cropped, 0)

            val nameW = (cropped.width * 0.72f).toInt()
            val killX = (cropped.width * 0.78f).toInt()

            val nameCrop = Bitmap.createBitmap(cropped, 0, 0, nameW, cropped.height)
            val killCrop = Bitmap.createBitmap(cropped, killX, 0, cropped.width - killX, cropped.height)
            uploadKillCropDebug(killCrop)

            recognizer.process(InputImage.fromBitmap(nameCrop, 0))
                .addOnSuccessListener { nameResult ->
                    recognizer.process(InputImage.fromBitmap(killCrop, 0))
                        .addOnSuccessListener { killResult ->

                            val names = extractNamesWithY(nameResult)
                            val kills = extractKillsWithY(killResult)

                            val players = mutableListOf<PlayerData>()

                            names.forEachIndexed { index, item ->
                                val nearest = kills.minByOrNull { kotlin.math.abs(it.y - item.y) }
                                val kill = if (nearest != null && kotlin.math.abs(nearest.y - item.y) < 45) nearest.value else 0
                                players.add(PlayerData(index + 1, item.name, kill))
                            }

                            OverlayService.addLog("Mapped players=${players.size}")
                            OverlayService.addLog("Kill OCR text=${killResult.text.take(80)}")

                            sendDebug(
                                "NAMES:\\n${nameResult.text}\\n\\nKILLS:\\n${killResult.text}",
                                x, y, w, h
                            )

                            if (players.isNotEmpty()) {
                                sendPlayers(players)
                            }
                        }
                        .addOnFailureListener {
                            OverlayService.addLog("Kill OCR error: ${it.message}")
                        }
                }
                .addOnFailureListener {
                    OverlayService.addLog("Name OCR error: ${it.message}")
                }

        } catch (e: Exception) {
            OverlayService.addLog("Capture error: ${e.message}")
            sendDebug("CAPTURE_ERROR: ${e.message}", 0, 0, 0, 0)
        }
    }

    private fun parseNamesOnly(text: String): List<String> {
        val names = mutableListOf<String>()

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

            line = line.replace(Regex("""[^A-Za-z0-9_ .!'₹-]"""), "").trim()

            if (line.length < 3) return@forEach
            if (line.split(" ").size > 5) return@forEach

            names.add(line)
        }

        return names.take(20)
    }


    private fun preprocessKillCrop(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)

        for (yy in 0 until src.height) {
            for (xx in 0 until src.width) {
                val c = src.getPixel(xx, yy)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                val gray = (r * 0.3 + g * 0.59 + b * 0.11).toInt()

                val v = if (gray > 145) 255 else 0
                out.setPixel(xx, yy, Color.rgb(v, v, v))
            }
        }

        return out
    }

    private fun parseKillsOnly(text: String): List<Int> {
        val nums = mutableListOf<Int>()

        Regex("""\b\d{1,2}\b""").findAll(text).forEach { m ->
            val n = m.value.toIntOrNull()
            if (n != null && n in 0..99) {
                nums.add(n)
            }
        }

        return nums.take(20)
    }



    data class NameY(val name: String, val y: Int)
    data class KillY(val value: Int, val y: Int)

    private fun extractNamesWithY(result: com.google.mlkit.vision.text.Text): List<NameY> {
        val list = mutableListOf<NameY>()
        val ignore = listOf("players", "bermuda", "game", "safe", "zone", "hp", "ep")

        for (block in result.textBlocks) {
            for (line in block.lines) {
                var text = line.text.trim()
                    .replace("|", " ")
                    .replace(">", "")
                    .replace(")", "")
                    .replace("(", "")
                    .replace(Regex("""\s+"""), " ")

                if (text.length < 3) continue
                if (text.all { it.isDigit() }) continue

                val lower = text.lowercase()
                if (ignore.any { lower.contains(it) }) continue

                text = text.replace(Regex("""[^A-Za-z0-9_ .!'₹-]"""), "").trim()
                if (text.length < 3) continue

                val y = line.boundingBox?.centerY() ?: continue
                list.add(NameY(text, y))
            }
        }

        return list.take(20)
    }

    private fun extractKillsWithY(result: com.google.mlkit.vision.text.Text): List<KillY> {
        val list = mutableListOf<KillY>()

        for (block in result.textBlocks) {
            for (line in block.lines) {
                val y = line.boundingBox?.centerY() ?: continue
                Regex("""\b\d{1,2}\b""").findAll(line.text).forEach { m ->
                    val raw = m.value
                    val value = if (raw.length > 1 && raw.startsWith("0")) 0 else (raw.toIntOrNull() ?: 0)
                    if (value in 0..99) list.add(KillY(value, y))
                }
            }
        }

        return list
    }

    private fun uploadKillCropDebug(cropped: Bitmap) {
        try {
            val bos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 100, bos)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "kill_crop.png",
                    bos.toByteArray().toRequestBody("image/png".toMediaType())
                )
                .build()

            val url = apiUrl.replace("/api/ocr-scan", "/api/kill-debug")
            val req = Request.Builder().url(url).post(body).build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    OverlayService.addLog("Kill crop upload failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    OverlayService.addLog("Kill crop uploaded ${response.code}")
                    response.close()
                }
            })
        } catch (e: Exception) {
            OverlayService.addLog("Kill crop upload error: ${e.message}")
        }
    }

    private fun uploadCropDebug(cropped: Bitmap) {
        try {
            val bos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 100, bos)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "crop_debug.png",
                    bos.toByteArray().toRequestBody("image/png".toMediaType())
                )
                .build()

            val url = apiUrl.replace("/api/ocr-scan", "/api/crop-debug")
            val req = Request.Builder().url(url).post(body).build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    OverlayService.addLog("Crop upload failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    OverlayService.addLog("Crop uploaded ${response.code}")
                    response.close()
                }
            })
        } catch (e: Exception) {
            OverlayService.addLog("Crop upload error: ${e.message}")
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
                val killText = m.groupValues[2]
                kills = if (killText.length > 1 && killText.startsWith("0")) 0 else (killText.toIntOrNull() ?: 0)
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
