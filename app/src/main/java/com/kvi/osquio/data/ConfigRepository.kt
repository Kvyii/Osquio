package com.kvi.osquio.data

import com.kvi.osquio.data.model.Config
import io.github.jan.supabase.postgrest.from

object ConfigRepository {

    suspend fun getConfig(): Config =
        supabase.from("config").select().decodeSingle<Config>()

    suspend fun updateConfig(cooldownSeconds: Int, maxAheadMinutes: Int) {
        supabase.from("config").update({
            set("summon_cooldown_seconds", cooldownSeconds)
            set("max_summon_ahead_minutes", maxAheadMinutes)
            set("updated_at", "now()")
        }) {
            filter { gt("id", "") }
        }
    }
}
