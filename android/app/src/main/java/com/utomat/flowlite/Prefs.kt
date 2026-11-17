package com.utomat.flowlite

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Device-local settings. Secrets (sync token, cloud API key) live in EncryptedSharedPreferences. */
class Prefs(ctx: Context) {
    private val plain: SharedPreferences = ctx.getSharedPreferences("local", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = EncryptedSharedPreferences.create(
        ctx, "secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var serverUrl: String
        get() = plain.getString("server_url", "")!!
        set(v) = plain.edit { putString("server_url", v.trim().trimEnd('/')) }
    var email: String
        get() = plain.getString("email", "")!!
        set(v) = plain.edit { putString("email", v) }
    var token: String?
        get() = secure.getString("token", null)
        set(v) = secure.edit { putString("token", v) }
    var userId: String?
        get() = secure.getString("user_id", null)
        set(v) = secure.edit { putString("user_id", v) }
    /** "serverUrl|userId" of the account the local data belongs to. */
    var account: String
        get() = plain.getString("account", "")!!
        set(v) = plain.edit { putString("account", v) }
    /** When the app last asked GitHub Releases for a newer version. */
    var lastUpdateCheck: Long
        get() = plain.getLong("last_update_check", 0)
        set(v) = plain.edit { putLong("last_update_check", v) }
    var lastRefresh: Long
        get() = plain.getLong("last_refresh", 0)
        set(v) = plain.edit { putLong("last_refresh", v) }
    var syncStatus: String
        get() = plain.getString("sync_status", "Not signed in")!!
        set(v) = plain.edit { putString("sync_status", v) }

    fun cursor(collection: String): String = plain.getString("cursor_$collection", "")!!
    fun setCursor(collection: String, v: String) = plain.edit { putString("cursor_$collection", v) }

    /** "parakeet", "whisper" or "cloud" */
    var engine: String
        get() = plain.getString("engine", "parakeet")!!
        set(v) = plain.edit { putString("engine", v) }
    var cloudUrl: String
        get() = plain.getString("cloud_url", "")!!
        set(v) = plain.edit { putString("cloud_url", v) }
    var cloudModel: String
        get() = plain.getString("cloud_model", "whisper-1")!!
        set(v) = plain.edit { putString("cloud_model", v) }
    var cloudKey: String
        get() = secure.getString("cloud_key", "")!!
        set(v) = secure.edit { putString("cloud_key", v) }
    var aiRewrite: Boolean
        get() = plain.getBoolean("ai_rewrite", false)
        set(v) = plain.edit { putBoolean("ai_rewrite", v) }
    var aiPrompt: String
        get() = plain.getString("ai_prompt", Rewrite.DEFAULT_PROMPT)!!
        set(v) = plain.edit { putString("ai_prompt", v) }
    var aiModel: String
        get() = plain.getString("ai_model", "gpt-4o-mini")!!
        set(v) = plain.edit { putString("ai_model", v) }
    /** User's style for an app category; the category comes from [Cleanup.styleFor]. */
    fun style(category: Cleanup.Style): Cleanup.Style =
        runCatching { Cleanup.Style.valueOf(plain.getString("style_$category", category.name)!!) }.getOrDefault(category)
    fun setStyle(category: Cleanup.Style, v: Cleanup.Style) = plain.edit { putString("style_$category", v.name) }
    /** Bubble hidden until this time (ms). */
    var snoozeUntil: Long
        get() = plain.getLong("snooze_until", 0)
        set(v) = plain.edit { putLong("snooze_until", v) }
    var bubbleX: Int
        get() = plain.getInt("bubble_x", 0)
        set(v) = plain.edit { putInt("bubble_x", v) }
    var bubbleY: Int
        get() = plain.getInt("bubble_y", 400)
        set(v) = plain.edit { putInt("bubble_y", v) }
}
