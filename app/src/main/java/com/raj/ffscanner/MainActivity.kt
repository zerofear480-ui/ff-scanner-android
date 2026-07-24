package com.raj.ffscanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val requestCode = 1001
    private lateinit var status: TextView
    private lateinit var ocrServerUrl: String
    private val settingsClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ocrServerUrl = OcrServerSettings.getUrl(this)

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

        val overlayPermissionBtn = Button(this)
        overlayPermissionBtn.text = "ALLOW FLOATING BOX PERMISSION"

        val showBoxBtn = Button(this)
        showBoxBtn.text = "SHOW OCR BOX"

        val hideBoxBtn = Button(this)
        hideBoxBtn.text = "HIDE OCR BOX"

        val startBtn = Button(this)
        startBtn.text = "START LIVE"

        val stopBtn = Button(this)
        stopBtn.text = "STOP"

        val clearBtn = Button(this)
        clearBtn.text = "CLEAR LOGS"

        val accessibilityBtn = Button(this)
        accessibilityBtn.text = "ALLOW AUTO SCROLL / ACCESSIBILITY PERMISSION"

        val settingsBtn = Button(this)
        settingsBtn.text = "OCR SERVER SETTINGS"

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
                OverlayService.showBox()
                status.text = "Status: OCR box shown"
            } else {
                status.text = "Status: Allow floating box permission first"
            }
        }

        hideBoxBtn.setOnClickListener {
            OverlayService.hideBox()
            status.text = "Status: OCR box hidden"
        }

        startBtn.setOnClickListener {
            startLive()
        }

        stopBtn.setOnClickListener {
            stopLive()
        }

        clearBtn.setOnClickListener {
            OverlayService.clearLogs()
            status.text = "Status: Logs cleared"
        }

        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            status.text = "Status: Enable FF Scanner accessibility service"
        }

        settingsBtn.setOnClickListener {
            showOcrServerSettings()
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(overlayPermissionBtn)
        layout.addView(showBoxBtn)
        layout.addView(hideBoxBtn)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        layout.addView(clearBtn)
        layout.addView(accessibilityBtn)
        layout.addView(settingsBtn)

        val spacer = Space(this)
        layout.addView(
            spacer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val version = TextView(this)
        version.text = "Version: v0.9.0 Live Async Stream"
        version.textSize = 12f
        version.setTextColor(Color.GRAY)
        version.setPadding(0, 24, 0, 0)
        layout.addView(version)

        setContentView(layout)

        if (intent?.getBooleanExtra("auto_start_live", false) == true) {
            startLive()
        } else if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
        }
    }

    private fun startLive() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Status: Allow overlay permission for crop box"
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        startService(Intent(this, OverlayService::class.java))
        OverlayService.showBox()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    private fun stopLive() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        AutoScrollAccessibilityService.instance?.stopCommandExecution()
        status.text = "Status: Live stopped"
    }

    private fun showOcrServerSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val label = TextView(this).apply {
            text = "OCR Server URL"
        }
        val urlInput = EditText(this).apply {
            setText(OcrServerSettings.getUrl(this@MainActivity))
            isSingleLine = true
        }
        val connectionStatus = TextView(this).apply {
            setPadding(0, 16, 0, 0)
        }
        content.addView(label)
        content.addView(urlInput)
        content.addView(connectionStatus)

        val dialog = AlertDialog.Builder(this)
            .setTitle("OCR Server Settings")
            .setView(content)
            .setPositiveButton("SAVE", null)
            .setNeutralButton("TEST CONNECTION", null)
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (!OcrServerSettings.isValid(url)) {
                    connectionStatus.text = "Invalid URL"
                    return@setOnClickListener
                }

                OcrServerSettings.saveUrl(this, url)
                ocrServerUrl = url
                status.text = "Status: OCR server URL saved"
                dialog.dismiss()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (!OcrServerSettings.isValid(url)) {
                    connectionStatus.text = "Invalid URL"
                    return@setOnClickListener
                }

                connectionStatus.text = "Testing..."
                testOcrServerConnection(url, dialog, connectionStatus)
            }
        }
        dialog.show()
    }

    private fun testOcrServerConnection(
        url: String,
        dialog: AlertDialog,
        connectionStatus: TextView
    ) {
        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        settingsClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (dialog.isShowing) {
                        connectionStatus.text = "❌ Failed"
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                runOnUiThread {
                    if (dialog.isShowing) {
                        connectionStatus.text = "✅ Connected"
                    }
                }
            }
        })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)

        if (req == requestCode && res == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            serviceIntent.action = ScreenCaptureService.ACTION_START_LIVE
            serviceIntent.putExtra("resultCode", res)
            serviceIntent.putExtra("data", data)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            status.text = "Status: Live crop stream running"
        } else {
            status.text = "Status: Screen capture permission denied"
        }
    }
}
