package com.kvi.osquio.data

import com.kvi.osquio.data.model.Message
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

object ChatRepository {

    private const val MESSAGE_LIMIT = 30L

    suspend fun getMessages(): List<Message> =
        supabase.from("messages").select {
            order("created_at", order = Order.ASCENDING)
            limit(MESSAGE_LIMIT)
        }.decodeList<Message>()

    suspend fun sendMessage(userId: String, content: String) {
        supabase.from("messages").insert(
            mapOf(
                "user_id" to userId,
                "content" to content,
            )
        )
    }
}
