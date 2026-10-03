package com.heystrike.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

/**
 * Talks to PocketStrike server (same box as Termux: http://127.0.0.1:5000).
 * Mobile tasks that need zero server round-trip (app launch) run here with
 * PackageManager — reliable, no am/monkey hacks, normal permissions only.
 */
class StrikeApi(private val ctx: Context, private val tts: TextToSpeech?) {

    private val appNames = mapOf(
        "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps", "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android", "spotify" to "com.spotify.music",
        "camera" to "com.android.camera2", "clock" to "com.google.android.deskclock",
        "settings" to "com.android.settings", "photos" to "com.google.android.apps.photos"
    )

    fun handle(text: String, record: Boolean = true): String {
        // correction re-routes ("actually telegram") re-enter the pipeline clean
        Correction.strip(text)?.let { fixed ->
            if (record) ConversationManager.recordUser(ctx, text)
            return handle(fixed, record = false)
        }
        if (record) ConversationManager.recordUser(ctx, text)
        // normalize FIRST: Devanagari/Gujarati ASR becomes Latin, so every
        // matcher below (wake, verbs, contacts, apps) stays monolingual.
        // Raw text is still what gets recorded and displayed.
        val norm = Transliterate.normalize(text)
        val t = WakeMatcher.stripWake(norm)
        val hadWake = t != norm
        val hindi = Prefs.voiceLang(ctx) == "hi" ||
            text.any { it.code in 0x0900..0x0AFF }
        // HITL confirmation release ("yes"/"no"/"haan") — before every other route
        PendingConfirm.consume(t, ctx)?.let { return it }
        // runtime emergency stop (spec 58): never depends on the LLM obeying
        if (t in stopWords) {
            StrikeAgent.stopRequested = true
            PendingConfirm.clear(ctx)
            return "Stopped."
        }
        // clarification-task follow-up ("haan" / slot fills) — before contact routes
        PendingTask.consume(t, hindi)?.let { o ->
            return when (o) {
                is PendingTask.Outcome.Say -> o.text
                is PendingTask.Outcome.Run -> runPlanner(o.goal)
            }
        }
        // pending ambiguity follow-up: answer with just the app name
        AppResolver.pick(ctx, t)?.let { pkg ->
            val name = CommandNormalizer.normalizeName(t)
            return if (launch(pkg)) "$name is open." else "I couldn't open $name."
        }
        // pending contact ambiguity follow-up: "beru" / "second one"
        pendingContact?.let { (raw, name) ->
            ContactResolver.pick(t)?.let { m ->
                pendingContact = null
                // case-insensitive: name came from the lowercased transcript
                return handle(injectName(raw, name, m.name ?: name), record = false)
            }
        }
        // deterministic contact intents (resolve -> never invent -> ask if many)
        contactIntent(t)?.let { (verb, name) ->
            contactRoute(text, verb, name)?.let { return it }
        }
        // spec 11/12: camera + screen-vision cues, BEFORE the planner so
        // "take a screenshot and read it" isn't hijacked by " and " routing
        if (isCameraCue(t)) return Vision.askCamera(ctx, text)
        if (isScreenVisionCue(t)) return Vision.askScreen(ctx, text)
        // multilingual message/open compounds: structured intent with known
        // slots, or ONE clarification question — never a blind guess, and
        // never silence when the transcript lost a word
        routeIntent(t, hindi, hadWake, text)?.let { return it }
        // P4: multi-step / contact tasks go to the planner (observe→act→verify),
        // not the direct parser. e.g. "open whatsapp and call rahul".
        if (t.startsWith("call ") || " and " in t || " then " in t || t.startsWith("tap ")) {
            return runPlanner(text)
        }
        // Phone-UI layer (Accessibility): tap by visible text, back/home
        if (t.startsWith("tap ")) {
            val target = t.removePrefix("tap ").trim()
            if (target.isEmpty()) return "Tap what? Say tap followed by the button name."
            return if (StrikeAccessibilityService.tapText(target)) "Tapped $target."
            else "Couldn't find $target on screen. Enable Strike Tap in Accessibility settings."
        }
        if (t in listOf("go back", "back", "press back")) {
            return if (StrikeAccessibilityService.goBack()) "Went back."
            else "Back unavailable. Enable Strike Tap in Accessibility settings."
        }
        if (t in listOf("go home", "home screen", "press home")) {
            return if (StrikeAccessibilityService.goHome()) "Home."
            else "Home unavailable. Enable Strike Tap in Accessibility settings."
        }
        // Local instant intents (no network): open app
        for ((name, pkg) in appNames) {
            if (t.contains("open $name") || t == "open $name") {
                return if (launch(pkg)) "$name is open." else "I couldn't open $name."
            }
        }
        // Dynamic resolution: "open <anything>" — PackageManager labels + known
        // mappings; multiple matches are asked about, never guessed.
        if (t.startsWith("open ") || t.startsWith("launch ")) {
            val label = CommandNormalizer.normalizeName(
                t.removePrefix("open ").removePrefix("launch "))
            if (label.isNotEmpty()) {
                val m = AppResolver.resolve(ctx, label)
                val pkg = m.pkg
                when {
                    pkg != null -> return if (launch(pkg)) "$label is open." else "I couldn't open $label."
                    m.candidates.size > 1 -> return "I found ${m.candidates.size} matches: " +
                        m.candidates.joinToString(", ") + ". Which one should I open?"
                    else -> return "I couldn't find an app called \"$label\"."
                }
            }
        }
        // Everything else -> PocketStrike brain (local pocket-qwen3 or cloud)
        return askServer(text)
    }

