package com.raj.ffscanner

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var box: FrameLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private val minSize = 250

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("ocr_box", MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        box = FrameLayout(this)

        val border = GradientDrawable()
        border.setColor(Color.TRANSPARENT)
        border.setStroke(6, Color.WHITE)
        border.cornerRadius = 35f
        box.background = border

        val handle = TextView(this)
        handle.text = "↘"
        handle.textSize = 30f
        handle.setTextColor(Color.WHITE)
        handle.setBackgroundColor(Color.argb(80, 255, 255, 255))

        val handleParams = FrameLayout.LayoutParams(90, 90)
        handleParams.gravity = Gravity.BOTTOM or Gravity.RIGHT
        box.addView(handle, handleParams)

        params = WindowManager.LayoutParams(
            prefs.getInt("w", 600),
            prefs.getInt("h", 600),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.getInt("x", 120)
        params.y = prefs.getInt("y", 220)

        box.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        wm.updateViewLayout(box, params)
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

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startW = params.width
                        startH = params.height
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val newW = startW + (event.rawX - touchX).toInt()
                        val newH = startH + (event.rawY - touchY).toInt()

                        params.width = if (newW < minSize) minSize else newW
                        params.height = if (newH < minSize) minSize else newH

                        wm.updateViewLayout(box, params)
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

        wm.addView(box, params)
    }

    private fun saveBox() {
        prefs.edit()
            .putInt("x", params.x)
            .putInt("y", params.y)
            .putInt("w", params.width)
            .putInt("h", params.height)
            .apply()
    }

    override fun onDestroy() {
        saveBox()
        try {
            wm.removeView(box)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
