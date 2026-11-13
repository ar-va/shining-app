package com.arva.shining

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check for this sideloaded app: asks the GitHub Releases API whether a newer version exists.
 *
 * It deliberately does NOT download or install the APK itself. An app that holds
 * REQUEST_INSTALL_PACKAGES and fetches an APK from the network to install trips Google Play Protect's
 * "downloads and installs apps" (dropper) heuristic and gets flagged as harmful. Instead we open the
 * release download in the browser, which installs it through the normal, trusted system flow.
 */
object Updater {
    private const val REPO = "ar-va/shining-app"
    private const val UA = "Shining-Updater" // generic client name; never any user/personal info
    private val json = Json { ignoreUnknownKeys = true }

    data class Release(val version: String, val notes: String, val downloadUrl: String)

    /** The latest published release, or null if it can't be fetched. */
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
            val page = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$REPO/releases/latest"
            // Prefer the APK asset's direct link (browser downloads it straight away); fall back to the page.
            val apk = obj["assets"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            Release(
                version = tag.removePrefix("v"),
                notes = (obj["body"]?.jsonPrimitive?.contentOrNull).orEmpty().trim(),
                downloadUrl = apk ?: page,
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

    /** Opens the release download in the browser; it handles the download + install the trusted way. */
    fun openDownload(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
