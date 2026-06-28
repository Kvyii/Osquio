package com.kvi.osquio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Summon(
    val id: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("game_time") val gameTime: String,
    @SerialName("expires_at") val expiresAt: String,
    val status: String = "open",
    @SerialName("cancelled_by") val cancelledBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
