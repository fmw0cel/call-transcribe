package com.localtranscribe.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppRelease(
    val version: String,
    val apkUrl: String,
    val releasePageUrl: String,
)

object AppReleaseChecker {

    private const val REPO = "fmw0cel/call-transcribe"
    private const val APK_NAME = "app-debug.apk"

    suspend fun fetchLatest(appVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val connection = open("https://api.github.com/repos/$REPO/releases/latest", appVersion)
        try {
            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            val htmlUrl = json.getString("html_url")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == APK_NAME) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            val url = apkUrl ?: return@withContext null
            AppRelease(
                version = AppVersion.normalize(tag),
                apkUrl = url,
                releasePageUrl = htmlUrl,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, appVersion: String = ""): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "LocalTranscribe/$appVersion")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("릴리스를 확인하지 못했습니다 (HTTP $code).")
        }
        return connection
    }
}
