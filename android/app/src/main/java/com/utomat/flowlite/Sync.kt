package com.utomat.flowlite

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Pure sync rules from docs/sync-api.md, kept free of Android types for unit tests. */
object SyncRules {
    private val NAMESPACE_URL: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    /** Last write wins. A tie keeps the local copy (it is identical or already queued). */
    fun remoteWins(localUpdatedAt: Long?, remoteUpdatedAt: Long) =
        localUpdatedAt == null || remoteUpdatedAt > localUpdatedAt

    fun dictionaryId(word: String) = uuid5("flowlite:dictionary:" + word.trim().lowercase())
    fun settingId(key: String) = uuid5("flowlite:settings:$key")

    fun uuid5(name: String): String {
        val ns = ByteBuffer.allocate(16).putLong(NAMESPACE_URL.mostSignificantBits)
            .putLong(NAMESPACE_URL.leastSignificantBits).array()
        val h = MessageDigest.getInstance("SHA-1").apply { update(ns); update(name.toByteArray()) }.digest()
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        val b = ByteBuffer.wrap(h, 0, 16)
        return UUID(b.long, b.long).toString()
    }

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Pull filter value: the stored cursor minus a 2 second overlap, or "" for a full pull. */
    fun pullFrom(cursor: String): String {
        if (cursor.isEmpty()) return ""
        val t = fmt().parse(cursor) ?: return ""
        return fmt().format(java.util.Date(t.time - 2000))
    }

    fun maxCursor(a: String, b: String) = if (b > a) b else a

    fun parseJson(s: String): Any = org.json.JSONTokener(s).nextValue()
    fun toJson(v: Any?): String = if (v is String) JSONObject.quote(v) else (v ?: JSONObject.NULL).toString()
}

class SyncException(val code: Int, msg: String) : IOException(msg)

/** Talks to PocketBase. Uses HttpURLConnection so the app needs no HTTP library. */
class SyncClient(private val base: String, private val token: String?) {
    fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/json")
            token?.let { c.setRequestProperty("Authorization", it) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to (if (text.isBlank()) JSONObject() else JSONObject(text))
        } finally {
            c.disconnect()
        }
    }

    fun login(email: String, password: String): JSONObject {
        val (code, r) = call("POST", "/api/collections/users/auth-with-password",
            JSONObject().put("identity", email).put("password", password))
        if (code != 200) throw SyncException(code, r.optString("message", "Login failed"))
        return r
    }

    /** Returns true when the record is settled on the server (stored, or server has newer). */
    fun push(collection: String, id: String, body: JSONObject): Boolean {
        val (code, r) = call("PATCH", "/api/collections/$collection/records/$id", body)
        val final = if (code == 404) call("POST", "/api/collections/$collection/records", JSONObject(body.toString()).put("id", id)).first else code
        return when {
            final == 200 || final == 409 -> true
            final == 401 || final == 403 -> throw SyncException(final, "Sign in again")
            final == 400 -> { Log.w("FlowLite", "Server rejected $collection/$id: $r"); true }
            else -> throw SyncException(final, "Server error $final")
        }
    }

    fun pullPage(collection: String, from: String, page: Int): List<JSONObject> {
        val filter = URLEncoder.encode("(synced_at>=\"$from\")", "UTF-8")
        val (code, r) = call("GET", "/api/collections/$collection/records?filter=$filter&sort=synced_at,id&perPage=500&page=$page&skipTotal=1")
        if (code == 401 || code == 403) throw SyncException(code, "Sign in again")
        if (code != 200) throw SyncException(code, "Pull failed $code")
        val items = r.getJSONArray("items")
        return List(items.length()) { items.getJSONObject(it) }
    }
}

object SyncManager {
    private const val TAG = "FlowLiteSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var retryJob: Job? = null
    private var failures = 0
    val status = MutableStateFlow("")

    fun request(ctx: Context) {
        scope.launch { sync(ctx.applicationContext) }
    }

    /** Runs one push + pull cycle. Never throws. On failure schedules a retry with backoff. */
    suspend fun sync(ctx: Context): Boolean = mutex.withLock {
        val prefs = Prefs(ctx)
        purgeOld(FlowDb.get(ctx))
        val token = prefs.token
        if (token == null || prefs.userId == null) { setStatus(prefs, "Not signed in"); return false }
        return try {
            val db = FlowDb.get(ctx)
            val client = SyncClient(prefs.serverUrl, token)
            refreshToken(prefs, client)
            pushAll(db, client, prefs.userId!!)
            pullAll(db, client, prefs)
            if (purgeOld(db)) pushAll(db, client, prefs.userId!!) // old rows pulled from other devices
            failures = 0
            setStatus(prefs, "Synced " + java.text.DateFormat.getTimeInstance().format(java.util.Date()))
            true
        } catch (e: SyncException) {
            if (e.code == 401 || e.code == 403) { prefs.token = null; setStatus(prefs, "Sign in again") }
            else scheduleRetry(ctx, prefs, e)
            false
        } catch (e: Exception) {
            scheduleRetry(ctx, prefs, e)
            false
        }
    }

