package com.kvi.osquio.data

import com.kvi.osquio.BuildConfig
import com.kvi.osquio.data.model.Rsvp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object RsvpRepository {

    private val httpClient = HttpClient(OkHttp)

    private val locallyResponded = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun hasRespondedLocally(summonId: String): Boolean = locallyResponded.contains(summonId)

    suspend fun rsvpsForSummon(summonId: String): List<Rsvp> =
        supabase.from("rsvps").select {
            filter { eq("summon_id", summonId) }
        }.decodeList<Rsvp>()

    suspend fun upsertRsvp(summonId: String, userId: String, response: String, responseTime: String?) {
        // Mark before the HTTP call so any FCM that arrives during the round-trip is suppressed.
        locallyResponded.add(summonId)
        val token = supabase.auth.currentSessionOrNull()?.accessToken ?: return
        val body = buildJsonObject {
            put("summon_id", summonId)
            put("user_id", userId)
            put("response", response)
            if (responseTime != null) put("response_time", responseTime)
        }
        val res = httpClient.post("${BuildConfig.SUPABASE_URL}/functions/v1/validate-rsvp") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (res.status.value !in 200..299) {
            throw Exception("RSVP failed (${res.status.value})")
        }
    }

}
