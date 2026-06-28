package com.kvi.osquio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SummonHistory(
    val id: String,
    @SerialName("summon_id") val summonId: String,
    @SerialName("summoner_id") val summonerId: String,
    @SerialName("game_time") val gameTime: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("closed_at") val closedAt: String,
    val status: String,
    @SerialName("cancelled_by") val cancelledBy: String? = null,
    val snapshot: JsonObject,
)

@Serializable
data class SnapshotRespondent(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val response: String,
    @SerialName("response_time") val responseTime: String? = null,
    @SerialName("responded_at") val respondedAt: String,
)

@Serializable
data class SnapshotNonRespondent(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
