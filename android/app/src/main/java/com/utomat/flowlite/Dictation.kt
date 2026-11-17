package com.utomat.flowlite

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Shared by the bubble and the keyboard: one engine per process, then VAD, cleanup, optional AI rewrite and save. */
object Dictation {
    private var engine: Engine? = null
    private var engineKey = ""
    private var trimmer: SpeechTrimmer? = null
    private val lock = Mutex()

    @Synchronized
    fun ensureEngine(ctx: Context): Engine {
        val prefs = Prefs(ctx)
        val key = prefs.engine + "|" + prefs.cloudUrl + "|" + prefs.cloudModel
        engine?.let { if (key == engineKey) return it }
        engine?.close(); engine = null
        val specs = Models.forEngine(prefs.engine)
        check(specs.all { it.isReady(ctx) }) { "Download the ${prefs.engine} model in FlowLite first" }
        if (trimmer == null) trimmer = SpeechTrimmer(ctx.applicationContext)
        engine = when (prefs.engine) {
            "whisper" -> SherpaEngine(ctx.applicationContext, whisper = true)
            "cloud" -> CloudEngine(prefs.cloudUrl, prefs.cloudKey, prefs.cloudModel)
            else -> SherpaEngine(ctx.applicationContext, whisper = false)
        }
        engineKey = key
        return engine!!
    }

    /**
     * Returns the saved transcript, or null when there was no speech.
     * [selection] is the highlighted text in the field. With AI rewrite on, the dictation becomes an instruction for it.
     * [private] (password and incognito fields) skips AI rewrite and is never saved or synced.
     */
    suspend fun transcribe(ctx: Context, audio: FloatArray, durationMs: Long, pkg: String?, selection: String?,
                           private: Boolean = false): Transcript? {
        val e = ensureEngine(ctx)
        val db = FlowDb.get(ctx)
        val language = db.settings().get(SyncRules.settingId("language"))?.takeIf { !it.deleted }
            ?.let { SyncRules.parseJson(it.valueJson) as? String } ?: "auto"
        val fillers = db.settings().get(SyncRules.settingId("remove_filler_words"))?.takeIf { !it.deleted }
            ?.let { SyncRules.parseJson(it.valueJson) as? Boolean } ?: true
        val t0 = System.currentTimeMillis()
        val raw = lock.withLock {
            val segments = trimmer!!.segments(audio)
            if (segments.isEmpty()) "" else e.transcribe(segments, language)
        }
        val transcribeMs = System.currentTimeMillis() - t0
        if (raw.isBlank()) return null
        val dict = db.dictionary().active().map { Cleanup.Entry(it.word, it.replacement) }
        val prefs = Prefs(ctx)
        var text = Cleanup.clean(raw, dict, fillers, prefs.style(Cleanup.styleFor(pkg)), language)
        if (prefs.aiRewrite && !private) text = runCatching { Rewrite.run(prefs, text, selection) }
            .onFailure { Log.w("FlowLite", "AI rewrite failed", it) }.getOrDefault(text)
        val now = System.currentTimeMillis()
        val t = Transcript(UUID.randomUUID().toString(), text, raw, "android", pkg, e.name, e.model, language,
            durationMs, transcribeMs, now, now)
        if (!private) db.transcripts().upsert(t)
        return t
    }
}

/** Optional AI rewrite through any OpenAI-compatible /chat/completions endpoint. Off by default; text leaves the device. */
object Rewrite {
    const val DEFAULT_PROMPT = "Fix grammar and punctuation. Keep the wording and language. Return only the corrected text."
    private const val COMMAND_PROMPT = "Apply the instruction to the text. Return only the resulting text, nothing else."

    /** Returns (system, user) messages. A selection turns the dictation into an instruction for that selection. */
    fun messages(prompt: String, dictated: String, selection: String?): Pair<String, String> =
        if (!selection.isNullOrBlank()) COMMAND_PROMPT to "Instruction: $dictated\n\nText:\n$selection"
        else prompt.ifBlank { DEFAULT_PROMPT } to dictated

    fun endpoint(base: String) = base.trimEnd('/').removeSuffix("/audio/transcriptions").removeSuffix("/chat/completions") + "/chat/completions"

    fun run(prefs: Prefs, dictated: String, selection: String?): String {
        val (system, user) = messages(prefs.aiPrompt, dictated, selection)
        val body = JSONObject().put("model", prefs.aiModel).put("temperature", 0).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user)))
        val c = URL(endpoint(prefs.cloudUrl)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer ${prefs.cloudKey}")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) throw IOException("AI rewrite HTTP ${c.responseCode}")
        val out = JSONObject(c.inputStream.bufferedReader().readText())
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        check(out.isNotEmpty()) { "Empty AI response" }
        return out
    }
}
