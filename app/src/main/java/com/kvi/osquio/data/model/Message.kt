package com.kvi.osquio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    @SerialName("user_id") val userId: String,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("image_url") val imageUrl: String? = null,
)
