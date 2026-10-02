package com.heystrike.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phone-UI layer: observe + act on the real Android UI.
 * Enable in Settings → Accessibility → Strike Tap.
 *
 * Agent loop shape: OBSERVE (getUiTree) → PLAN → ACT → OBSERVE → VERIFY.
 * Every action returns Boolean; never claim success Android didn't report.
 */
class StrikeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: StrikeAccessibilityService? = null
            private set

        @Volatile private var lastPackage: String? = null

        // ---------- observe ----------

        fun getActivePackage(): String? = lastPackage
            ?: instance?.rootInActiveWindow?.packageName?.toString()

        /** All visible text + descriptions, one per line. */
        fun getScreenText(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val out = LinkedHashSet<String>()
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var seen = 0
            while (q.isNotEmpty() && seen < 400) {
                val n = q.removeFirst()
                seen++
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            return out.joinToString("\n")
        }

        /** Compact tree: [Class*] text|desc @cx,cy — mirrors StrikeAgent.observe. */
        fun getUiTree(maxNodes: Int = 120, maxLen: Int = 4000): String {
            val root = instance?.rootInActiveWindow ?: return "(no screen access — enable Strike Tap)"
            val sb = StringBuilder()
            var count = 0
            fun dump(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 8 || count >= maxNodes) return
                count++
                val text = (n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")
                val cls = (n.className?.toString() ?: "").substringAfterLast('.')
                if (text.trim('|').isNotBlank() || n.isClickable) {
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    sb.append("  ".repeat(depth))
                        .append("[$cls${if (n.isClickable) "*" else ""}] ${text.trim('|')} @${b.centerX()},${b.centerY()}\n")
                }
                for (i in 0 until n.childCount) dump(n.getChild(i), depth + 1)
            }
            dump(root, 0)
            val s = sb.toString()
            return if (s.length > maxLen) s.take(maxLen) + "\n…(truncated)" else s
        }

        fun getFocusedElement(): String? {
            val n = instance?.rootInActiveWindow
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: instance?.rootInActiveWindow
                    ?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return null
            val b = Rect()
            n.getBoundsInScreen(b)
            return "[${(n.className?.toString() ?: "").substringAfterLast('.')}] " +
                "${n.text}|${n.contentDescription} @${b.centerX()},${b.centerY()}"
        }

        // ---------- find ----------

        private fun bfs(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val root = instance?.rootInActiveWindow ?: return null
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var seen = 0
            while (q.isNotEmpty() && seen < 400) {
                val n = q.removeFirst()
                seen++
                if (match(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            return null
        }

        fun findText(text: String): AccessibilityNodeInfo? {
            val q = text.lowercase()
            return bfs {
                q in (it.text?.toString() ?: "").lowercase() ||
                    q in (it.contentDescription?.toString() ?: "").lowercase()
            }
        }

        fun findViewId(viewId: String): AccessibilityNodeInfo? {
            val root = instance?.rootInActiveWindow ?: return null
            return root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()
        }

        private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var p: AccessibilityNodeInfo? = n
            while (p != null && !p.isClickable) p = p.parent
            return p
        }

        // ---------- act ----------

        fun clickNode(n: AccessibilityNodeInfo): Boolean =
            clickable(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

        fun clickText(text: String): Boolean {
            val n = findText(text) ?: return false
            return clickNode(n)
        }

        /** Kept for StrikeAgent compat. */
        fun tapText(text: String): Boolean = clickText(text)

        private fun gesture(path: Path, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            var done = false
            svc.dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { done = true }
            }, null)
            // ponytail: busy-poll, no executor/thread infra for one boolean.
            val end = System.currentTimeMillis() + durationMs + 1500
            while (!done && System.currentTimeMillis() < end) Thread.sleep(50)
            return done
        }

        fun tap(x: Float, y: Float): Boolean {
            val p = Path().apply { moveTo(x, y) }
            return gesture(p, 50)
        }

        fun longPress(x: Float, y: Float, ms: Long = 600): Boolean {
            val p = Path().apply { moveTo(x, y) }
            return gesture(p, ms)
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
            val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            return gesture(p, ms)
        }

        fun scroll(forward: Boolean): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var seen = 0
            while (q.isNotEmpty() && seen < 400) {
                val n = q.removeFirst()
                seen++
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (n.isScrollable && n.performAction(action)) return true
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            return false
        }

        fun typeText(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun pressBack(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true

        fun pressHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true

        fun openRecents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true

        /** Kept for StrikeAgent compat. */
        fun goBack(): Boolean = pressBack()

        /** Kept for StrikeAgent compat. */
        fun goHome(): Boolean = pressHome()

        // ---------- wait / verify ----------

        fun waitForText(text: String, timeoutMs: Long = 5000): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            val q = text.lowercase()
            while (System.currentTimeMillis() < end) {
                if (q in getScreenText().lowercase()) return true
                Thread.sleep(300)
            }
            return false
        }

        fun waitForPackage(pkg: String, timeoutMs: Long = 5000): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (getActivePackage() == pkg) return true
                Thread.sleep(300)
            }
            return false
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { lastPackage = it }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
