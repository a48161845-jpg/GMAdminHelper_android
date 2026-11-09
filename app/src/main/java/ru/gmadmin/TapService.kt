package ru.gmadmin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Служба доступности только для одного: делать тапы по экрану.
 * Нужна, чтобы заново открывать чат игры (после отправки он закрывается сам).
 * Ничего не читает и не собирает.
 */
class TapService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (inst === this) inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float, done: (() -> Unit)? = null) {
        try {
            val p = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, 60L)).build()
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
                override fun onCancelled(gestureDescription: GestureDescription?) { done?.invoke() }
            }
            if (!dispatchGesture(g, cb, null)) done?.invoke()
        } catch (e: Exception) { done?.invoke() }
    }

    companion object {
        @Volatile var inst: TapService? = null
    }
}
