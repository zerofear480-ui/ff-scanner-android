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
    private var backendSwipeRunning = false

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
        OverlayService.setOcrBoxTouchEnabled(true)
    }

    fun stopBackendAutoScroll() {
        backendSwipeRunning = false
        OverlayService.setOcrBoxTouchEnabled(true)
    }

    fun performBackendSwipeUp(onComplete: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { performBackendSwipeUp(onComplete) }
            return
        }

        if (backendSwipeRunning) {
            OverlayService.addLog("AUTO_SCROLL_SKIP reason=swipe_in_progress")
            onComplete()
            return
        }

        backendSwipeRunning = true
        performScoreboardSwipe(
            direction = "BOTTOM_TO_TOP",
            completeOnGesture = true,
            onComplete = {
                backendSwipeRunning = false
                onComplete()
            }
        )
    }

    private fun performScoreboardSwipe() {
        val direction = if (!directionDown) "BOTTOM_TO_TOP" else "TOP_TO_BOTTOM"
        performScoreboardSwipe(
            direction = direction,
            completeOnGesture = false,
            onComplete = {}
        )
    }

    private fun performScoreboardSwipe(
        direction: String,
        completeOnGesture: Boolean,
        onComplete: () -> Unit
    ) {
        val rect = OverlayService.getBoxRect()
        val step = swipeCount + 1

        if (rect == null || rect.width() <= 30 || rect.height() <= 30) {
            OverlayService.addLog("AUTO_SCROLL_RECT x=0 y=0 w=0 h=0")
            OverlayService.addLog("AUTO_SCROLL_DISPATCH ok=false direction=$direction step=$step/3")
            if (completeOnGesture) {
                onComplete()
            } else {
                scheduleNextSwipe()
            }
            return
        }

        OverlayService.addLog("AUTO_SCROLL_RECT x=${rect.left} y=${rect.top} w=${rect.width()} h=${rect.height()}")

        val x = rect.centerX().toFloat()
        val topY = rect.top + rect.height() * 0.10f
        val bottomY = rect.bottom - rect.height() * 0.10f
        val startY = if (direction == "BOTTOM_TO_TOP") bottomY else topY
        val endY = if (direction == "BOTTOM_TO_TOP") topY else bottomY

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()

        OverlayService.addLog("AUTO_SCROLL_TOUCH_MODE=UNDERLAY")
        OverlayService.setOcrBoxTouchEnabled(false)

        handler.postDelayed({
            if (!isRunning && !completeOnGesture) {
                OverlayService.setOcrBoxTouchEnabled(true)
                return@postDelayed
            }

            val ok = try {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        OverlayService.addLog("AUTO_SCROLL_GESTURE_DONE")
                        OverlayService.setOcrBoxTouchEnabled(true)
                        if (completeOnGesture) {
                            onComplete()
                        } else {
                            advanceDirection()
                            scheduleNextSwipe()
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        OverlayService.addLog("AUTO_SCROLL_GESTURE_CANCELLED")
                        OverlayService.setOcrBoxTouchEnabled(true)
                        if (completeOnGesture) {
                            onComplete()
                        } else {
                            advanceDirection()
                            scheduleNextSwipe()
                        }
                    }
                }, null)
            } catch (e: Exception) {
                OverlayService.addLog("AUTO_SCROLL_DISPATCH_ERROR error=${e.message ?: "unknown"}")
                false
            }
            OverlayService.addLog("AUTO_SCROLL_DISPATCH ok=$ok direction=$direction step=$step/3")

            if (!ok) {
                OverlayService.setOcrBoxTouchEnabled(true)
                if (completeOnGesture) {
                    onComplete()
                } else {
                    advanceDirection()
                    scheduleNextSwipe()
                }
            }
        }, 50)
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