    companion object {
        private val contactVerbs = listOf("message", "text", "call", "whatsapp")

        /** Emergency-stop utterances (spec 58) — handled at runtime, not by the LLM. */
        private val stopWords = setOf(
            "stop", "stop it", "stop that", "stop this", "cancel", "cancel that",
            "cancel this", "never mind", "nevermind", "abort", "forget it"
        )

        // pending contact ambiguity: (original command, extracted name)
        @Volatile
        private var pendingContact: Pair<String, String>? = null

        private val pkgMap = mapOf(
            "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps", "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android", "spotify" to "com.spotify.music",
            "camera" to "com.android.camera2", "clock" to "com.google.android.deskclock",
            "settings" to "com.android.settings", "photos" to "com.google.android.apps.photos",
            "phone" to "com.google.android.dialer", "dialer" to "com.google.android.dialer",
            "messages" to "com.google.android.apps.messaging", "sms" to "com.google.android.apps.messaging",
            "contacts" to "com.google.android.contacts"
        )
        fun appPkg(name: String): String {
            val k = name.lowercase().trim()
            return pkgMap[k] ?: (k.takeIf { "." in k } ?: "com.android.settings")
        }
    }

    /**
     * Strict action lifecycle: RESOLVE → EXECUTE → WAIT → VERIFY → REPORT.
     * Never reports success unless the app is actually in the foreground.
     */
    private fun launch(pkg: String): Boolean {
        return try {
            val pm: PackageManager = ctx.packageManager
            val intent: Intent? = pm.getLaunchIntentForPackage(pkg)
            if (intent == null) return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            // Wait for the app to actually come to the foreground
            Thread.sleep(2000)
            val active = StrikeAccessibilityService.getActivePackage()
            active == pkg
        } catch (_: Exception) { false }
    }

    /** Dynamic app resolution: match installed app labels case-insensitively. */
    fun resolveApp(label: String): String? {
        val pm = ctx.packageManager
        val q = label.lowercase().trim()
        // Fast path: known package map
        pkgMap[q]?.let { return it }
        // Dynamic: scan installed apps for label match
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val matches = apps.filter { app ->
            val appLabel = pm.getApplicationLabel(app).toString().lowercase()
            appLabel.contains(q) || q.contains(appLabel)
        }
        return when (matches.size) {
            1 -> matches[0].packageName
            0 -> null
            else -> null // ambiguous — don't guess
        }
    }

