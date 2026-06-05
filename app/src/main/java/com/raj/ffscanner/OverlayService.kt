package com.raj.ffscanner

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
        val logs = mutableListOf<String>()

        fun addLog(msg: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logs.add(0, "[$time] $msg")
            if (logs.size > 25) logs.removeAt(logs.size - 1)
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
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        createOcrBox()
        createControlPanel()

        addLog("Overlay opened")
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
                        addLog("Box saved x=${boxParams.x} y=${boxParams.y} w=${boxParams.width} h=${boxParams.height}")
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
                        addLog("Box resized w=${boxParams.width} h=${boxParams.height}")
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

        val startBtn = Button(this)
        startBtn.text = "START OCR"

        val stopBtn = Button(this)
        stopBtn.text = "STOP"

        val clearBtn = Button(this)
        clearBtn.text = "CLEAR"

        row.addView(startBtn)
        row.addView(stopBtn)
        row.addView(clearBtn)

        logView = TextView(this)
        logView.textSize = 11f
        logView.setTextColor(Color.WHITE)
        logView.text = "Logs..."
        logView.setPadding(8, 8, 8, 8)

        panel.addView(row)
        panel.addView(logView)

        panelParams = WindowManager.LayoutParams(
            650,
            420,
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
            addLog("Use app START OCR button first")
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            addLog("OCR stopped")
        }

        clearBtn.setOnClickListener {
            logs.clear()
            addLog("Logs cleared")
        }

        wm.addView(panel, panelParams)

        handler.post(object : Runnable {
            override fun run() {
                logView.text = logs.joinToString("\n")
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun saveBox() {
        getSharedPreferences("ocr_box", MODE_PRIVATE).edit()
            .putInt("x", boxParams.x)
            .putInt("y", boxParams.y)
            .putInt("w", boxParams.width)
            .putInt("h", boxParams.height)
            .apply()
    }

    override fun onDestroy() {
        saveBox()
        try { wm.removeView(box) } catch (_: Exception) {}
        try { wm.removeView(panel) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
