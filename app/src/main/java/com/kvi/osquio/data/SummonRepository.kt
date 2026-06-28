package com.kvi.osquio.data

import com.kvi.osquio.data.model.Summon
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object SummonRepository {

    suspend fun activeSummon(): Summon? =
        supabase.from("summons").select {
            filter { eq("status", "open") }
        }.decodeSingleOrNull<Summon>()

    suspend fun createSummon(createdBy: String, gameTime: String): Summon {
        val summon = supabase.from("summons").insert(
            mapOf(
                "created_by" to createdBy,
                "game_time" to gameTime,
                "expires_at" to gameTime,
            )
        ) { select() }.decodeSingle<Summon>()

        supabase.from("rsvps").insert(
            mapOf(
                "summon_id" to summon.id,
                "user_id" to createdBy,
                "response" to "yes",
            )
        )

        return summon
    }

    suspend fun cancelSummon(summonId: String, cancelledBy: String) {
        supabase.from("summons").update({
            set("status", "cancelled")
            set("cancelled_by", cancelledBy)
        }) {
            filter { eq("id", summonId) }
        }
    }

    suspend fun triggerRebeacon(summonId: String) {
        val summon = supabase.from("summons").select {
            filter { eq("id", summonId) }
        }.decodeSingle<Summon>()

        val body = buildJsonObject {
            putJsonObject("record") {
                put("id", summon.id)
                put("game_time", summon.gameTime)
                put("user_id", summon.createdBy)
            }
        }

        supabase.functions.invoke("notify-summon") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }
}