    /**
     * Streaming twin of handle(): local intents arrive as one instant token,
     * AI answers arrive token-by-token like ChatGPT.
     */
    fun handleStream(
        text: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        record: Boolean = true
    ) {
        // correction re-routes ("actually telegram") re-enter the pipeline clean
        Correction.strip(text)?.let { fixed ->
            if (record) ConversationManager.recordUser(ctx, text)
            handleStream(fixed, onToken, onDone, record = false)
            return
        }
        if (record) ConversationManager.recordUser(ctx, text)
        // fresh command: a stale Stop from a previous turn must not kill this
        // stream (askServerStream polls stopRequested while reading SSE)
        StrikeAgent.stopRequested = false
        val norm = Transliterate.normalize(text)
        val t = WakeMatcher.stripWake(norm)
        val hadWake = t != norm
        val hindi = Prefs.voiceLang(ctx) == "hi" ||
            text.any { it.code in 0x0900..0x0AFF }
        // HITL confirmation release ("yes"/"no"/"haan") — before every other route
        PendingConfirm.consume(t, ctx)?.let { ans ->
            onToken(ans)
            onDone()
            return
        }
        // runtime emergency stop (spec 58): never depends on the LLM obeying
        if (t in stopWords) {
            StrikeAgent.stopRequested = true
            PendingConfirm.clear(ctx)
            onToken("Stopped.")
            onDone()
            return
        }
        // clarification-task follow-up — before contact routes
        PendingTask.consume(t, hindi)?.let { o ->
            when (o) {
                is PendingTask.Outcome.Say -> onToken(o.text)
                is PendingTask.Outcome.Run -> onToken(runPlanner(o.goal))
            }
            onDone()
            return
        }
        // pending ambiguity follow-up: answer with just the app name
        AppResolver.pick(ctx, t)?.let { pkg ->
            val name = CommandNormalizer.normalizeName(t)
            onToken(if (launch(pkg)) "$name is open." else "I couldn't open $name.")
            onDone()
            return
        }
        // pending contact ambiguity follow-up: "beru" / "second one"
                pendingContact?.let { (raw, name) ->
                    ContactResolver.pick(t)?.let { m ->
                        pendingContact = null
                        // record=false: the original command was already recorded
                        handleStream(injectName(raw, name, m.name ?: name), onToken, onDone, record = false)
                        return
                    }
                }
        // deterministic contact intents (resolve -> never invent -> ask if many)
        contactIntent(t)?.let { (verb, name) ->
            contactRoute(text, verb, name)?.let { ans ->
                onToken(ans)
                onDone()
                return
            }
        }
        // spec 11/12: camera + screen-vision cues, BEFORE the planner so
        // "take a screenshot and read it" isn't hijacked by " and " routing
        if (isCameraCue(t)) {
            onToken(Vision.askCamera(ctx, text))
            onDone()
            return
        }
        if (isScreenVisionCue(t)) {
            onToken(Vision.askScreen(ctx, text))
            onDone()
            return
        }
        // multilingual message/open compounds: structured intent or ONE
        // clarification — never a blind guess, never silence
        routeIntent(t, hindi, hadWake, text)?.let { ans ->
            onToken(ans)
            onDone()
            return
        }
        // multi-step / contact tasks -> local agent planner (observe->act->verify)
        if (t.startsWith("call ") || " and " in t || " then " in t) {
            onToken(runPlanner(text))
            onDone()
            return
        }
        if (t.startsWith("tap ")) {
            val target = t.removePrefix("tap ").trim()
            if (target.isEmpty()) onToken("Tap what? Say tap followed by the button name.")
            else if (StrikeAccessibilityService.tapText(target)) onToken("Tapped $target.")
            else onToken("Couldn't find $target on screen. Enable Strike Tap in Accessibility settings.")
            onDone()
            return
        }
        if (t in listOf("go back", "back", "press back")) {
            onToken(if (StrikeAccessibilityService.goBack()) "Went back." else "Back unavailable. Enable Strike Tap in Accessibility settings.")
            onDone()
            return
        }
        if (t in listOf("go home", "home screen", "press home")) {
            onToken(if (StrikeAccessibilityService.goHome()) "Home." else "Home unavailable. Enable Strike Tap in Accessibility settings.")
            onDone()
            return
        }
        for ((name, pkg) in appNames) {
            if (t.contains("open $name") || t == "open $name") {
                onToken(if (launch(pkg)) "$name is open." else "I couldn't open $name.")
                onDone()
                return
            }
        }
        // Dynamic resolution: "open <anything>" — PackageManager labels + known
        // mappings; multiple matches are asked about, never guessed.
        if (t.startsWith("open ") || t.startsWith("launch ")) {
            val label = CommandNormalizer.normalizeName(
                t.removePrefix("open ").removePrefix("launch "))
            if (label.isNotEmpty()) {
                val m = AppResolver.resolve(ctx, label)
                val pkg = m.pkg
                when {
                    pkg != null ->
                        onToken(if (launch(pkg)) "$label is open." else "I couldn't open $label.")
                    m.candidates.size > 1 ->
                        onToken("I found ${m.candidates.size} matches: " +
                            m.candidates.joinToString(", ") + ". Which one should I open?")
                    else ->
                        onToken("I couldn't find an app called \"$label\".")
                }
                onDone()
                return
            }
        }
        askServerStream(text, onDone, onToken)
    }

