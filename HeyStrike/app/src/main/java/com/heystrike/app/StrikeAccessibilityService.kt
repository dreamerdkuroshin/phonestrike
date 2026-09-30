package com.heystrike.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phone-UI layer: real on-screen control (tap by visible text, back/home/recents)
 * for tasks intents can't express. Enable in Settings → Accessibility → Strike Tap.
 */
class StrikeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: StrikeAccessibilityService? = null
            private set

        fun tapText(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (n in nodes) {
                var p: AccessibilityNodeInfo? = n
                while (p != null && !p.isClickable) p = p.parent
                if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            return false
        }

        fun goBack(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true

        fun goHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
