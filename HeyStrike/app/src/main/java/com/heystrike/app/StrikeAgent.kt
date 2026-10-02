package com.heystrike.app

import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * P2+P4 — proper task planner instead of direct command parsing:
 * user -> intent -> plan -> execute -> observe -> verify -> next step.
 * Qwen (local pocket-qwen3) chooses tools each round from a live UI tree.
 *
 * Example "open whatsapp and call rahul":
 *   launch whatsapp -> observe tree -> find Search -> type rahul ->
 *   tap rahul row -> find call button -> tap -> verify call screen.
 */
class StrikeAgent(private val ctx: Context) {

    companion object {
        /** UI Stop button: set true to abort between rounds/steps. */
        @Volatile
        var stopRequested = false
    }

    // ---------- ObserveScreen: compact UI tree ----------
    fun observe(): String {
        val root = StrikeAccessibilityService.instance?.rootInActiveWindow
            ?: return "(no screen access — enable Strike Tap)"
        val sb = StringBuilder()
        dump(root, 0, sb)
        val s = sb.toString()
        return if (s.length > 4000) s.take(4000) + "\n…(truncated)" else s
    }

    private var nodes = 0
    private fun dump(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (n == null || depth > 8 || nodes > 120) return
        nodes++
        val text = (n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")
        val cls = (n.className?.toString() ?: "").substringAfterLast('.')
        if (text.trim('|').isNotBlank() || n.isClickable) {
            val b = Rect()
            n.getBoundsInScreen(b)
            sb.append("  ".repeat(depth))
                .append("[$cls${if (n.isClickable) "*" else ""}] ${text.trim('|')} @${b.centerX()},${b.centerY()}\n")
        }
        for (i in 0 until n.childCount) dump(n.getChild(i), depth + 1, sb)
    }

    // ---------- Act tools ----------
    private fun findNode(match: String): AccessibilityNodeInfo? {
        val root = StrikeAccessibilityService.instance?.rootInActiveWindow ?: return null
        val q = match.lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val n = queue.removeFirst()
            seen++
            val t = (n.text?.toString() ?: "").lowercase()
            val d = (n.contentDescription?.toString() ?: "").lowercase()
            if (q in t || q in d) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = n
        while (p != null && !p.isClickable) p = p.parent
        return p?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun tool(name: String, arg: String): String {
        val svc = StrikeAccessibilityService.instance
        return when (name.lowercase()) {
            "launch" -> {
                val pkg = StrikeApi.appPkg(arg)
                try {
                    val pm = ctx.packageManager
                    val i = pm.getLaunchIntentForPackage(pkg) ?: return "fail: $arg not installed"
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    Thread.sleep(1500)
                    "ok: launched $arg"
                } catch (e: Exception) { "fail: ${e.message}" }
            }
            "tap" -> if (svc == null) "fail: tap service off"
            else {
                val n = findNode(arg)
                if (n != null && clickNode(n)) { Thread.sleep(800); "ok: tapped $arg" }
                else "fail: '$arg' not on screen"
            }
            "type" -> if (svc == null) "fail: tap service off"
            else {
                val focus = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findNode("search")?.also { clickNode(it); Thread.sleep(500) }
                val target = focus ?: return "fail: no input field"
                val b = Bundle()
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arg)
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                    Thread.sleep(800); "ok: typed $arg"
                } else "fail: typing rejected"
            }
            "enter" -> {
                // No IME key API: tap the on-screen Search/Enter/Go key instead.
                var done = false
                for (key in listOf("search", "enter", "go", "done", "send")) {
                    val n = findNode(key)
                    if (n != null && clickNode(n)) { done = true; break }
                }
                Thread.sleep(800)
                if (done) "ok: submitted" else "fail: no submit key"
            }
            "swipeup", "swipedown" -> {
                if (svc == null) return "fail: tap service off"
                val up = name.equals("swipeup", true)
                val h = ctx.resources.displayMetrics.heightPixels
                val w = ctx.resources.displayMetrics.widthPixels
                val p = Path()
                if (up) { p.moveTo(w / 2f, h * 0.8f); p.lineTo(w / 2f, h * 0.3f) }
                else { p.moveTo(w / 2f, h * 0.3f); p.lineTo(w / 2f, h * 0.8f) }
                var done = false
                svc.dispatchGesture(
                    GestureDescription.Builder().addStroke(
                        GestureDescription.StrokeDescription(p, 0, 300)).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) { done = true }
                    }, null)
                Thread.sleep(900)
                if (done) "ok: swiped" else "fail: gesture rejected"
            }
            "back" -> if (StrikeAccessibilityService.goBack()) "ok: back" else "fail: back"
            "home" -> if (StrikeAccessibilityService.goHome()) "ok: home" else "fail: home"
            "wait" -> { Thread.sleep((arg.toLongOrNull() ?: 1000).coerceIn(200, 5000)); "ok: waited" }
            "verify" -> {
                val tree = observe().lowercase()
                if (arg.lowercase() in tree) "verified: '$arg' visible"
                else "not-verified: '$arg' missing — replan"
            }
            else -> "fail: unknown tool $name"
        }
    }

    // ---------- Qwen brain: picks tools ----------
    private fun askQwen(system: String, user: String): String {
        return try {
            val url = URL(Prefs.server(ctx) + "/api/chat")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 120000
                doOutput = true
            }
            val payload = JSONObject()
                .put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
                .toString().toByteArray()
            c.outputStream.use { it.write(payload) }
            c.inputStream.bufferedReader().readText()
        } catch (e: Exception) { "BRAIN_OFFLINE: ${e.message}" }
    }

    private val sys = """
You are Strike's hands. Answer ONLY with tool lines, one per line, then stop.
Tools: launch(app) tap(text) type(text) enter() swipeup() swipedown() back() home() wait(ms) verify(text) done(answer)
Rules: tap exact visible text (case-insensitive); if tap target missing, observe first via verify; chain max 6 steps; final line done(short spoken answer).
Screen now:
""".trimIndent()

    fun run(goal: String): String {
        if (StrikeAccessibilityService.instance == null)
            return "Enable Strike Tap in Accessibility settings first (button 7), then retry."
        val log = StringBuilder()
        var screen = observe()
        repeat(8) { round ->
            if (stopRequested) return "Stopped."
            val reply = askQwen(sys + screen, "Goal: $goal\nDone so far:\n$log")
            if (reply.startsWith("BRAIN_OFFLINE")) return "Brain offline — in Termux run start-all.sh"
            val calls = Regex("""\[TOOL_CALL:\s*(\w+)\((.*?)\)\s*\]""").findAll(reply).toList()
            val plain = reply.lines().filter { !it.contains("[TOOL_CALL:") }.joinToString(" ").trim()
            // also accept bare lines like: tap(Search)
            val bare = if (calls.isEmpty())
                Regex("""^(launch|tap|type|enter|swipeup|swipedown|back|home|wait|verify|done)\((.*?)\)\s*$""", RegexOption.MULTILINE)
                    .findAll(reply).map { it.groupValues[1] to it.groupValues[2] }.toList()
            else calls.map { it.groupValues[1] to it.groupValues[2] }
            if (bare.isEmpty()) {
                val fin = plain.ifBlank { "Done." }
                log.append("final: $fin\n")
                return fin.take(500)
            }
            for ((name, arg) in bare) {
                if (stopRequested) return "Stopped."
                if (name.equals("done", true)) return arg.ifBlank { plain }.take(500)
                val res = tool(name, arg.trim('\'', '"', ' '))
                log.append("$name($arg) -> $res\n")
                ConversationManager.recordToolResult(ctx, "$name($arg) -> $res")
            }
            screen = observe()
        }
        return "I did several steps, please check the screen. " + log.takeLast(300)
    }
}
