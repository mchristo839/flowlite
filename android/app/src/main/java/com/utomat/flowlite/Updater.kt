package com.utomat.flowlite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Over-the-air updates from the public GitHub Releases page. Each release carries flowlite-android.json
 * (version_code, version_name, sha256, tag, apk) next to the APK. The APK must match that SHA-256 and be
 * this package signed by the same key as the installed app, so a tampered download cannot get a different
 * app installed through the update prompt. Works without signing in to a sync server.
 */
object Updater {
    const val RELEASES = "https://github.com/mchristo839/flowlite/releases"
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L
    private val SAFE = Regex("^[A-Za-z0-9._-]+$")
    private val mutex = Mutex()

    fun installedVersion(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "FlowLite-Android")
    }

    /** Downloads the newest release if it is newer than this app. Returns the verified APK, or null when up to date. */
    suspend fun download(ctx: Context): File? = mutex.withLock {
        val m = open("$RELEASES/latest/download/flowlite-android.json")
        val manifest = try {
            when (m.responseCode) {
                200 -> JSONObject(m.inputStream.bufferedReader().use { it.readText() })
                404 -> return null // no release published yet
                else -> throw IOException("Update check failed ${m.responseCode}")
            }
        } finally { m.disconnect() }
        val version = manifest.getLong("version_code")
        if (version <= installedVersion(ctx)) return null
        val sha = manifest.getString("sha256")
        val tag = manifest.getString("tag")
        val name = manifest.getString("apk")
        if (!SAFE.matches(tag) || !SAFE.matches(name) || !name.endsWith(".apk")) throw IOException("Update manifest is malformed")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "flowlite-$version.apk")
        if (apk.exists() && sha256(apk) == sha && sameApp(ctx, apk)) return apk
        dir.listFiles()?.forEach { it.delete() }

        val c = open("$RELEASES/download/$tag/$name")
        val part = File(dir, "download.part")
        try {
            if (c.responseCode != 200) throw IOException("Update download failed ${c.responseCode}")
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } finally { c.disconnect() }
        if (sha256(part) != sha) { part.delete(); throw IOException("Update checksum mismatch") }
        if (!sameApp(ctx, part)) { part.delete(); throw IOException("Update is not signed by FlowLite") }
        part.renameTo(apk)
        return apk
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Set<String> = when {
        info == null -> emptySet()
        Build.VERSION.SDK_INT >= 28 -> info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        else -> info.signatures.orEmpty().map { it.toCharsString() }.toSet()
    }

    /** True when [apk] is this app's package and carries exactly the installed app's signing certificates. */
    @Suppress("DEPRECATION")
    fun sameApp(ctx: Context, apk: File): Boolean {
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, flag) ?: return false
        val mine = signers(pm.getPackageInfo(ctx.packageName, flag))
        return archive.packageName == ctx.packageName && mine.isNotEmpty() && signers(archive) == mine
    }

    fun installIntent(ctx: Context, apk: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Background check, at most every 6 hours. Shows one notification per new version; tapping it opens the installer. */
    suspend fun checkInBackground(ctx: Context) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < CHECK_EVERY_MS) return
        prefs.lastUpdateCheck = now
        val apk = runCatching { download(ctx) }.getOrNull() ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(ctx, 2, installIntent(ctx, apk), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2, NotificationCompat.Builder(ctx, "updates").setSmallIcon(R.drawable.ic_parakeet)
            .setContentTitle("FlowLite update ready").setContentText("Tap to install")
            .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).build())
    }
}
