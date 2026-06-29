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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var commandRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OverlayService.addLog("ACCESSIBILITY_CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopCommandExecution()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCommandExecution()
        instance = null
    }

    fun stopCommandExecution() {
        commandRunning = false
        OverlayService.setOcrBoxTouchEnabled(true)
    }

    fun executeSwipe(
        x: Int,
        startY: Int,
        endY: Int,
        durationMs: Long,
        onComplete: () -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { executeSwipe(x, startY, endY, durationMs, onComplete) }
            return
        }

        if (commandRunning) {
            OverlayService.addLog("COMMAND_WAIT reason=gesture_in_progress")
            onComplete()
            return
        }

        commandRunning = true
        OverlayService.addLog("COMMAND_EXECUTE SWIPE x=$x y1=$startY y2=$endY")

        val path = Path().apply {
            moveTo(x.toFloat(), startY.toFloat())
            lineTo(x.toFloat(), endY.toFloat())
        }
        dispatch(path, durationMs.coerceAtLeast(1L), onComplete)
    }

    fun executeTap(
        x: Int,
        y: Int,
        durationMs: Long,
        onComplete: () -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { executeTap(x, y, durationMs, onComplete) }
            return
        }

        if (commandRunning) {
            OverlayService.addLog("COMMAND_WAIT reason=gesture_in_progress")
            onComplete()
            return
        }

        commandRunning = true
        OverlayService.addLog("COMMAND_EXECUTE TAP x=$x y=$y")

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        dispatch(path, durationMs.coerceAtLeast(1L), onComplete)
    }

    private fun dispatch(path: Path, durationMs: Long, onComplete: () -> Unit) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        OverlayService.setOcrBoxTouchEnabled(false)
        val ok = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    commandRunning = false
                    OverlayService.setOcrBoxTouchEnabled(true)
                    OverlayService.addLog("COMMAND_DONE")
                    onComplete()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    commandRunning = false
                    OverlayService.setOcrBoxTouchEnabled(true)
                    OverlayService.addLog("COMMAND_DONE result=cancelled")
                    onComplete()
                }
            }, null)
        } catch (e: Exception) {
            OverlayService.addLog("WS_ERROR command_dispatch=${e.message ?: "unknown"}")
            false
        }

        if (!ok) {
            commandRunning = false
            OverlayService.setOcrBoxTouchEnabled(true)
            OverlayService.addLog("COMMAND_DONE result=dispatch_failed")
            onComplete()
        }
    }
}
