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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START_OCR = "com.raj.ffscanner.START_OCR"
        const val ACTION_STOP_OCR = "com.raj.ffscanner.STOP_OCR"
        const val EXTRA_AUTO_SCROLL_ENABLED = "auto_scroll_enabled"
        private const val CAPTURE_INTERVAL_MS = 1000L
        private const val NO_CAPTURE_RESTART_MS = 3000L
        private const val MAX_CONTINUOUS_NULL_IMAGES = 3
        private const val MAX_UPLOAD_ATTEMPTS = 3
        private const val UPLOAD_RETRY_DELAY_MS = 750L
        private var instance: ScreenCaptureService? = null
    }

    private val channelId = "ff_scanner_channel"
    private val notificationId = 101
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val scanRunnable = object : Runnable {
        override fun run() {
            scanLoop()
        }
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var scanRunning = false
    private var scanBusy = false
    private var lastFrameMs = 0L
    private var lastPipelineRestartMs = 0L
    private var continuousNullImages = 0
    private var nextScanId = 0L
    private var activeScanId = 0L
    private var lastCropUploadAt = 0L
    private var autoScrollEnabled = false
    private var apiUrl = "http://13.203.102.124:8000/api/ocr-scan"

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopScanning(logStop = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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

        when (intent?.action) {
            ACTION_START_OCR -> {
                autoScrollEnabled = intent.getBooleanExtra(EXTRA_AUTO_SCROLL_ENABLED, false)
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
                    startScanning()
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP_OCR -> {
                stopScanning(logStop = true)
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
        lastFrameMs = System.currentTimeMillis()
        continuousNullImages = 0
        lastPipelineRestartMs = lastFrameMs
    }

    private fun restartCapturePipeline() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { restartCapturePipeline() }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPipelineRestartMs < 500L) return

        OverlayService.addLog("CAPTURE_PIPELINE_RESTART")
        lastPipelineRestartMs = now
        continuousNullImages = 0
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        if (projection != null) {
            createCapturePipeline()
        }
    }

    private fun startScanning() {
        if (scanRunning) return
        scanRunning = true
        scanBusy = false
        lastFrameMs = System.currentTimeMillis()
        continuousNullImages = 0
        handler.removeCallbacks(scanRunnable)
        OverlayService.addLog("START OCR")
        scanRunnable.run()
    }

    private fun stopScanning(logStop: Boolean) {
        val wasRunning = scanRunning || scanBusy
        scanRunning = false
        scanBusy = false
        handler.removeCallbacks(scanRunnable)
        if (logStop && wasRunning) {
            OverlayService.addLog("STOP OCR")
        }
        AutoScrollAccessibilityService.instance?.stopBackendAutoScroll()
    }

    private fun scheduleNextScan() {
        if (!scanRunning) return
        handler.postDelayed(scanRunnable, CAPTURE_INTERVAL_MS)
    }

    private fun scanLoop() {
        if (!scanRunning) return
        val tickMs = System.currentTimeMillis()
        OverlayService.addLog("CAPTURE_TICK ts=$tickMs")
        if (scanBusy) {
            return
        }

        val scanId = ++nextScanId
        activeScanId = scanId
        scanBusy = true
        captureAndOcr(scanId, tickMs)
    }

    private fun captureAndOcr(scanId: Long, captureStartMs: Long) {
        if (!scanRunning) {
            finishCapture(scanId)
            return
        }

        try {
            val reader = imageReader ?: run {
                restartCapturePipeline()
                finishCapture(scanId)
                return
            }

            val image = reader.acquireLatestImage() ?: run {
                continuousNullImages++
                OverlayService.addLog("IMAGE_NULL id=$scanId")
                val noCaptureForMs = System.currentTimeMillis() - lastFrameMs
                if (continuousNullImages >= MAX_CONTINUOUS_NULL_IMAGES || noCaptureForMs > NO_CAPTURE_RESTART_MS) {
                    restartCapturePipeline()
                }
                finishCapture(scanId)
                return
            }
            OverlayService.addLog("IMAGE_ACQUIRED id=$scanId")
            val captureWidth = image.width
            val captureHeight = image.height

            lastFrameMs = System.currentTimeMillis()
            continuousNullImages = 0
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

            val prefs = getSharedPreferences("ocr_box", MODE_PRIVATE)

            val savedX = prefs.getInt("x", 120)
            val savedY = prefs.getInt("y", 220)
            val savedW = prefs.getInt("w", 600)
            val savedH = prefs.getInt("h", 600)

            val x = savedX.coerceAtLeast(0).coerceAtMost(captureWidth - 1)
            val y = savedY.coerceAtLeast(0).coerceAtMost(captureHeight - 1)
            val w = savedW.coerceAtLeast(1).coerceAtMost(captureWidth - x)
            val h = savedH.coerceAtLeast(1).coerceAtMost(captureHeight - y)

            val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
            val bos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 55, bos)
            val bytes = bos.toByteArray()
            OverlayService.addLog("CAPTURE_DONE ms=${System.currentTimeMillis() - captureStartMs} bytes=${bytes.size}")
            uploadCropDebug(bytes, scanId) { finishCapture(scanId) }

        } catch (e: Exception) {
            OverlayService.addLog("UPLOAD_ERROR error=${e.message ?: "unknown"}")
            finishCapture(scanId)
        }
    }

    private fun finishCapture(scanId: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { finishCapture(scanId) }
            return
        }

        if (scanId != null && scanId != activeScanId) return
        scanBusy = false
        if (scanRunning) {
            OverlayService.addLog("NEXT_CAPTURE_ALLOWED")
            scheduleNextScan()
        }
    }

    private fun handleBackendAutoScrollDecision(responseText: String, onComplete: () -> Unit) {
        val decision = parseAutoScrollDecision(responseText)
        OverlayService.addLog(
            "BACKEND_DECISION shouldScroll=${decision.shouldScroll} " +
                "endReached=${decision.endReached} direction=${decision.direction} reason=${decision.reason}"
        )

        if (!autoScrollEnabled) {
            OverlayService.addLog("OCR_ONLY_MODE_NO_SCROLL")
            OverlayService.addLog("AUTO_SCROLL_SKIP reason=ocr_only_mode")
            onComplete()
            return
        }

        if (decision.endReached) {
            OverlayService.addLog("AUTO_SCROLL_END_REACHED")
            OverlayService.addLog("AUTO_SCROLL_SKIP reason=backend_endReached_true backendReason=${decision.reason}")
            autoScrollEnabled = false
            AutoScrollAccessibilityService.instance?.stopBackendAutoScroll()
            onComplete()
            return
        }

        if (!decision.shouldScroll) {
            OverlayService.addLog("AUTO_SCROLL_SKIP reason=backend_shouldScroll_false backendReason=${decision.reason}")
            onComplete()
            return
        }

        val service = AutoScrollAccessibilityService.instance
        if (service == null) {
            OverlayService.addLog("AUTO_SCROLL_PERMISSION_MISSING")
            OverlayService.addLog("AUTO_SCROLL_SKIP reason=accessibility_service_missing")
            onComplete()
            return
        }

        OverlayService.addLog("AUTO_SCROLL_EXECUTE requested direction=${decision.direction}")
        service.performBackendSwipe(direction = decision.direction) {
            onComplete()
        }
    }

    private fun parseAutoScrollDecision(responseText: String): AutoScrollDecision {
        return try {
            val root = JSONObject(responseText)
            val autoScroll = root.optJSONObject("autoScroll")
                ?: root.optJSONObject("auto_scroll")
                ?: root

            val source = when {
                root.has("autoScroll") -> "autoScroll"
                root.has("auto_scroll") -> "auto_scroll"
                root.has("shouldScroll") || root.has("should_scroll") -> "root"
                else -> "missing_autoScroll"
            }
            val parsedReason = optFlexibleString(autoScroll, "reason", "none").ifBlank { "none" }

            AutoScrollDecision(
                shouldScroll = optFlexibleBoolean(autoScroll, "shouldScroll", "should_scroll", false),
                endReached = optFlexibleBoolean(autoScroll, "endReached", "end_reached", false),
                direction = normalizeScrollDirection(
                    optFlexibleString(autoScroll, "direction", "scrollDirection", "scroll_direction")
                ),
                reason = if (source == "missing_autoScroll" && parsedReason == "none") source else parsedReason,
                source = source
            )
        } catch (e: Exception) {
            AutoScrollDecision(
                shouldScroll = false,
                endReached = false,
                direction = "BOTTOM_TO_TOP",
                reason = "parse_error",
                source = "parse_error"
            )
        }
    }

    private fun optFlexibleBoolean(
        obj: JSONObject,
        primaryKey: String,
        fallbackKey: String,
        defaultValue: Boolean
    ): Boolean {
        val key = when {
            obj.has(primaryKey) -> primaryKey
            obj.has(fallbackKey) -> fallbackKey
            else -> return defaultValue
        }
        val value = obj.opt(key) ?: return defaultValue
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) ||
                value == "1" ||
                value.equals("yes", ignoreCase = true)
            else -> defaultValue
        }
    }

    private fun optFlexibleString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (obj.has(key)) return obj.optString(key, "")
        }
        return ""
    }

    private fun normalizeScrollDirection(rawDirection: String): String {
        val normalized = rawDirection.trim()
            .uppercase(java.util.Locale.US)
            .replace("-", "_")
        return when (normalized) {
            "TOP_TO_BOTTOM", "DOWN", "SCROLL_DOWN" -> "TOP_TO_BOTTOM"
            else -> "BOTTOM_TO_TOP"
        }
    }

    private fun startUploads(uploadTasks: List<(() -> Unit) -> Unit>, scanId: Long) {
        uploadTasks.forEach { startUpload ->
            try {
                startUpload { finishCapture(scanId) }
            } catch (_: Exception) {
                OverlayService.addLog("UPLOAD_FAIL error=queue")
                finishCapture(scanId)
            }
        }
    }

    private fun parseSlotHeaders(text: String): List<Int> {
        return text.lines().mapNotNull { line ->
            val cleaned = line.trim()
                .replace("O", "0")
                .replace("o", "0")
                .replace(Regex("""[^0-9]"""), "")

            if (cleaned.matches(Regex("""^\d{1,2}$"""))) {
                cleaned.toIntOrNull()
            } else {
                null
            }
        }.take(10)
    }

