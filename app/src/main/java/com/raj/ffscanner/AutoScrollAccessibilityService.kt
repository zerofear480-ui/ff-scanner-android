package com.raj.ffscanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AutoScrollAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoScrollAccessibilityService? = null
        var isRunning = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var directionDown = false
    private var swipeCount = 0

    private val scrollTask = object : Runnable {
        override fun run() {
            if (!isRunning) return

            performScoreboardSwipe()

            swipeCount++

            // 8 swipe ke baad direction reverse
            if (swipeCount >= 3) {
                swipeCount = 0
                directionDown = !directionDown
            }

            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopAutoScroll()
        instance = null
    }

    fun startAutoScroll() {
        if (isRunning) return
        isRunning = true
        directionDown = false
        swipeCount = 0
        handler.post(scrollTask)
    }

    fun stopAutoScroll() {
        isRunning = false
        handler.removeCallbacks(scrollTask)
    }

    private fun performScoreboardSwipe() {
        val display = resources.displayMetrics
        val screenW = display.widthPixels
        val screenH = display.heightPixels
        val rect = OverlayService.getBoxRect()

        val x: Float
        val startY: Float
        val endY: Float

        if (rect != null && rect.width() > 30 && rect.height() > 30) {
            x = rect.centerX().toFloat()
            val topY = rect.top + rect.height() * 0.18f
            val bottomY = rect.bottom - rect.height() * 0.18f

            if (!directionDown) {
                startY = bottomY
                endY = topY
            } else {
                startY = topY
                endY = bottomY
            }
        } else {
            x = screenW * 0.88f
            if (!directionDown) {
                startY = screenH * 0.72f
                endY = screenH * 0.42f
            } else {
                startY = screenH * 0.42f
                endY = screenH * 0.72f
            }
        }

        OverlayService.addLog("AUTO_SCROLL ${if (!directionDown) "BOTTOM_TO_TOP" else "TOP_TO_BOTTOM"} ${swipeCount + 1}/3")

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()

        dispatchGesture(gesture, null, null)
    }
}
