package com.kvi.osquio.util

import com.kvi.osquio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(val version: String, val downloadUrl: String)

class UpdateChecker(
    private val repoOwner: String = BuildConfig.GITHUB_REPO_OWNER,
    private val repoName: String = BuildConfig.GITHUB_REPO_NAME,
) {
    private val client = OkHttpClient()

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body!!.string())
            val latestTag = json.getString("tag_name")
            if (!isUpdateAvailable(currentVersion, latestTag)) return@withContext null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    return@withContext UpdateInfo(latestTag, asset.getString("browser_download_url"))
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isUpdateAvailable(current: String, latest: String): Boolean {
        val c = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val l = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }
}
