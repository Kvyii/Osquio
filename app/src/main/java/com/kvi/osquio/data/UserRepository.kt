package com.kvi.osquio.data

import com.kvi.osquio.data.model.User
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from

object UserRepository {

    suspend fun currentUser(): User {
        val authId = supabase.auth.currentUserOrNull()?.id ?: error("Not logged in")
        return supabase.from("users").select {
            filter { eq("auth_id", authId) }
        }.decodeSingle<User>()
    }

    suspend fun allUsers(): List<User> =
        supabase.from("users").select().decodeList<User>()

    suspend fun updateFcmToken(userId: String, token: String) {
        supabase.from("users").update({ set("fcm_token", token) }) {
            filter { eq("id", userId) }
        }
    }

    suspend fun updateSteamCache(userId: String, displayName: String, avatarUrl: String) {
        supabase.from("users").update({
            set("display_name", displayName)
            set("avatar_url", avatarUrl)
            set("cache_at", "now()")
        }) {
            filter { eq("id", userId) }
        }
    }

    suspend fun updateAvatarCache(userId: String, avatarUrl: String) {
        supabase.from("users").update({
            set("avatar_url", avatarUrl)
            set("cache_at", "now()")
        }) {
            filter { eq("id", userId) }
        }
    }
}
