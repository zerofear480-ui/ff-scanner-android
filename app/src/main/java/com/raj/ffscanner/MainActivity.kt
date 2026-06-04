package com.raj.ffscanner

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

class MainActivity : Activity() {

    private val client = OkHttpClient()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    private var apiUrl = "http://13.204.87.106:8000/api/ocr-scan"
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var scanning = false

    private lateinit var status: TextView
    private lateinit var apiInput: EditText
    private lateinit var ocrText: TextView

    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(35, 55, 35, 35)

        val title = TextView(this)
        title.text = "FF Scanner"
        title.textSize = 28f

        status = TextView(this)
        status.text = "Status: Ready"
        status.textSize = 17f

        apiInput = EditText(this)
        apiInput.setText(apiUrl)

        val startBtn = Button(this)
        startBtn.text = "START OCR SCANNER"

        val stopBtn = Button(this)
        stopBtn.text = "STOP SCANNER"

        val testBtn = Button(this)
        testBtn.text = "SEND TEST DATA"

        ocrText = TextView(this)
        ocrText.text = "OCR output will show here"
        ocrText.textSize = 14f

        startBtn.setOnClickListener {
            apiUrl = apiInput.text.toString()
            requestCapture()
        }

        stopBtn.setOnClickListener {
            stopScanner()
        }

        testBtn.setOnClickListener {
            sendPlayers(
                listOf(
                    PlayerData(1, "Raj", 20),
                    PlayerData(2, "Aman", 5),
                    PlayerData(3, "Rohit", 9)
                )
            )
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(apiInput)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        layout.addView(testBtn)
        layout.addView(ocrText)

        setContentView(layout)
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)

        if (req == requestCode && res == RESULT_OK && data != null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(res, data)
            startScanner()
        } else {
            status.text = "Status: Screen capture denied"
        }
    }

    private fun startScanner() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "FFScanner",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        scanning = true
        
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        status.text = "Status: Scanner started"
    
        scanLoop()
    }

    private fun scanLoop() {
        if (!scanning) return

        captureAndOCR()

        handler.postDelayed({
            scanLoop()
        }, 4000)
    }

    private fun captureAndOCR() {
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

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val text = result.text
                val players = parsePlayers(text)

                ocrText.text = "Detected: ${players.size}\n\n$text"

                if (players.isNotEmpty()) {
                    sendPlayers(players)
                } else {
                    status.text = "Status: OCR done, no players parsed"
                }
            }
            .addOnFailureListener {
                status.text = "Status: OCR error ${it.message}"
            }
    }

    private fun parsePlayers(text: String): List<PlayerData> {
        val players = mutableListOf<PlayerData>()

        val regex = Regex("""^\s*(\d{1,2})\s+(.+?)\s+(\d{1,2})\s*$""")

        text.lines().forEach { line ->
            val match = regex.find(line.trim())
            if (match != null) {
                val slot = match.groupValues[1].toIntOrNull() ?: return@forEach
                val name = match.groupValues[2].trim()
                val kills = match.groupValues[3].toIntOrNull() ?: 0

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
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread {
                    status.text = "Status: Send error ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    status.text = "Status: Sent ${players.size} players - ${response.code}"
                }
                response.close()
            }
        })
    }

    private fun stopScanner() {
        scanning = false
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        
        stopService(Intent(this, ScreenCaptureService::class.java))
        status.text = "Status: Scanner stopped"
    
    }

    data class PlayerData(
        val slot: Int,
        val name: String,
        val kills: Int
    )
}
