package com.utomat.flowlite

/** Text cleanup that runs on every transcript. Pure functions so they can be unit tested. */
object Cleanup {
    enum class Style { CASUAL, FORMAL, DEFAULT, VERY_CASUAL }

    data class Entry(val word: String, val replacement: String?)

    private val CASUAL_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.facebook.orca",
        "com.discord", "com.instagram.android", "com.slack", "com.viber.voip",
    )
    private val FORMAL_APPS = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
        "com.google.android.apps.docs.editors.docs", "com.microsoft.office.word", "com.google.android.keep",
        "notion.id", "com.samsung.android.app.notes", "com.proton.android.mail",
    )

    fun styleFor(packageName: String?): Style = when (packageName) {
        in CASUAL_APPS -> Style.CASUAL
        in FORMAL_APPS -> Style.FORMAL
        else -> Style.DEFAULT
    }

    fun clean(
        text: String,
        dictionary: List<Entry> = emptyList(),
        removeFillers: Boolean = true,
        style: Style = Style.DEFAULT,
        language: String = "auto",
    ): String {
        var t = scratchThat(text)
        if (removeFillers) t = removeFillers(t)
        t = spokenPunctuation(t)
        t = capitalise(t, english = language == "en")
        t = applyDictionary(t, dictionary)
        return applyStyle(t, style)
    }

    private val SCRATCH = Regex("""(?i)\bscratch that\b[.,!?]?""")

    /** "Monday. Actually Tuesday, scratch that, Wednesday" -> "Monday. Wednesday" */
    fun scratchThat(text: String): String {
        var t = text
        while (true) {
            val m = SCRATCH.find(t) ?: return tidy(t)
            val before = t.substring(0, m.range.first).trimEnd(' ', ',', '.', '!', '?')
            val cut = before.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
            t = before.substring(0, cut + 1) + " " + t.substring(m.range.last + 1).trimStart(' ', ',')
        }
    }

    private val FILLERS = Regex("""(?i)(^|[\s,])(?:u+m+|u+h+|uhm|e+rm|e+r|a+h|h+m+|m+h*m+)(?=[\s,.!?]|$)[,]?""")

    fun removeFillers(text: String): String = tidy(FILLERS.replace(text) { it.groupValues[1] })

    private val SPOKEN = listOf(
        "new paragraph" to "\n\n", "new line" to "\n",
        "question mark" to "?", "exclamation mark" to "!", "exclamation point" to "!",
        "full stop" to ".", "period" to ".", "comma" to ",", "semicolon" to ";", "colon" to ":",
    ).map { (words, mark) -> Regex("""(?i)[,.]?\s*\b$words\b[,.!?]?""") to mark }

    fun spokenPunctuation(text: String): String {
        var t = text
        for ((re, mark) in SPOKEN) t = re.replace(t) { mark }
        return tidy(t)
    }

    /** Capitalises sentence starts. The lone "i" fix is English only ("i" is a word in Italian). */
    fun capitalise(text: String, english: Boolean = false): String {
        val sb = StringBuilder(text)
        var start = true
        for (i in sb.indices) {
            val c = sb[i]
            val endsSentence = c in ".!?" && (i + 1 == sb.length || sb[i + 1].isWhitespace())
            if (start && c.isLetter()) { sb.setCharAt(i, c.uppercaseChar()); start = false }
            else if (endsSentence || c == '\n') start = true
            else if (!c.isWhitespace() && c != '"' && c != '\'') start = false
        }
        val out = sb.toString()
        return if (english) Regex("""\bi\b(?=['’\s,.!?]|$)""").replace(out, "I") else out
    }

    const val MAX_WORD = 60
    const val MAX_REPLACEMENT = 4000

    fun applyDictionary(text: String, entries: List<Entry>): String {
        // Synced rows are untrusted: same bounds and control-character rule as the Linux client.
        val active = entries.filter { it.word.isNotBlank() && it.word.trim().length <= MAX_WORD && (it.replacement?.length ?: 0) <= MAX_REPLACEMENT }
            .sortedByDescending { it.word.trim().length } // longest phrase wins at a position
        if (active.isEmpty()) return text
        val targets = active.associate { e ->
            e.word.trim().lowercase() to (e.replacement?.takeIf { it.isNotBlank() } ?: e.word.trim())
                .filter { !it.isISOControl() || it == '\n' || it == '\t' }
        }
        val alternatives = active.joinToString("|") { Regex.escape(it.word.trim()) }
        val re = Regex("""(?i)(?<![\p{L}\p{N}])(?:$alternatives)(?![\p{L}\p{N}])""")
        // One pass over the original text: inserted text is never expanded again.
        return re.replace(text) { m -> targets[m.value.lowercase()] ?: m.value }
    }

    fun applyStyle(text: String, style: Style): String = when (style) {
        Style.CASUAL -> if (text.endsWith(".") && !text.endsWith("..")) text.dropLast(1) else text
        Style.FORMAL -> if (text.isNotEmpty() && text.last() !in ".!?:…\n") "$text." else text
        Style.DEFAULT -> text
        Style.VERY_CASUAL -> applyStyle(text, Style.CASUAL).let { t ->
            // Lowercase the opening word unless it is "I" or an acronym.
            if (t.length > 1 && t[0].isUpperCase() && t[1].isLowerCase()) t[0].lowercase() + t.substring(1) else t
        }
    }

    /** Fixes spacing and stray punctuation left behind by the other rules. */
    fun tidy(text: String): String = text
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex(""" ([,.!?;:])"""), "$1")
        .replace(Regex("""([,;:])(?=[,.!?;:])"""), "")
        .replace(Regex("""([.!?]),+"""), "$1")
        .replace(Regex("""([!?])\."""), "$1")
        .replace(Regex(""" *\n *"""), "\n")
        .replace(Regex("""^[\s,.;:]+"""), "")
        .trim()
}
