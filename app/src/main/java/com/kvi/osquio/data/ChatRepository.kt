package com.kvi.osquio.data

import com.kvi.osquio.BuildConfig
import com.kvi.osquio.data.model.Message
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ChatRepository {

    private const val MESSAGE_LIMIT = 50L
    private val httpClient = HttpClient(OkHttp)

    private val _refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshSignal = _refreshSignal.asSharedFlow()

    fun signalRefresh() { _refreshSignal.tryEmit(Unit) }

    suspend fun getMessages(): List<Message> =
        supabase.from("messages").select {
            order("created_at", order = Order.DESCENDING)
            limit(MESSAGE_LIMIT)
        }.decodeList<Message>().reversed()

    suspend fun sendMessage(userId: String, content: String): Message =
        supabase.from("messages").insert(
            mapOf(
                "user_id" to userId,
                "content" to content,
            )
        ) { select() }.decodeSingle<Message>()

    suspend fun notifyMentions(
        senderId: String,
        senderName: String,
        messageId: String,
        mentionedUserIds: List<String>,
    ) {
        val token = supabase.auth.currentSessionOrNull()?.accessToken ?: return
        val body = buildJsonObject {
            put("message_id", messageId)
            put("sender_id", senderId)
            put("sender_name", senderName)
            put("mentioned_user_ids", buildJsonArray { mentionedUserIds.forEach { add(JsonPrimitive(it)) } })
        }
        httpClient.post("${BuildConfig.SUPABASE_URL}/functions/v1/notify-mention") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }
}
