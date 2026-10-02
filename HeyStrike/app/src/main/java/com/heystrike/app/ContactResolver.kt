package com.heystrike.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * Contact lookup via ContactsContract. Exact -> prefix -> small edit distance.
 * Never invents a contact: no match / multiple matches / no permission all
 * come back as an explicit answer the caller can speak. Pending ambiguity
 * survives until the follow-up names one of the candidates.
 */
object ContactResolver {

    data class Match(
        val name: String?,
        val phone: String?,
        val candidates: List<String> = emptyList(),
        val error: String? = null
    )

    private data class Contact(val name: String, val phone: String?)

    // pending ambiguity from the previous command (display label -> name)
    @Volatile
    private var lastAmbiguous: List<Pair<String, String>> = emptyList()

    fun resolve(ctx: Context, query: String): Match {
        if (checkSelfPermission(ctx) != PackageManager.PERMISSION_GRANTED) {
            return Match(null, null, emptyList(), "contacts permission denied")
        }
        val q = query.lowercase().trim()
        if (q.isEmpty()) return Match(null, null, emptyList(), "empty name")

        val all = load(ctx)
        if (all.isEmpty()) return Match(null, null, emptyList(), "no contacts found")

        val norm = all.map { it.copy(name = it.name.lowercase().trim()) }

        val exact = norm.filter { it.name == q }
        if (exact.size == 1) return matched(exact[0], query)
        if (exact.size > 1) return ambiguous(exact.map { label(it) to it.name })

        val pref = norm.filter { it.name.startsWith(q) || q.startsWith(it.name) }
        if (pref.size == 1) return matched(pref[0], query)
        if (pref.size > 1) return ambiguous(pref.map { label(it) to it.name })

        val near = norm.filter { distance(it.name, q) <= editBudget(q) }
        if (near.size == 1) return matched(near[0], query)
        if (near.size > 1) return ambiguous(near.map { label(it) to it.name })

        return Match(null, null)
    }

    /**
     * Follow-up pick while a contact ambiguity is pending: user answers "the
     * second one" / "Beru" / a full label. Clears the pending set on success.
     */
    fun pick(text: String): Match? {
        val pend = lastAmbiguous
        if (pend.isEmpty()) return null
        val t = text.lowercase().trim()
        if (t.isEmpty()) return null
        // ordinal answer: "second" / "second one" / "the second one" / "2".
        // (the old removeSuffix("one") left "second " — ordinals NEVER matched)
        val cleaned = t.removePrefix("the ").removeSuffix(" one").removeSuffix("one").trim()
        val oi = ordinalIndex(cleaned)
        if (oi in pend.indices) {
            lastAmbiguous = emptyList()
            return Match(pend[oi].second, null)
        }
        val q = CommandNormalizer.normalizeName(t)
        val hit = pend.filter { (label, name) ->
            CommandNormalizer.normalizeName(label) == q ||
                CommandNormalizer.normalizeName(name) == q ||
                name.lowercase().startsWith(q) ||
                label.lowercase().startsWith(q)
        }
        if (hit.size == 1) {
            lastAmbiguous = emptyList()
            return Match(hit[0].second, null)
        }
        return null
    }

    private fun ordinalIndex(word: String): Int = when (word) {
        "first", "1" -> 0
        "second", "2" -> 1
        "third", "3" -> 2
        "fourth", "4" -> 3
        "fifth", "5" -> 4
        else -> -1
    }

    fun clearPending() { lastAmbiguous = emptyList() }

    private fun matched(c: Contact, query: String) =
        Match(c.name.replaceFirstChar { it.titlecase() }, c.phone)

    private fun ambiguous(list: List<Pair<String, String>>): Match {
        lastAmbiguous = list
        return Match(null, null, list.map { it.first })
    }

    private fun label(c: Contact): String =
        c.name.replaceFirstChar { it.titlecase() } +
            (c.phone?.takeLast(4)?.let { " (…$it)" } ?: "")

    private fun checkSelfPermission(ctx: Context): Int = try {
        ctx.checkPermission(Manifest.permission.READ_CONTACTS, android.os.Process.myPid(), android.os.Process.myUid())
    } catch (_: Exception) { PackageManager.PERMISSION_DENIED }

    private fun load(ctx: Context): List<Contact> {
        val out = LinkedHashMap<String, Contact>() // dedupe on name+number
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cur ->
                val ni = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cur.moveToNext()) {
                    val n = cur.getString(ni) ?: continue
                    val p = cur.getString(pi)
                    out.putIfAbsent("$n|$p", Contact(n, p))
                }
            }
        } catch (_: SecurityException) { return emptyList() }
        return out.values.toList()
    }

    // ponytail: plain Levenshtein — contact lists are small, O(n*m) is fine.
    private fun distance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private fun editBudget(q: String): Int = when {
        q.length <= 3 -> 0   // short names: exact or prefix only (no "kat"->"sam")
        q.length <= 6 -> 1
        else -> 2
    }
}