    private fun askServer(text: String): String {
        val out = StringBuilder()
        askServerStream(text, onDone = {}, onToken = { out.append(it) })
        return out.toString().ifBlank { "Sorry, empty reply." }
    }

    /**
     * Raw blocking LLM call: bypasses mobile/jarvis matching (raw:true) so a
     * recovery prompt containing words like "open" is never hijacked.
     */
    private fun askServerRaw(prompt: String, timeoutMs: Int = 90000): String? {
        return try {
            val url = URL(Prefs.server(ctx) + "/api/chat")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = timeoutMs
                doOutput = true
            }
            val body = JSONObject()
                .put("raw", true)
                .put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "user").put("content", prompt)))
                .toString().toByteArray()
            c.outputStream.use { it.write(body) }
            c.inputStream.bufferedReader().readText()
        } catch (_: Exception) { null }
    }

    // ---------- deterministic contact intents ----------

    /** ("message"|"text"|"call"|"whatsapp", name) when the command targets a person. */
    private fun contactIntent(t: String): Pair<String, String>? {
        val verb = t.split(" ").firstOrNull() ?: return null
        if (verb !in contactVerbs) return null
        val rest = t.removePrefix(verb).trim()
        if (rest.startsWith("and ")) return null // "whatsapp and message beru" = app opener
        val stop = setOf(
            "that", "to", "about", "and", "saying", "say", "telling", "tell",
            "is", "the", "a", "an", "on", "for", "me", "my", "now", "please"
        )
        val words = rest.split(" ").filter { it.isNotBlank() }.takeWhile { it !in stop }.take(4)
        if (words.isEmpty()) return null
        return verb to words.joinToString(" ")
    }

    /** Replace the spoken name inside the original command (case-blind). */
    private fun injectName(raw: String, spoken: String, resolved: String): String = try {
        Regex(Regex.escape(spoken), RegexOption.IGNORE_CASE).replaceFirst(raw, resolved)
    } catch (_: Exception) { raw }

    /** null = keep the original flow (e.g. "call voicemail" has no contact). */
    private fun contactRoute(raw: String, verb: String, name: String): String? {
        val r = ContactResolver.resolve(ctx, name)
        if (r.error == "contacts permission denied") {
            return "I need Contacts permission — open the Hey Strike app and tap Grant, then try again."
        }
        if (r.error != null) return "Contact lookup failed: ${r.error}."
        if (r.candidates.size > 1) {
            pendingContact = raw to name
            return "I found ${r.candidates.size} contacts: " +
                r.candidates.joinToString(", ") + ". Which one?"
        }
        if (r.name == null) {
            if (verb == "call") return null // no contact match: let planner/LLM try
            return "I couldn't find a contact named \"$name\". " +
                "Add them to Contacts or say their exact name."
        }
        if (verb == "call" && "whatsapp" !in raw.lowercase() && "video" !in raw.lowercase()) {
            val num = r.phone
            if (num.isNullOrBlank()) return "I found ${r.name}, but no phone number is saved for them."
            return if (dial(num)) "Dialer is open for ${r.name} — say call to place it."
            else "I couldn't open the dialer for ${r.name}."
        }
        // message / text / whatsapp-or-video call: multi-step UI work -> planner
        return runPlanner(raw)
    }

    private fun dial(num: String): Boolean = try {
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) == null) false
        else { ctx.startActivity(i); true }
    } catch (_: Exception) { false }

        /**
     * Multilingual intent router (shared by handle/handleStream).
     * String = answered here; null = continue normal routing.
     */
    private fun routeIntent(t: String, hindi: Boolean, hadWake: Boolean, raw: String): String? {
        val intent = IntentParser.parseMessage(t)
        if (intent == null) {
            // nothing parsed deterministically: with a wake word or a fuzzy
            // keyword hit, recover via LLM or ask with the transcript shown —
            // never silent, never a hallucinated chat answer to a command
            if (!hadWake && !IntentParser.fuzzyHit(t)) return null
            return recoverOrClarify(t, raw, hindi)
        }
        if (intent.action == "open") {
            if (intent.app != null) {
                val label = intent.app.replaceFirstChar { it.uppercase() }
                return if (launch(appPkg(intent.app))) "$label is open."
                else "I couldn't open $label."
            }
            // app lost in ASR ("open aur beru", "oph vatara maich bairut"):
            // probe content tokens as contacts (exact/prefix/near) — a hit
            // means a message compound, so clarify instead of failing
            val skip = setOf(
                "open", "opan", "oph", "launch", "start", "khol", "kholo", "kholna",
                "and", "aur", "ane", "then", "message", "msg", "maisej", "mesej",
                "mesij", "send", "text", "bhej", "bhejo", "ko", "ke", "ne", "to"
            )
            val probes = t.split(" ").map { it.trim() }.filter {
                it.isNotBlank() && it !in skip && it.all { c -> c.isLetter() }
            }.take(4)
            for (p in probes) {
                val hit = ContactResolver.resolve(ctx, p)
                if (hit.error == null && hit.name != null) {
                    val pend = IntentParser.MsgIntent("message", null, hit.name, null)
                    PendingTask.set(pend)
                    return IntentParser.clarify(pend, hindi)
                }
            }
            return null
        }
        // message intent: recipient-vs-text ambiguity ("message hello" sends
        // "hello" to someone — it is not a contact named Hello)
        var msg = intent
        val rcpt = msg.recipient
        if (msg.message == null && rcpt != null) {
            val r = ContactResolver.resolve(ctx, rcpt)
            if (r.error == null && r.name == null && r.candidates.isEmpty()) {
                msg = msg.copy(recipient = null, message = rcpt)
            }
        }
        if (!msg.complete) {
            PendingTask.set(msg)
            return IntentParser.clarify(msg, hindi)
        }
        return runPlanner(t)
    }

    /**
     * Last-resort command recovery for noisy transcripts ("oph vatara maich
     * bairut"): one local LLM extraction with strict validation, else a
     * clarification that SHOWS what was heard. Validation gates (known app,
     * contact-checked recipient) mean the LLM can never invent an action.
     */
    private fun recoverOrClarify(t: String, raw: String, hindi: Boolean): String {
        val heard = raw.trim().ifBlank { t }.take(160)
        val prompt = "Noisy Hinglish voice transcript. Extract ONE intent as JSON like " +
            "{\"action\":\"message\",\"app\":\"whatsapp\",\"recipient\":\"Beru\",\"message\":\"hello\"} " +
            "or {\"action\":\"open\",\"app\":\"chrome\"}. action is message|open|none. " +
            "Known apps: whatsapp. If it is a question or chit-chat, use {\"action\":\"none\"}. " +
            "Unknown slots: \"?\". Reply ONLY the JSON, no other words. " +
            "Transcript: \"$t\""
        try {
            askServerRaw(prompt)?.let { resp ->
                val rec = IntentParser.parseRecoveryJson(resp) ?: return@let
                if (rec.action == "open" && rec.app != null) {
                    val label = rec.app.replaceFirstChar { it.uppercase() }
                    return if (launch(appPkg(rec.app))) "$label is open."
                    else "I couldn't open $label."
                }
                if (rec.action == "message") {
                    if (rec.recipient != null) {
                        val r = ContactResolver.resolve(ctx, rec.recipient)
                        val name = r.name
                        if (r.error == null && name != null) {
                            val pend = IntentParser.MsgIntent("message", rec.app, name, rec.message)
                            if (pend.complete) return runPlanner(
                                "open ${rec.app ?: "app"} and message $name ${rec.message ?: ""}".trim())
                            PendingTask.set(pend)
                            return IntentParser.clarify(pend, hindi)
                        }
                        // recipient not a contact: do NOT invent — clarify
                        val pend = IntentParser.MsgIntent("message", rec.app, null, rec.message)
                        PendingTask.set(pend)
                        return IntentParser.clarify(pend, hindi)
                    }
                    if (rec.app != null || rec.message != null) {
                        val pend = IntentParser.MsgIntent("message", rec.app, null, rec.message)
                        PendingTask.set(pend)
                        return IntentParser.clarify(pend, hindi)
                    }
                }
            }
        } catch (_: Exception) { }
        // recovery failed or found nothing: ask WITH the transcript shown
        return if (hindi) "Maine suna: “$heard” — kya karna hai, dobara bolo?"
        else "I heard: “$heard” — what should I do?"
    }

    private fun runPlanner(text: String): String {        return try {
            ConversationManager.setTask(ctx, text)
            StrikeAgent.stopRequested = false // fresh run clears a stale Stop press
            StrikeAgent(ctx).run(text)
        } catch (e: Exception) {
            "Planner failed: ${e.message}"
        } finally {
            // a pending confirmation keeps the card alive until yes/no resolves it
            if (PendingConfirm.active() == null) ConversationManager.clearTask(ctx)
        }
    }

    // ---------- conversation-agent enrichment (screen + live web facts) ----------

    /** "what's on my screen / read my screen / what am I looking at" — answer from the a11y tree. */
    private val screenQuery = Regex(
        "what('s| is)? (on|this) (my )?screen|read (my|the )?screen|" +
        "what am i (looking at|seeing)|describe (the |my )?screen|" +
        "what('s| is) (this|on this) (app|page|screen)"
    )

    /** Spec 11: physical-object cues — short and exact so chat questions don't hijack the camera. */
    private fun isCameraCue(t: String): Boolean {
        val w = t.trim().trim('?', '!', '.')
        if (w.split(" ").size > 5) return false
        if (screenQuery.containsMatchIn(w)) return false // screen questions use the a11y tree
        return w.startsWith("look at this") || w.startsWith("look at that") ||
            w == "what is this" || w == "what's this" ||
            w == "what is that" || w == "what's that" ||
            w.startsWith("what am i holding") || w.startsWith("what am i pointing")
    }

    /** Spec 12: explicit screen-vision cues (screenshot / on-screen error text). */
    private val screenVisionCue = Regex(
        "take a (fast )?(screenshot|screen shot)|screenshot (this|it|my screen|the screen|for me)|" +
        "why is this error|what does (this|the) error say"
    )

    private fun isScreenVisionCue(t: String): Boolean =
        screenVisionCue.containsMatchIn(t.trim().trim('?', '!', '.'))

    /** Live-fact cues: fetch fresh web snippets so the local LLM doesn't hallucinate recency. */
    private val searchCue = Regex(
        "\\b(latest|breaking|headlines?|news|price|prices|stock|stocks|weather|forecast|" +
        "score|today|yesterday|who won|current (price|rate|news)|how much is|" +
        "search (the )?web|google it|right now)\\b"
    )

    /** DuckDuckGo results via the server's /api/search; null = plain LLM path. */
    private fun searchSnippet(text: String): String? {
        if (!searchCue.containsMatchIn(text.lowercase())) return null
        return try {
            val q = URLEncoder.encode(text.take(160), "UTF-8")
            val c = URL(Prefs.server(ctx) + "/api/search?q=$q").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("results")
            if (arr.length() == 0) return null
            buildString {
                append("Web search results for \"").append(text.take(120)).append("\":\n")
                for (i in 0 until minOf(5, arr.length())) {
                    val o = arr.getJSONObject(i)
                    append("- ").append(o.optString("title")).append(": ")
                    append(o.optString("snippet"))
                    val u = o.optString("url")
                    if (u.isNotEmpty()) append(" (source: ").append(u).append(")")
                    append('\n')
                }
                append("Cite the source URL when you use a result above.\n")
            }
        } catch (_: Exception) { null }
    }

    /** Live token stream (ChatGPT-style): calls onToken per chunk as they arrive. */
    fun askServerStream(text: String, onDone: () -> Unit, onToken: (String) -> Unit) {
        // context enrichment stays client-side; the executor path above only
        // ever sees the FINAL transcript
        var prompt = ConversationManager.enrich(ctx, text)
        // screen understanding: prepend the live a11y tree for screen queries
        if (screenQuery.containsMatchIn(text.lowercase())) {
            prompt = "Current screen contents (from accessibility service):\n" +
                StrikeAgent(ctx).observe() + "\n\nUser asked: " + prompt
        }
        // live web facts first, so the prompt never starts with a server-intercepted verb
        searchSnippet(text)?.let { web ->
            prompt = web + "Use the results above when relevant; answer in plain spoken words.\n\nQuestion: " + text
        }
        val answer = StringBuilder()
        try {
            val url = URL(Prefs.server(ctx) + "/api/voice/stream")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 120000
                doOutput = true
            }
            val body = JSONObject().put("text", prompt).toString().toByteArray()
            c.outputStream.use { it.write(body) }
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // emergency stop (spec 58): cut the stream instead of
                    // reading a dead answer to EOF
                    if (StrikeAgent.stopRequested) {
                        c.disconnect()
                        break
                    }
                    val s = line.trim()
                    if (!s.startsWith("data:")) continue
                    val payload = s.removePrefix("data:").trim()
                    if (payload == "[DONE]") continue
                    try {
                        val tok = JSONObject(payload).optString("token", "")
                        if (tok.isNotEmpty()) {
                            StrikeVoiceController.noteFirstToken()
                            answer.append(tok)
                            onToken(tok)
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            val msg = "Strike server unreachable. In Termux run: bash ~/PocketStrike-AI/start-all.sh"
            answer.append(msg)
            onToken(msg)
        } finally {
            // answer recording belongs to the UI-layer onDone callers only —
            // doing it here as well double-recorded every streamed answer
            onDone()
        }
    }

    fun speak(s: String) {
        // URLs are for the screen, never the ear (spec 5)
        val clean = s.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF☀-➿➕➖]"), "").trim().take(450)
        tts?.speak(clean.ifBlank { "Done." }, TextToSpeech.QUEUE_FLUSH, null, "strike")
    }
}
