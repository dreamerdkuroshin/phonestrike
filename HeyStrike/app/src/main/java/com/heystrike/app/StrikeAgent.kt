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

        /** Dedupe key for one executed tool call (case-blind tool name). */
        fun dedupeKey(name: String, arg: String) = "${name.lowercase()}|${arg.trim()}"

        /**
         * Completed steps from ConversationManager tool lines
         * ("tap(Send) -> ok: tapped Send") become done-seeds so a resumed
         * run never re-executes them — the 4×-send class of bug.
         * Pure function, unit-tested.
         */
        fun parseDoneSeeds(lines: List<String>): Set<String> {
            val out = mutableSetOf<String>()
            for (line in lines) {
                val m = Regex("""^(\w+)\((.*)\)\s*->\s*ok""").find(line.trim())
                if (m != null) out.add(dedupeKey(m.groupValues[1], m.groupValues[2]))
            }
            return out
        }

        /** Tools that must never repeat identically inside one run. */
        val MUTATING = setOf("tap", "type", "enter", "swipeup", "swipedown", "back", "home")
    }

    // ---------- ObserveScreen: compact UI tree ----------
    fun observe(): String {
        val root = StrikeAccessibilityService.instance?.rootInActiveWindow
            ?: return "(no screen access — enable Strike Tap)"
        nodes = 0 // reset every dump (a stale counter blanks all later trees)
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
            val flags = StringBuilder()
            if (n.isEditable) flags.append("{ed}")
            if (n.isSelected) flags.append("{sel}")
            if (!n.isEnabled) flags.append("{!}")
            if (n.isFocused) flags.append("{foc}")
            sb.append("  ".repeat(depth))
                .append("[$cls${if (n.isClickable) "*" else ""}]${flags} ${text.trim('|')} @${b.centerX()},${b.centerY()}\n")
        }
        for (i in 0 until n.childCount) dump(n.getChild(i), depth + 1, sb)
    }

    // ---------- D-06: wait for the UI to actually change ----------
    // Fixed sleeps read stale trees on slow devices (false-positive
    // verify). Poll a content fingerprint: return as soon as it moves,
    // or false on timeout. Blocking is fine — run() is never on main.
    // NOTE: fingerprints CONTENT (node identity hashes would differ on
    // every dump and make this return instantly — useless).
    fun uiFingerprint(): Int {
        val root = StrikeAccessibilityService.instance?.rootInActiveWindow ?: return 0
        var h = 17
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val n = queue.removeFirst()
            seen++
            h = 31 * h + (n.text?.toString().hashCode() ?: 0)
            h = 31 * h + (n.contentDescription?.toString().hashCode() ?: 0)
            h = 31 * h + (if (n.isClickable) 1 else 0)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return 31 * h + seen
    }

    fun waitForUiChange(timeoutMs: Long = 2000): Boolean {
        val before = uiFingerprint()
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(120)
            if (uiFingerprint() != before) return true
        }
        return false
    }

    // ---------- Act tools ----------
    private fun findNode(match: String): AccessibilityNodeInfo? {
        val root = StrikeAccessibilityService.instance?.rootInActiveWindow ?: return null
        val q = match.lowercase()
        // pass 1 exact match, pass 2 substring — "send" must not hit
        // "Send and receive" when a literal "Send" exists
        for (exact in booleanArrayOf(true, false)) {
            var seen = 0
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && seen < 300) {
                val n = queue.removeFirst()
                seen++
                val t = (n.text?.toString() ?: "").lowercase()
                val d = (n.contentDescription?.toString() ?: "").lowercase()
                val hit = if (exact) (t == q || d == q) else (q in t || q in d)
                if (hit) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
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
                    waitForUiChange(2500)
                    // verify: never claim open unless it is actually foreground
                    val active = StrikeAccessibilityService.getActivePackage()
                    if (active == pkg) "ok: launched $arg"
                    else "fail: $arg not in foreground (now: ${active ?: "none"})"
                } catch (e: Exception) { "fail: ${e.message}" }
            }
            "tap" -> if (svc == null) "fail: tap service off"
            else {
                val n = findNode(arg)
                if (n != null && clickNode(n)) { waitForUiChange(1500); "ok: tapped $arg" }
                else "fail: '$arg' not on screen"
            }
            "type" -> if (svc == null) "fail: tap service off"
            else if (arg.isBlank()) "fail: empty text — replan with the exact message to type"
            else {
                val focus = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findNode("search")?.also { clickNode(it); waitForUiChange(1200) }
                val target = focus ?: return "fail: no input field"
                val b = Bundle()
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arg)
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                    waitForUiChange(1500); "ok: typed $arg"
                } else "fail: typing rejected"
            }
            "enter" -> {
                // No IME key API: tap the on-screen Search/Enter/Go key instead.
                var done = false
                for (key in listOf("search", "enter", "go", "done", "send")) {
                    val n = findNode(key)
                    if (n != null && clickNode(n)) { done = true; break }
                }
                waitForUiChange(1500)
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
                waitForUiChange(1500)
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
        Prefs.serverBlockedReason(ctx)?.let { return "BRAIN_OFFLINE: $it" }
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
                // raw: prompt embeds a screen dump — instant-intent matching
                // must not hijack it (see server /api/chat)
                .put("raw", true)
                .put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
                .toString().toByteArray()
            c.outputStream.use { it.write(payload) }
            Prefs.readCapped(c)
        } catch (e: Exception) { "BRAIN_OFFLINE: ${e.message}" }
    }

    private val sys = """
You are Strike's hands. Answer ONLY with tool lines, one per line, then stop.
Tools: launch(app) tap(text) type(text) enter() swipeup() swipedown() back() home() wait(ms) verify(text) done(answer)
Rules: tap exact visible text (case-insensitive); if tap target missing, observe first via verify; chain max 6 steps; final line done(short spoken answer).
If the goal contains "and", it is a MULTI-STEP request: do EVERY clause in order before done(...) — e.g. "open whatsapp and message X" means launch, then compose the message; never stop after the first clause.
Messaging: "message <words> to <name>" means find <name>'s chat, then type(<words>) with the EXACT words from the goal — type() with empty or paraphrased text is a failure; tap send only after verify shows the typed text.
Screen now:
""".trimIndent()

    fun run(goal: String, prior: String = "", seeds: Set<String> = emptySet()): String {
        if (StrikeAccessibilityService.instance == null)
            return "Enable Strike Tap in Accessibility settings first (button 7), then retry."
        val log = StringBuilder()
        if (prior.isNotBlank()) log.append(prior.trim()).append("\n")
        val done = seeds.toMutableSet()
        val retried = mutableSetOf<String>() // A-01: one retry per step max
        var screen = observe()
        var drafted = false // a type() draft exists - enter() would submit it
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
                val cleanArg = arg.trim('\'', '"', ' ')
                // no-repeat guard: a mutating tool that already succeeded
                // must never run again (the 4x-send class) — tell the planner
                // to verify or finish instead
                if (name.lowercase() in MUTATING && dedupeKey(name, cleanArg) in done) {
                    val res = "already-done: $name($cleanArg) succeeded earlier — verify it or done(), do NOT repeat"
                    log.append("$name($arg) -> $res\n")
                    ConversationManager.recordToolResult(ctx, "$name($arg) -> $res")
                    break
                }
                // spec 16/32/38: HITL barrier — prompt on USER_CONFIRMED and above
                val level = AgentPermissions.levelFor(name, cleanArg, drafted)
                if (AgentPermissions.shouldPrompt(level)) {
                    val pc = PendingConfirm.set(name, cleanArg, level)
                    log.append("confirm required: $name($cleanArg)\n")
                    return PendingConfirm.question(pc)
                }
                val res = tool(name, cleanArg)
                log.append("$name($arg) -> $res\n")
                ConversationManager.recordToolResult(ctx, "$name($arg) -> $res")
                if (res.startsWith("ok") && name.lowercase() in MUTATING) {
                    done.add(dedupeKey(name, cleanArg))
                }
                if (name.equals("type", true) && res.startsWith("ok")) drafted = true
                if (name.equals("enter", true)) drafted = false
                // A-01 RECOVER: first failure of a step gets ONE re-observed
                // retry in the same round (transient miss, slow render); a
                // repeat failure ends the round so the next round replans
                // instead of hammering — never blind-tap continuously
                if (res.startsWith("fail") || res.startsWith("not-verified")) {
                    val key = dedupeKey(name, cleanArg)
                    if (key !in retried && name.lowercase() in MUTATING) {
                        retried.add(key)
                        screen = observe()
                        val retry = tool(name, cleanArg)
                        log.append("$name($arg) -> retry: $retry\n")
                        ConversationManager.recordToolResult(ctx, "$name($arg) -> retry: $retry")
                        if (retry.startsWith("ok")) {
                            done.add(key)
                            continue
                        }
                    }
                    break
                }
            }
            screen = observe()
        }
        return "I did several steps, please check the screen. " + log.takeLast(300)
    }
}
