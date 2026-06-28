package com.kvi.osquio.data

import com.kvi.osquio.data.model.SummonHistory
import io.github.jan.supabase.postgrest.from

object HistoryRepository {

    suspend fun allHistory(): List<SummonHistory> =
        supabase.from("summon_history").select().decodeList<SummonHistory>()

    suspend fun historyForDay(datePrefix: String): List<SummonHistory> =
        supabase.from("summon_history").select {
            filter {
                gte("game_time", "${datePrefix}T00:00:00Z")
                lt("game_time", nextDayPrefix(datePrefix) + "T00:00:00Z")
            }
        }.decodeList<SummonHistory>()

    suspend fun historyDetail(id: String): SummonHistory =
        supabase.from("summon_history").select {
            filter { eq("id", id) }
        }.decodeSingle<SummonHistory>()

    private fun nextDayPrefix(datePrefix: String): String {
        val parts = datePrefix.split("-").map { it.toInt() }
        val (y, m, d) = Triple(parts[0], parts[1], parts[2])
        val daysInMonth = java.util.Calendar.getInstance().apply {
            set(y, m - 1, 1)
        }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        return if (d < daysInMonth) "%04d-%02d-%02d".format(y, m, d + 1)
        else if (m < 12) "%04d-%02d-%02d".format(y, m + 1, 1)
        else "%04d-%02d-%02d".format(y + 1, 1, 1)
    }
}
