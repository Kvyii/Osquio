package com.kvi.osquio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val id: String? = null,
    @SerialName("summon_cooldown_seconds") val summonCooldownSeconds: Int = 900,
    @SerialName("max_summon_ahead_minutes") val maxSummonAheadMinutes: Int = 120,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
