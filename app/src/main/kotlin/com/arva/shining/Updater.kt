package com.arva.shining

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update for this sideloaded app: checks the GitHub Releases API for a newer version and, since
 * releases are signed with the permanent key, downloads the release APK and hands it to Android's
 * package installer (which installs it straight over the running app). No Play Services, no tracking.
 */
object Updater {
    private const val REPO = "ar-va/shining-app"
    private const val UA = "Shining-Updater" // generic client name; never any user/personal info
    private val json = Json { ignoreUnknownKeys = true }

    data class Release(val version: String, val notes: String, val apkUrl: String)

    /** The latest published release, or null if it can't be fetched or has no APK asset. */
    suspend fun checkLatest(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val obj = json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            val apk = obj["assets"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                ?: return@withContext null
            Release(
                version = tag.removePrefix("v"),
                notes = (obj["body"]?.jsonPrimitive?.contentOrNull).orEmpty().trim(),
                apkUrl = apk,
            )
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** True when [latest] (e.g. "1.12.0") is a higher dotted version than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split(".", "-").map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the APK to the app cache, returning the file. */
    suspend fun download(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** API 26+: whether the user has allowed this app to install packages. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the "install unknown apps" screen for this app. */
    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Launches the system package installer for the downloaded [file]. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