    /** Applies the synced "history_days" setting. Returns true if anything was deleted. */
    private suspend fun purgeOld(db: FlowDb): Boolean {
        val days = db.settings().get(SyncRules.settingId("history_days"))?.takeIf { !it.deleted }
            ?.let { SyncRules.parseJson(it.valueJson) as? Number }?.toInt() ?: 0
        if (days <= 0) return false
        return db.transcripts().purgeBefore(System.currentTimeMillis() - days * 86_400_000L) > 0
    }

    private fun setStatus(prefs: Prefs, s: String) { prefs.syncStatus = s; status.value = s }

    private fun scheduleRetry(ctx: Context, prefs: Prefs, e: Exception) {
        failures++
        val delayMs = minOf(5_000L shl minOf(failures - 1, 6), 300_000L)
        Log.w(TAG, "Sync failed ($failures), retry in ${delayMs / 1000}s: ${e.message}")
        setStatus(prefs, "Offline, retrying in ${delayMs / 1000}s")
        retryJob?.cancel()
        retryJob = scope.launch { delay(delayMs); sync(ctx) }
    }

    private fun refreshToken(prefs: Prefs, client: SyncClient) {
        if (System.currentTimeMillis() - prefs.lastRefresh < 86_400_000L) return
        val (code, r) = client.call("POST", "/api/collections/users/auth-refresh")
        if (code == 401 || code == 403) throw SyncException(code, "Sign in again")
        if (code == 200) { prefs.token = r.getString("token"); prefs.lastRefresh = System.currentTimeMillis() }
    }

    private suspend fun pushAll(db: FlowDb, c: SyncClient, owner: String) {
        for (t in db.transcripts().dirty()) {
            if (c.push("transcripts", t.id, transcriptJson(t).put("owner", owner))) db.transcripts().markClean(t.id, t.updatedAt)
        }
        for (w in db.dictionary().dirty()) {
            val body = JSONObject().put("owner", owner).put("word", w.word).put("replacement", w.replacement ?: "")
                .put("updated_at", w.updatedAt).put("deleted", w.deleted)
            if (c.push("dictionary", w.id, body)) db.dictionary().markClean(w.id, w.updatedAt)
        }
        for (s in db.settings().dirty()) {
            val body = JSONObject().put("owner", owner).put("key", s.key).put("value_json", SyncRules.parseJson(s.valueJson))
                .put("updated_at", s.updatedAt).put("deleted", s.deleted)
            if (c.push("settings", s.id, body)) db.settings().markClean(s.id, s.updatedAt)
        }
    }

    private suspend fun pullAll(db: FlowDb, c: SyncClient, prefs: Prefs) {
        for (col in listOf("transcripts", "dictionary", "settings")) {
            var cursor = prefs.cursor(col)
            val from = SyncRules.pullFrom(cursor)
            var page = 1
            while (true) {
                val items = c.pullPage(col, from, page)
                for (r in items) {
                    apply(db, col, r)
                    cursor = SyncRules.maxCursor(cursor, r.getString("synced_at"))
                }
                if (items.size < 500) break
                page++
            }
            prefs.setCursor(col, cursor)
        }
    }

    private suspend fun apply(db: FlowDb, col: String, r: JSONObject) {
        val id = r.getString("id")
        val remoteAt = r.getLong("updated_at")
        when (col) {
            "transcripts" -> if (SyncRules.remoteWins(db.transcripts().get(id)?.updatedAt, remoteAt)) db.transcripts().upsert(
                Transcript(id, r.optString("text"), r.optString("raw_text"), r.optString("device"), r.optString("app_name"),
                    r.optString("engine"), r.optString("model"), r.optString("language"), r.optLong("duration_ms"),
                    r.optLong("transcribe_ms"), r.optLong("created_at"), remoteAt, r.optBoolean("deleted"), dirty = false))
            "dictionary" -> if (SyncRules.remoteWins(db.dictionary().get(id)?.updatedAt, remoteAt)) db.dictionary().upsert(
                DictWord(id, r.getString("word"), r.optString("replacement").ifBlank { null }, remoteAt, r.optBoolean("deleted"), dirty = false))
            "settings" -> if (SyncRules.remoteWins(db.settings().get(id)?.updatedAt, remoteAt)) db.settings().upsert(
                SharedSetting(id, r.getString("key"), SyncRules.toJson(r.opt("value_json")),
                    remoteAt, r.optBoolean("deleted"), dirty = false))
        }
    }

    fun transcriptJson(t: Transcript): JSONObject = JSONObject()
        .put("text", t.text).put("raw_text", t.rawText).put("device", t.device).put("app_name", t.appName ?: "")
        .put("engine", t.engine ?: "").put("model", t.model ?: "").put("language", t.language ?: "")
        .put("duration_ms", t.durationMs).put("transcribe_ms", t.transcribeMs).put("created_at", t.createdAt)
        .put("updated_at", t.updatedAt).put("deleted", t.deleted)

    /** Every 5 minutes while the dictation service runs: sync when signed in, and the update check. */
    fun startPeriodic(ctx: Context): Job = scope.launch {
        while (true) {
            sync(ctx.applicationContext)
            Updater.checkInBackground(ctx.applicationContext) // throttled to every 6 hours, needs no sign-in
            delay(5 * 60_000L)
        }
    }
}
