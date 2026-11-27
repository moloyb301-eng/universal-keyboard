package com.universalkeyboard.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from the GitHub "nightly" release.
 *
 * CI uploads two files there: universal-keyboard.apk and version.json
 * ({"versionCode": <build number>, "versionName": ..., "notes": ...}).
 * Network calls block, so call them off the main thread.
 */
object Updater {
    private const val RELEASE_BASE =
        "https://github.com/moloyb301-eng/universal-keyboard/releases/download/nightly/"
    private const val APK_NAME = "universal-keyboard.apk"

    data class Release(val versionCode: Long, val versionName: String, val notes: String)

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Latest published build. */
    fun fetchLatest(): Release {
        val json = JSONObject(open(RELEASE_BASE + "version.json").inputStream.bufferedReader().use { it.readText() })
        return Release(
            versionCode = json.getLong("versionCode"),
            versionName = json.optString("versionName", "?"),
            notes = json.optString("notes", ""),
        )
    }

    /** Downloads the APK into the app's cache, reporting progress 0–100 (-1 if size unknown). */
    fun download(context: Context, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, APK_NAME)
        val conn = open(RELEASE_BASE + APK_NAME)
        val total = conn.contentLengthLong
        var done = 0L
        var lastPercent = -2
        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
        }
        if (total > 0 && done != total) throw IOException("Download incomplete")
        return file
    }

    /**
     * Hands the APK to Android's package installer. Android shows its own
     * "Update this app?" confirmation; the result arrives in [UpdateReceiver].
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(callback.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "UniversalKeyboard-Updater")
        conn.setRequestProperty("Cache-Control", "no-cache")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("Server returned $code")
        }
        return conn
    }
}
