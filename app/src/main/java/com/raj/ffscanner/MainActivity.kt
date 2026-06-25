package com.raj.ffscanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.widget.*

class MainActivity : Activity() {
    private var pendingAutoScrollFromOverlay = false


    private val requestCode = 1001
    private lateinit var status: TextView
    private lateinit var apiInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }

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
        apiInput.setText("http://13.203.102.124:8000/api/ocr-scan")

        val overlayPermissionBtn = Button(this)
        overlayPermissionBtn.text = "ALLOW FLOATING BOX PERMISSION"

        val showBoxBtn = Button(this)
        showBoxBtn.text = "SHOW OCR BOX"

        val hideBoxBtn = Button(this)
        hideBoxBtn.text = "HIDE OCR BOX"

        val startBtn = Button(this)
        startBtn.text = "START OCR SCANNER"

        val stopBtn = Button(this)
        stopBtn.text = "STOP SCANNER"

        val accessibilityBtn = Button(this)
        accessibilityBtn.text = "ALLOW AUTO SCROLL PERMISSION"

        val startScrollBtn = Button(this)
        startScrollBtn.text = "START AUTO SCROLL"

        val stopScrollBtn = Button(this)
        stopScrollBtn.text = "STOP AUTO SCROLL"

        overlayPermissionBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                status.text = "Status: Floating box permission already allowed"
            }
        }

        showBoxBtn.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, OverlayService::class.java))
                status.text = "Status: OCR box shown"
            } else {
                status.text = "Status: Allow floating box permission first"
            }
        }

        hideBoxBtn.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            status.text = "Status: OCR box hidden"
        }

        startBtn.setOnClickListener {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            status.text = "Status: Scanner stopped"
        }

        accessibilityBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            status.text = "Status: Enable FF Scanner auto scroll service"
        }

        startScrollBtn.setOnClickListener {
            val service = AutoScrollAccessibilityService.instance
            if (service == null) {
                status.text = "Status: Enable auto scroll permission first"
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                service.startAutoScroll()
                status.text = "Status: Auto scroll started"
            }
        }

        stopScrollBtn.setOnClickListener {
            AutoScrollAccessibilityService.instance?.stopAutoScroll()
            status.text = "Status: Auto scroll stopped"
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(apiInput)
        layout.addView(overlayPermissionBtn)
        layout.addView(showBoxBtn)
        layout.addView(hideBoxBtn)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        layout.addView(accessibilityBtn)
        layout.addView(startScrollBtn)
        layout.addView(stopScrollBtn)

        setContentView(layout)

        pendingAutoScrollFromOverlay = intent?.getBooleanExtra("auto_start_scroll", false) == true

        if (intent?.getBooleanExtra("auto_start_ocr", false) == true) {
            status.text = "Status: Auto starting OCR..."
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)

        if (req == requestCode && res == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            serviceIntent.action = ScreenCaptureService.ACTION_START_OCR
            serviceIntent.putExtra("resultCode", res)
            serviceIntent.putExtra("data", data)
            serviceIntent.putExtra("apiUrl", apiInput.text.toString())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
        startPendingAutoScrollIfNeeded()
        // AUTO_SCROLL_HELPER_CALL_ADDED
            }

            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, OverlayService::class.java))
            }

            status.text = "Status: Scanner running"
        } else {
            status.text = "Status: Permission denied"
        }
    }

    private fun startPendingAutoScrollIfNeeded() {
        if (!pendingAutoScrollFromOverlay) return
        val svc = AutoScrollAccessibilityService.instance
        if (svc != null) {
            svc.startAutoScroll()
            pendingAutoScrollFromOverlay = false
        }
    }

}
