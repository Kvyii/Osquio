package com.kvi.osquio.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kvi.osquio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

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

    suspend fun fetchReleaseNotes(version: String): String? = withContext(Dispatchers.IO) {
        try {
            val tag = "v$version"
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/tags/$tag"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body!!.string())
            json.optString("body").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadAndInstall(context: Context, downloadUrl: String, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            val body = response.body ?: return@withContext

            val cacheDir = File(context.cacheDir, "apk").also { it.mkdirs() }
            val apkFile = File(cacheDir, "update.apk")

            val total = body.contentLength()
            var downloaded = 0L
            apkFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress((downloaded * 100 / total).toInt())
                    }
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(install)
        }

    private fun isUpdateAvailable(current: String, latest: String): Boolean {
        val numericPart = Regex("""^[\d.]+""")
        val c = (numericPart.find(current.trim().removePrefix("v"))?.value ?: "0").split(".").map { it.toIntOrNull() ?: 0 }
        val l = (numericPart.find(latest.trim().removePrefix("v"))?.value ?: "0").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }
}
