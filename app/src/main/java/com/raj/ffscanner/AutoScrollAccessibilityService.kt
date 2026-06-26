package com.raj.ffscanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
        OverlayService.addLog("AUTO_SCROLL_START_CALLED")
        if (isRunning) {
            OverlayService.addLog("AUTO_SCROLL_ALREADY_RUNNING")
            return
        }
        isRunning = true
        directionDown = false
        swipeCount = 0
        OverlayService.addLog("AUTO_SCROLL_RUNNING=true")
        handler.post(scrollTask)
    }

    fun stopAutoScroll() {
        isRunning = false
        handler.removeCallbacks(scrollTask)
    }

    private fun performScoreboardSwipe() {
        val rect = OverlayService.getBoxRect()
        val direction = if (!directionDown) "BOTTOM_TO_TOP" else "TOP_TO_BOTTOM"
        val step = swipeCount + 1

        if (rect == null || rect.width() <= 30 || rect.height() <= 30) {
            OverlayService.addLog("AUTO_SCROLL_RECT x=0 y=0 w=0 h=0")
            OverlayService.addLog("AUTO_SCROLL_DISPATCH ok=false direction=$direction step=$step/3")
            scheduleNextSwipe()
            return
        }

        OverlayService.addLog("AUTO_SCROLL_RECT x=${rect.left} y=${rect.top} w=${rect.width()} h=${rect.height()}")

        val x = rect.centerX().toFloat()
        val topY = rect.top + rect.height() * 0.18f
        val bottomY = rect.bottom - rect.height() * 0.18f
        val startY = if (!directionDown) bottomY else topY
        val endY = if (!directionDown) topY else bottomY

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                OverlayService.addLog("AUTO_SCROLL_GESTURE_DONE")
                OverlayService.addLog("AUTO_SCROLL_CAPTURE_AFTER_SWIPE")
                ScreenCaptureService.captureAfterAutoScrollSwipe()
                advanceDirection()
                scheduleNextSwipe()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                OverlayService.addLog("AUTO_SCROLL_GESTURE_CANCELLED")
                advanceDirection()
                scheduleNextSwipe()
            }
        }, null)
        OverlayService.addLog("AUTO_SCROLL_DISPATCH ok=$ok direction=$direction step=$step/3")

        if (!ok) {
            advanceDirection()
            scheduleNextSwipe()
        }
    }

    private fun advanceDirection() {
        swipeCount++
        if (swipeCount >= 3) {
            swipeCount = 0
            directionDown = !directionDown
        }
    }

    private fun scheduleNextSwipe() {
        if (!isRunning) return
        handler.removeCallbacks(scrollTask)
        handler.postDelayed(scrollTask, 1000)
    }
}
