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
    private var directionDown = true
    private var swipeCount = 0

    private val scrollTask = object : Runnable {
        override fun run() {
            if (!isRunning) return

            performScoreboardSwipe()

            swipeCount++

            // 8 swipe ke baad direction reverse
            if (swipeCount >= 8) {
                swipeCount = 0
                directionDown = !directionDown
            }

            handler.postDelayed(this, 1800)
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
        directionDown = true
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

        // Free Fire scoreboard usually right side me hota hai
        val x = (screenW * 0.88f)

        val startY: Float
        val endY: Float

        if (directionDown) {
            // list ko neeche le jane ke liye finger up swipe
            startY = screenH * 0.72f
            endY = screenH * 0.42f
        } else {
            // list ko upar wapas lane ke liye finger down swipe
            startY = screenH * 0.42f
            endY = screenH * 0.72f
        }

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()

        dispatchGesture(gesture, null, null)
    }
}