private fun parseStructuredScoreboard(
        result: com.google.mlkit.vision.text.Text,
        cropW: Int,
        cropH: Int
    ): List<PlayerData> {
        val ignore = listOf(
            "players", "safe", "zone", "bermuda", "game", "hp", "ep",
            "alive", "spectating", "permission", "projection", "starting",
            "debug", "sent", "capture", "crop", "scale", "ocr", "length",
            "booyah", "paid", "app", "ok", "final", "round", "service",
            "destroyed", "stopped", "tick"
        )

        val slots = mutableListOf<Pair<Int, Int>>()   // value, y
        val names = mutableListOf<Pair<String, Int>>() // name, y
        val kills = mutableListOf<Pair<Int, Int>>()   // value, y

        fun cleanNumber(raw: String): Int? {
            val cleaned = raw.trim()
                .replace("O", "0")
                .replace("o", "0")
                .replace("I", "1")
                .replace("l", "1")
                .replace("|", "1")
                .replace(Regex("""[^0-9]"""), "")

            if (!cleaned.matches(Regex("""^\d{1,2}$"""))) return null
            val n = cleaned.toIntOrNull() ?: return null
            return if (n in 0..99) n else null
        }

        fun cleanName(raw: String): String {
            return raw.trim()
                .replace("|", " ")
                .replace(">", "")
                .replace(")", "")
                .replace("(", "")
                .replace(Regex("""\s+"""), " ")
                .replace(Regex("""[^A-Za-z0-9_ .!'₹-]"""), "")
                .trim()
        }

        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cx = box.centerX()
                val cy = box.centerY()
                val raw = line.text.trim()
                val lower = raw.lowercase()

                if (raw.isBlank()) continue
                if (ignore.any { lower.contains(it) }) continue

                // Slot/group numbers: left side only
                val slotNum = cleanNumber(raw)
                if (slotNum != null && cx < cropW * 0.38f) {
                    slots.add(slotNum to cy)
                    continue
                }

                // Kill numbers: right side only, use elements for accurate x/y
                for (el in line.elements) {
                    val eb = el.boundingBox ?: continue
                    if (eb.centerX() > cropW * 0.84f) {
                        val n = cleanNumber(el.text)
                        if (n != null) {
                            kills.add(n to eb.centerY())
                        }
                    }
                }

                // Player name: middle/left text lines with letters
                val name = cleanName(raw)
                if (
                    name.length >= 3 &&
                    name.any { it.isLetter() } &&
                    cx < cropW * 0.82f &&
                    !name.all { it.isDigit() }
                ) {
                    names.add(name to cy)
                }
            }
        }

        val sortedSlots = slots.sortedBy { it.second }
        val sortedNames = names
            .sortedBy { it.second }
            .distinctBy { it.first + "_" + (it.second / 12) }
            .take(20)

        val sortedKills = kills
            .sortedBy { it.second }
            .distinctBy { it.first.toString() + "_" + (it.second / 12) }

        val players = mutableListOf<PlayerData>()
        val yLimit = maxOf(55, cropH / 22)

        sortedNames.forEachIndexed { index, item ->
            val name = item.first
            val nameY = item.second

            val slot = sortedSlots
                .lastOrNull { it.second < nameY - 8 }
                ?.first
                ?: players.lastOrNull()?.slot
                ?: 0

            val nearestKill = sortedKills.minByOrNull {
                kotlin.math.abs(it.second - nameY)
            }

            val kill = if (
                nearestKill != null &&
                kotlin.math.abs(nearestKill.second - nameY) <= yLimit
            ) {
                nearestKill.first
            } else {
                0
            }

            players.add(PlayerData(slot, name, kill))
        }

        return players.take(20)
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
        return text.lines().mapNotNull { line ->
            val cleaned = line.trim()
                .replace("O", "0")
                .replace("o", "0")
                .replace("I", "1")
                .replace("l", "1")
                .replace("|", "1")
                .replace(Regex("""[^0-9]"""), "")

            if (cleaned.matches(Regex("""^\d{1,2}$"""))) {
                cleaned.toIntOrNull()
            } else {
                null
            }
        }
    }

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



    private fun preprocessDigitRow(src: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, src.width * 6, src.height * 6, false)
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)

        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val c = scaled.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val gray = (r * 0.3 + g * 0.59 + b * 0.11).toInt()
                val v = if (gray > 105) 255 else 0
                out.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return out
    }

    private fun upscaleBitmap(src: Bitmap, scale: Int): Bitmap {
        return Bitmap.createScaledBitmap(src, src.width * scale, src.height * scale, false)
    }


    private fun uploadRowDebug(rowId: Int, bitmap: Bitmap) {
        try {
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "row_$rowId.png",
                    bos.toByteArray().toRequestBody("image/png".toMediaType())
                )
                .build()

            val url = apiUrl.replace("/api/gemini-scan", "/api/row-debug/$rowId")
            val req = Request.Builder().url(url).post(body).build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
        }
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

            val url = apiUrl.replace("/api/gemini-scan", "/api/kill-debug")
            val req = Request.Builder().url(url).post(body).build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
        }
    }

    private fun uploadCropDebug(bytes: ByteArray, activeScanId: Long, onComplete: () -> Unit) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "crop_debug.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val scanId = System.currentTimeMillis().toString()
            val uploadStartMs = System.currentTimeMillis()
            OverlayService.addLog("UPLOAD_START id=$scanId ts=$uploadStartMs bytes=${bytes.size}")
            OverlayService.addLog("UPLOAD_WAIT_RESPONSE")
            enqueueCropUpload(
                reqBody = body,
                scanId = scanId,
                activeScanId = activeScanId,
                uploadStartMs = uploadStartMs,
                bytesSize = bytes.size,
                attempt = 1,
                onComplete = onComplete
            )
        } catch (e: Exception) {
            OverlayService.addLog("UPLOAD_ERROR error=${e.message ?: "unknown"}")
            onComplete()
        }
    }

    private fun enqueueCropUpload(
        reqBody: RequestBody,
        scanId: String,
        activeScanId: Long,
        uploadStartMs: Long,
        bytesSize: Int,
        attempt: Int,
        onComplete: () -> Unit
    ) {
        val attemptStartMs = System.currentTimeMillis()
        OverlayService.addLog("UPLOAD_ATTEMPT id=$scanId attempt=$attempt ts=$attemptStartMs")

        val req = Request.Builder()
            .url(apiUrl)
            .addHeader("X-Scan-Id", scanId)
            .addHeader("X-Upload-Attempt", attempt.toString())
            .post(reqBody)
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                val totalMs = System.currentTimeMillis() - uploadStartMs
                OverlayService.addLog("UPLOAD_ERROR id=$scanId attempt=$attempt totalMs=$totalMs error=${e.message ?: "unknown"}")
                retryOrFinishUpload(
                    reqBody = reqBody,
                    scanId = scanId,
                    activeScanId = activeScanId,
                    uploadStartMs = uploadStartMs,
                    bytesSize = bytesSize,
                    attempt = attempt,
                    responseText = "",
                    finalReason = "upload_error",
                    onComplete = onComplete
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val headerMs = System.currentTimeMillis() - attemptStartMs
                val code = response.code
                val bodyStartMs = System.currentTimeMillis()
                val responseText = try {
                    response.body?.string() ?: ""
                } catch (e: Exception) {
                    ""
                }
                val bodyReadMs = System.currentTimeMillis() - bodyStartMs
                response.close()
                val totalMs = System.currentTimeMillis() - uploadStartMs

                OverlayService.addLog("UPLOAD_HEADERS id=$scanId attempt=$attempt headerMs=$headerMs code=$code")
                OverlayService.addLog("UPLOAD_BODY id=$scanId attempt=$attempt bodyReadMs=$bodyReadMs respBytes=${responseText.length}")

                if (code in 200..299) {
                    OverlayService.addLog("UPLOAD_DONE id=$scanId attempt=$attempt totalMs=$totalMs code=$code bytes=$bytesSize")
                    handler.post {
                        if (activeScanId == this@ScreenCaptureService.activeScanId) {
                            handleBackendAutoScrollDecision(responseText, onComplete)
                        }
                    }
                } else {
                    OverlayService.addLog("UPLOAD_HTTP_RETRYABLE id=$scanId attempt=$attempt totalMs=$totalMs code=$code")
                    retryOrFinishUpload(
                        reqBody = reqBody,
                        scanId = scanId,
                        activeScanId = activeScanId,
                        uploadStartMs = uploadStartMs,
                        bytesSize = bytesSize,
                        attempt = attempt,
                        responseText = responseText,
                        finalReason = "http_$code",
                        onComplete = onComplete
                    )
                }
            }
        })
    }

    private fun retryOrFinishUpload(
        reqBody: RequestBody,
        scanId: String,
        activeScanId: Long,
        uploadStartMs: Long,
        bytesSize: Int,
        attempt: Int,
        responseText: String,
        finalReason: String,
        onComplete: () -> Unit
    ) {
        if (attempt < MAX_UPLOAD_ATTEMPTS) {
            val nextAttempt = attempt + 1
            OverlayService.addLog("UPLOAD_RETRY id=$scanId nextAttempt=$nextAttempt ts=${System.currentTimeMillis()} reason=$finalReason")
            handler.postDelayed({
                if (activeScanId == this@ScreenCaptureService.activeScanId && scanRunning) {
                    enqueueCropUpload(
                        reqBody = reqBody,
                        scanId = scanId,
                        activeScanId = activeScanId,
                        uploadStartMs = uploadStartMs,
                        bytesSize = bytesSize,
                        attempt = nextAttempt,
                        onComplete = onComplete
                    )
                }
            }, UPLOAD_RETRY_DELAY_MS)
            return
        }

        val totalMs = System.currentTimeMillis() - uploadStartMs
        OverlayService.addLog("UPLOAD_FINAL_FAIL id=$scanId attempts=$attempt totalMs=$totalMs reason=$finalReason")
        handler.post {
            if (activeScanId == this@ScreenCaptureService.activeScanId) {
                if (autoScrollEnabled) {
                    OverlayService.addLog("AUTO_SCROLL_SKIP reason=$finalReason")
                }
                handleBackendAutoScrollDecision(responseText, onComplete)
            }
        }
    }

    private fun sendDebug(text: String, x: Int, y: Int, w: Int, h: Int, onComplete: () -> Unit = {}) {
        try {
            val root = JSONObject()
            root.put("ocr_text", text.take(2000))
            root.put("players", JSONArray())
            root.put("box_x", x)
            root.put("box_y", y)
            root.put("box_w", w)
            root.put("box_h", h)

            val debugUrl = apiUrl.replace("/api/gemini-scan", "/api/ocr-debug")
            val body = root.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(debugUrl).post(body).build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    OverlayService.addLog("UPLOAD_FAIL error=${e.message ?: "unknown"}")
                    onComplete()
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    response.close()
                    OverlayService.addLog("UPLOAD_OK code=$code")
                    onComplete()
                }
            })
        } catch (_: Exception) {
            OverlayService.addLog("UPLOAD_FAIL error=queue")
            onComplete()
        }
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

private fun sendPlayers(players: List<PlayerData>, onComplete: () -> Unit = {}) {
        try {
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
                    OverlayService.addLog("UPLOAD_FAIL error=${e.message ?: "unknown"}")
                    onComplete()
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    response.close()
                    OverlayService.addLog("UPLOAD_OK code=$code")
                    onComplete()
                }
            })
        } catch (_: Exception) {
            OverlayService.addLog("UPLOAD_FAIL error=queue")
            onComplete()
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
        stopScanning(logStop = true)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    data class PlayerData(val slot: Int, val name: String, val kills: Int)
    data class AutoScrollDecision(
        val shouldScroll: Boolean,
        val endReached: Boolean,
        val direction: String,
        val reason: String,
        val source: String
    )
}

data class NameY(
    val name: String,
    val y: Int
)

data class KillY(
    val kills: Int,
    val y: Int
)
