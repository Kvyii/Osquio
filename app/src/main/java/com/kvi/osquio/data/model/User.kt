package com.kvi.osquio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    @SerialName("auth_id") val authId: String? = null,
    @SerialName("steam_id") val steamId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("cache_at") val cacheAt: String? = null,
    @SerialName("fcm_token") val fcmToken: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("cooldown_until") val cooldownUntil: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
