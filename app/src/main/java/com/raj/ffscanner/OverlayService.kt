package com.raj.ffscanner

import android.graphics.Rect

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*

class OverlayService : Service() {
    companion object {
        var instance: OverlayService? = null
        val logs = mutableListOf<String>()



        fun getBoxRect(): Rect? {
            val svc = instance ?: return null
            return try {
                Rect(
                    svc.boxParams.x,
                    svc.boxParams.y,
                    svc.boxParams.x + svc.boxParams.width,
                    svc.boxParams.y + svc.boxParams.height
                )
            } catch (_: Exception) {
                null
            }
        }

        fun addLog(msg: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logs.add("[$time] $msg")
            if (logs.size > 250) logs.removeAt(0)
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var box: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var boxParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val minSize = 250

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        createOcrBox()
        createControlPanel()
    }

    private fun createOcrBox() {
        val prefs = getSharedPreferences("ocr_box", MODE_PRIVATE)

        box = FrameLayout(this)

        val border = GradientDrawable()
        border.setColor(Color.TRANSPARENT)
        border.setStroke(6, Color.WHITE)
        border.cornerRadius = 35f
        box.background = border

        val handle = TextView(this)
        handle.text = "↘"
        handle.textSize = 26f
        handle.setTextColor(Color.WHITE)
        handle.setBackgroundColor(Color.argb(80, 255, 255, 255))

        val handleParams = FrameLayout.LayoutParams(85, 85)
        handleParams.gravity = Gravity.BOTTOM or Gravity.RIGHT
        box.addView(handle, handleParams)

        boxParams = WindowManager.LayoutParams(
            prefs.getInt("w", 600),
            prefs.getInt("h", 600),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        boxParams.gravity = Gravity.TOP or Gravity.START
        boxParams.x = prefs.getInt("x", 120)
        boxParams.y = prefs.getInt("y", 220)

        box.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = boxParams.x
                        startY = boxParams.y
                        touchX = e.rawX
                        touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        boxParams.x = startX + (e.rawX - touchX).toInt()
                        boxParams.y = startY + (e.rawY - touchY).toInt()
                        wm.updateViewLayout(box, boxParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveBox()
                        return true
                    }
                }
                return false
            }
        })

        handle.setOnTouchListener(object : View.OnTouchListener {
            var startW = 0
            var startH = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startW = boxParams.width
                        startH = boxParams.height
                        touchX = e.rawX
                        touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newW = startW + (e.rawX - touchX).toInt()
                        val newH = startH + (e.rawY - touchY).toInt()
                        boxParams.width = if (newW < minSize) minSize else newW
                        boxParams.height = if (newH < minSize) minSize else newH
                        wm.updateViewLayout(box, boxParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveBox()
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(box, boxParams)
    }

    private fun createControlPanel() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(12, 12, 12, 12)
        panel.setBackgroundColor(Color.argb(180, 0, 0, 0))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val ocrCheck = CheckBox(this)
        ocrCheck.text = "OCR"
        ocrCheck.setTextColor(Color.WHITE)
        ocrCheck.isChecked = true

        val autoScrollCheck = CheckBox(this)
        autoScrollCheck.text = "AUTO SCROLL"
        autoScrollCheck.setTextColor(Color.WHITE)

        val startBtn = Button(this)
        startBtn.text = "START"

        val stopBtn = Button(this)
        stopBtn.text = "STOP"

        val clearBtn = Button(this)
        clearBtn.text = "CLEAR"

        row.addView(ocrCheck)
        row.addView(autoScrollCheck)
        row.addView(startBtn)
        row.addView(stopBtn)
        row.addView(clearBtn)

        logView = TextView(this)
        logView.textSize = 13f
        logView.setTextColor(Color.WHITE)
        logView.text = "Logs..."
        logView.setPadding(8, 8, 8, 8)

        panel.addView(row)

        val logScroll = android.widget.ScrollView(this)
        logScroll.addView(logView)
        panel.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                520
            )
        )

        panelParams = WindowManager.LayoutParams(
            680,
            760,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        panelParams.gravity = Gravity.TOP or Gravity.START
        panelParams.x = 40
        panelParams.y = 40

        panel.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = panelParams.x
                        startY = panelParams.y
                        touchX = e.rawX
                        touchY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        panelParams.x = startX + (e.rawX - touchX).toInt()
                        panelParams.y = startY + (e.rawY - touchY).toInt()
                        wm.updateViewLayout(panel, panelParams)
                        return true
                    }
                }
                return false
            }
        })

        startBtn.setOnClickListener {
            val ocr = ocrCheck.isChecked
            val autoScroll = autoScrollCheck.isChecked

            if (!ocr) {
                addLog("START_BLOCKED enable OCR first")
                return@setOnClickListener
            }

            if (autoScroll && AutoScrollAccessibilityService.instance == null) {
                addLog("AUTO_SCROLL permission required")
                val accIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                accIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(accIntent)
                return@setOnClickListener
            }

            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("auto_start_ocr", true)
            intent.putExtra("auto_start_scroll", autoScroll)
            startActivity(intent)

            if (autoScroll) addLog("START mode=OCR+AUTO_SCROLL")
            else addLog("START mode=OCR_ONLY")
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
        }

        clearBtn.setOnClickListener {
            logs.clear()
            logView.text = ""
        }

        wm.addView(panel, panelParams)

        handler.post(object : Runnable {
            override fun run() {
                logView.text = logs.joinToString("\n")
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun saveBox() {
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val realX = loc[0]
        val realY = loc[1]
        val realW = box.width
        val realH = box.height

        if (realW < 250 || realH < 250) {
            return
        }

        getSharedPreferences("ocr_box", MODE_PRIVATE).edit()
            .putInt("x", realX)
            .putInt("y", realY)
            .putInt("w", realW)
            .putInt("h", realH)
            .putInt("screen_w", metrics.widthPixels)
            .putInt("screen_h", metrics.heightPixels)
            .apply()
    }

    override fun onDestroy() {
        saveBox()
        try { wm.removeView(box) } catch (_: Exception) {}
        try { wm.removeView(panel) } catch (_: Exception) {}
        super.onDestroy()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
