package com.kvi.osquio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class SteamProfile(val displayName: String, val avatarUrl: String)

object SteamApi {

    private val client = OkHttpClient()

    suspend fun fetchProfile(steamIdOrVanity: String): SteamProfile? = withContext(Dispatchers.IO) {
        val url = if (steamIdOrVanity.all { it.isDigit() }) {
            "https://steamcommunity.com/profiles/$steamIdOrVanity/?xml=1"
        } else {
            "https://steamcommunity.com/id/$steamIdOrVanity/?xml=1"
        }
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null
        parseXml(body)
    }

    private fun extractTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open).takeIf { it >= 0 }?.plus(open.length) ?: return null
        val end = xml.indexOf(close, start).takeIf { it >= 0 } ?: return null
        return xml.substring(start, end)
            .removePrefix("<![CDATA[").removeSuffix("]]>")
            .trim().takeIf { it.isNotEmpty() }
    }

    private fun parseXml(xml: String): SteamProfile? {
        val displayName = extractTag(xml, "steamID") ?: return null
        val avatarUrl = extractTag(xml, "avatarFull") ?: return null
        return SteamProfile(displayName, avatarUrl)
    }
}
