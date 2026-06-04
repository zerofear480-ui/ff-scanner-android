package com.raj.ffscanner

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {

    private val requestCode = 1001
    private lateinit var status: TextView
    private lateinit var apiInput: EditText

    private var apiUrl = "http://13.204.87.106:8000/api/ocr-scan"

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

        startBtn.setOnClickListener {
            apiUrl = apiInput.text.toString()
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            status.text = "Status: Scanner stopped"
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(apiInput)
        layout.addView(startBtn)
        layout.addView(stopBtn)

        setContentView(layout)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)

        if (req == requestCode && res == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            serviceIntent.putExtra("resultCode", res)
            serviceIntent.putExtra("data", data)
            serviceIntent.putExtra("apiUrl", apiInput.text.toString())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            status.text = "Status: Scanner service started"
        } else {
            status.text = "Status: Permission denied"
        }
    }
}
