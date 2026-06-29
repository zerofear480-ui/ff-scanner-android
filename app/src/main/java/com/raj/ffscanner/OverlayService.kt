package com.raj.ffscanner

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OverlayService : Service() {
    companion object {
        var instance: OverlayService? = null
        private val logs = mutableListOf<String>()

        fun getBoxRect(): Rect? {
            val svc = instance ?: return null
            if (!svc.boxAdded) return null
            return try {
                val loc = IntArray(2)
                svc.box.getLocationOnScreen(loc)
                val width = if (svc.box.width > 0) svc.box.width else svc.boxParams.width
                val height = if (svc.box.height > 0) svc.box.height else svc.boxParams.height
                Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
            } catch (_: Exception) {
                null
            }
        }

        fun setOcrBoxTouchEnabled(enabled: Boolean) {
            instance?.setOcrBoxTouchEnabledInternal(enabled)
        }

        fun showBox() {
            instance?.showBoxInternal()
        }

        fun hideBox() {
            instance?.hideBoxInternal()
        }

        fun addLog(msg: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            synchronized(logs) {
                logs.add("[$time] $msg")
                if (logs.size > 250) logs.removeAt(0)
            }
        }

        fun clearLogs() {
            synchronized(logs) {
                logs.clear()
            }
            instance?.refreshLogs()
        }

        fun logText(): String {
            return synchronized(logs) {
                logs.joinToString("\n")
            }
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
    private var boxAdded = false
    private var panelAdded = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        createCropBox()
        createControlPanel()
    }

    private fun createCropBox() {
        val prefs = getSharedPreferences("ocr_box", MODE_PRIVATE)

        box = FrameLayout(this)

        val border = GradientDrawable()
        border.setColor(Color.TRANSPARENT)
        border.setStroke(6, Color.WHITE)
        border.cornerRadius = 24f
        box.background = border

        val handle = TextView(this)
        handle.text = "+"
        handle.textSize = 26f
        handle.gravity = Gravity.CENTER
        handle.setTextColor(Color.WHITE)
        handle.setBackgroundColor(Color.argb(90, 255, 255, 255))

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
                        boxParams.width = maxOf(minSize, newW)
                        boxParams.height = maxOf(minSize, newH)
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
        boxAdded = true
    }

    private fun createControlPanel() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(12, 12, 12, 12)
        panel.setBackgroundColor(Color.argb(185, 0, 0, 0))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val showBtn = Button(this)
        showBtn.text = "SHOW OCR BOX"

        val hideBtn = Button(this)
        hideBtn.text = "HIDE OCR BOX"

        val startBtn = Button(this)
        startBtn.text = "START LIVE"

        val stopBtn = Button(this)
        stopBtn.text = "STOP"

        val clearBtn = Button(this)
        clearBtn.text = "CLEAR LOGS"

        row.addView(showBtn)
        row.addView(hideBtn)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(startBtn)
        row2.addView(stopBtn)
        row2.addView(clearBtn)

        logView = TextView(this)
        logView.textSize = 13f
        logView.setTextColor(Color.WHITE)
        logView.text = logText()
        logView.setPadding(8, 8, 8, 8)

        panel.addView(row)
        panel.addView(row2)

        val logScroll = ScrollView(this)
        logScroll.addView(logView)
        panel.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                520
            )
        )

        panelParams = WindowManager.LayoutParams(
            760,
            700,
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

        showBtn.setOnClickListener {
            showBoxInternal()
            addLog("SHOW_OCR_BOX")
        }

        hideBtn.setOnClickListener {
            hideBoxInternal()
            addLog("HIDE_OCR_BOX")
        }

        startBtn.setOnClickListener {
            addLog("LIVE_START_CLICK")
            showBoxInternal()
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("auto_start_live", true)
            startActivity(intent)
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            AutoScrollAccessibilityService.instance?.stopCommandExecution()
            addLog("LIVE_STOP_CLICK")
        }

        clearBtn.setOnClickListener {
            clearLogs()
        }

        wm.addView(panel, panelParams)
        panelAdded = true

        handler.post(object : Runnable {
            override fun run() {
                refreshLogs()
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun refreshLogs() {
        if (::logView.isInitialized) {
            logView.text = logText()
        }
    }

    private fun setOcrBoxTouchEnabledInternal(enabled: Boolean) {
        if (!boxAdded) return
        val notTouchableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val touchDisabled = (boxParams.flags and notTouchableFlag) != 0

        if (enabled && touchDisabled) {
            boxParams.flags = boxParams.flags and notTouchableFlag.inv()
        } else if (!enabled && !touchDisabled) {
            boxParams.flags = boxParams.flags or notTouchableFlag
        } else {
            addLog(if (enabled) "OVERLAY_TOUCH_ENABLED" else "OVERLAY_TOUCH_DISABLED")
            return
        }

        try {
            wm.updateViewLayout(box, boxParams)
            addLog(if (enabled) "OVERLAY_TOUCH_ENABLED" else "OVERLAY_TOUCH_DISABLED")
        } catch (e: Exception) {
            addLog("OVERLAY_TOUCH_UPDATE_FAILED error=${e.message ?: "unknown"}")
        }
    }

    private fun showBoxInternal() {
        if (!::box.isInitialized || !::panel.isInitialized) return
        try {
            if (!boxAdded) {
                wm.addView(box, boxParams)
                boxAdded = true
                addLog("OCR_BOX_SHOWN")
            }
            if (!panelAdded) {
                wm.addView(panel, panelParams)
                panelAdded = true
                addLog("OVERLAY_UI_SHOWN")
            }
        } catch (e: Exception) {
            addLog("OCR_BOX_SHOW_FAILED error=${e.message ?: "unknown"}")
        }
    }

    private fun hideBoxInternal() {
        if (!::box.isInitialized || !::panel.isInitialized) return
        if (boxAdded) saveBox()
        try {
            if (boxAdded) {
                wm.removeView(box)
                boxAdded = false
                addLog("OCR_BOX_HIDDEN")
            }
            if (panelAdded) {
                wm.removeView(panel)
                panelAdded = false
                addLog("OVERLAY_UI_HIDDEN")
            }
        } catch (e: Exception) {
            addLog("OCR_BOX_HIDE_FAILED error=${e.message ?: "unknown"}")
        }
    }

    private fun saveBox() {
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val realW = box.width
        val realH = box.height
        if (realW < minSize || realH < minSize) return

        getSharedPreferences("ocr_box", MODE_PRIVATE).edit()
            .putInt("x", loc[0])
            .putInt("y", loc[1])
            .putInt("w", realW)
            .putInt("h", realH)
            .putInt("screen_w", metrics.widthPixels)
            .putInt("screen_h", metrics.heightPixels)
            .apply()
    }

    override fun onDestroy() {
        if (boxAdded) saveBox()
        try { if (boxAdded) wm.removeView(box) } catch (_: Exception) {}
        try { if (panelAdded) wm.removeView(panel) } catch (_: Exception) {}
        super.onDestroy()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
