package com.kvi.osquio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Rsvp(
    val id: String? = null,
    @SerialName("summon_id") val summonId: String,
    @SerialName("user_id") val userId: String,
    val response: String,
    @SerialName("response_time") val responseTime: String? = null,
    @SerialName("responded_at") val respondedAt: String? = null,
)
