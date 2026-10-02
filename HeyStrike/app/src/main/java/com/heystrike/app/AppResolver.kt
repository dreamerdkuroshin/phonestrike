package com.heystrike.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Installed-app resolution via PackageManager: known mappings first, then
 * label match against launcher apps (normalized, alias-aware, case-blind).
 * Never invents package names; multiple matches are reported so the caller
 * can ask which one.
 */
object AppResolver {

    data class Match(val pkg: String?, val candidates: List<String> = emptyList())

    // spec: keep the known mappings
    private val known = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "spotify" to "com.spotify.music",
        "camera" to "com.android.camera2",
        "clock" to "com.google.android.deskclock",
        "settings" to "com.android.settings",
        "photos" to "com.google.android.apps.photos",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "contacts" to "com.google.android.contacts"
    )

    // pending ambiguity from the previous command (label -> pkg)
    @Volatile
    private var lastAmbiguous: List<Pair<String, String>> = emptyList()

    fun resolve(ctx: Context, query: String): Match {
        val q = CommandNormalizer.normalizeName(query)
        if (q.isEmpty()) return Match(null)

        known[q]?.let { pkg -> if (installed(ctx, pkg)) return Match(pkg) }

        val apps = launcherApps(ctx) // deduped (label, pkg)
        val norm = apps.map { CommandNormalizer.normalizeName(it.first) to it }

        val exact = norm.filter { it.first == q }.map { it.second }
        if (exact.size == 1) return Match(exact[0].second)
        if (exact.size > 1) return ambiguous(exact)

        val pref = norm.filter { (nl, _) -> nl.length >= 3 && (nl.startsWith(q) || q.startsWith(nl)) }
            .map { it.second }
        if (pref.size == 1) return Match(pref[0].second)
        if (pref.size > 1) return ambiguous(pref)

        val contains = norm.filter { (nl, _) -> nl.length >= 3 && (nl.contains(q) || q.contains(nl)) }
            .map { it.second }
        if (contains.size == 1) return Match(contains[0].second)
        if (contains.size > 1) return ambiguous(contains)

        return Match(null)
    }

    /**
     * Follow-up pick while an ambiguity is pending: user answers "whatsapp"
     * to "which one?". Returns the chosen package (and clears the pending set).
     */
    fun pick(ctx: Context, text: String): String? {
        val pend = lastAmbiguous
        if (pend.isEmpty()) return null
        val raw = text.lowercase().trim()
        if (raw.isEmpty()) return null
        // ordinal: "second one" / "the second" / "2" (was exact-label only)
        val cleaned = raw.removePrefix("the ").removeSuffix(" one").removeSuffix("one").trim()
        val oi = when (cleaned) {
            "first", "1" -> 0; "second", "2" -> 1; "third", "3" -> 2
            "fourth", "4" -> 3; "fifth", "5" -> 4; else -> -1
        }
        if (oi in pend.indices) {
            lastAmbiguous = emptyList()
            return pend[oi].second
        }
        val q = CommandNormalizer.normalizeName(text)
        if (q.isEmpty()) return null
        val m = pend.filter {
            CommandNormalizer.normalizeName(it.first) == q || it.first.lowercase().startsWith(q)
        }
        if (m.size == 1) {
            lastAmbiguous = emptyList()
            return m[0].second
        }
        return null
    }

    fun clearPending() { lastAmbiguous = emptyList() }

    private fun ambiguous(list: List<Pair<String, String>>): Match {
        lastAmbiguous = list
        return Match(null, list.map { it.first })
    }

    private fun installed(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

    private fun launcherApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
        } catch (_: Exception) { emptyList() }
    }
}
