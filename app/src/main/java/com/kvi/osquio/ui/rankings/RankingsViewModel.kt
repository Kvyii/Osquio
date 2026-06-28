package com.kvi.osquio.ui.rankings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.R
import com.kvi.osquio.data.HistoryRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.SummonHistory
import com.kvi.osquio.data.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

data class RankedEntry(val rank: Int, val user: User, val detail: String)
data class Badge(val iconRes: Int, val name: String, val holder: User?, val detail: String, val podium: List<RankedEntry> = emptyList())

sealed interface RankingsUiState {
    data object Loading : RankingsUiState
    data class Loaded(val badges: List<Badge>, val isThisMonth: Boolean) : RankingsUiState
    data class Error(val message: String) : RankingsUiState
}

class RankingsViewModel : ViewModel() {

    private val _state = MutableStateFlow<RankingsUiState>(RankingsUiState.Loading)
    val state = _state.asStateFlow()

    private var allHistory: List<SummonHistory> = emptyList()
    private var allUsers: List<User> = emptyList()
    private var isThisMonth = true

    fun load() {
        viewModelScope.launch {
            try {
                allHistory = HistoryRepository.allHistory()
                allUsers = UserRepository.allUsers()
                recalculate()
            } catch (e: Exception) {
                _state.value = RankingsUiState.Error(e.message ?: "Failed to load rankings")
            }
        }
    }

    private fun <T> buildPodium(sorted: List<Map.Entry<User, T>>, format: (Map.Entry<User, T>) -> String): List<RankedEntry> {
        val result = mutableListOf<RankedEntry>()
        var rank = 1
        var i = 0
        while (i < sorted.size && result.size < 3) {
            val currentVal = sorted[i].value
            val group = sorted.drop(i).takeWhile { it.value == currentVal }
            group.forEach { result.add(RankedEntry(rank, it.key, format(it))) }
            rank += group.size
            i += group.size
            if (rank > 3) break
        }
        return result
    }

    fun setFilter(thisMonth: Boolean) {
        isThisMonth = thisMonth
        recalculate()
    }

    private fun recalculate() {
        val thisMonth = YearMonth.now()
        val filtered = if (isThisMonth) {
            allHistory.filter { h ->
                runCatching {
                    val t = OffsetDateTime.parse(h.closedAt).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    YearMonth.of(t.year, t.month) == thisMonth
                }.getOrDefault(false)
            }
        } else allHistory

        val summonsSent = allUsers.associateWith { u -> filtered.count { it.summonerId == u.id } }
        val ignored = mutableMapOf<User, Int>()
        val rejected = mutableMapOf<User, Int>()
        val attended = mutableMapOf<User, Int>()
        val responseTimes = mutableMapOf<User, MutableList<Long>>()

        filtered.forEach { history ->
            val respondents = history.snapshot["respondents"] as? JsonArray ?: return@forEach
            val nonRespondents = history.snapshot["non_respondents"] as? JsonArray ?: return@forEach

            nonRespondents.forEach { nr ->
                val uid = nr.jsonObject["user_id"]?.jsonPrimitive?.content ?: return@forEach
                val user = allUsers.firstOrNull { it.id == uid } ?: return@forEach
                ignored[user] = (ignored[user] ?: 0) + 1
            }

            respondents.forEach { r ->
                val uid = r.jsonObject["user_id"]?.jsonPrimitive?.content ?: return@forEach
                val user = allUsers.firstOrNull { it.id == uid } ?: return@forEach
                val response = r.jsonObject["response"]?.jsonPrimitive?.content
                when (response) {
                    "no" -> rejected[user] = (rejected[user] ?: 0) + 1
                    "yes", "yes_at_time" -> if (uid != history.summonerId) {
                        attended[user] = (attended[user] ?: 0) + 1
                    }
                }
                val respondedAt = r.jsonObject["responded_at"]?.jsonPrimitive?.content
                val createdAt = history.createdAt
                if (respondedAt != null) {
                    val diff = runCatching {
                        OffsetDateTime.parse(respondedAt).toEpochSecond() -
                            OffsetDateTime.parse(createdAt).toEpochSecond()
                    }.getOrNull()
                    if (diff != null && diff >= 0) {
                        responseTimes.getOrPut(user) { mutableListOf() }.add(diff)
                    }
                }
            }
        }

        val fastestResponder = responseTimes.entries
            .filter { it.value.isNotEmpty() }
            .minByOrNull { it.value.average() }

        fun <T : Comparable<T>> topThreeDescending(
            map: Map<User, T>,
            format: (T) -> String,
            minValue: T,
        ): List<RankedEntry> {
            val sorted = map.entries.filter { it.value > minValue }.sortedByDescending { it.value }
            return buildPodium(sorted) { format(it.value) }
        }

        val summonsPodium = topThreeDescending(summonsSent, { "$it beacons sent" }, 0)
        val attendedPodium = topThreeDescending(attended, { "$it 'yes' responses" }, 0)
        val ignoredPodium = topThreeDescending(ignored, { "$it ignored beacons" }, 0)
        val rejectedPodium = topThreeDescending(rejected, { "$it 'no' responses" }, 0)
        val fastestPodium = run {
            val sorted = responseTimes.entries
                .filter { it.value.isNotEmpty() }
                .map { it.key to it.value.average().toLong() }
                .sortedBy { it.second }
            val result = mutableListOf<RankedEntry>()
            var rank = 1
            var i = 0
            while (i < sorted.size && result.size < 3) {
                val currentVal = sorted[i].second
                val group = sorted.drop(i).takeWhile { it.second == currentVal }
                group.forEach { (user, avg) ->
                    result.add(RankedEntry(rank, user, "${avg / 60}m ${avg % 60}s avg response time"))
                }
                rank += group.size
                i += group.size
                if (rank > 3) break
            }
            result
        }

        val badges = listOf(
            Badge(
                R.drawable.rune_double_damage, "Most Beacons",
                summonsSent.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key,
                summonsSent.maxByOrNull { it.value }?.let { "${it.value} beacons sent" } ?: "No data",
                summonsPodium,
            ),
            Badge(
                R.drawable.rune_haste, "Fastest Responder",
                fastestResponder?.key,
                fastestResponder?.let {
                    val avg = it.value.average().toLong()
                    "${avg / 60}m ${avg % 60}s avg response time"
                } ?: "No data",
                fastestPodium,
            ),
            Badge(
                R.drawable.rune_regeneration, "Top Attendance",
                attended.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key,
                attended.maxByOrNull { it.value }?.let { "${it.value} 'yes' responses" } ?: "No data",
                attendedPodium,
            ),
            Badge(
                R.drawable.rune_invisibility, "Biggest Ghost",
                ignored.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key,
                ignored.maxByOrNull { it.value }?.let { "${it.value} ignored beacons" } ?: "No data",
                ignoredPodium,
            ),
            Badge(
                R.drawable.rune_water, "Biggest Chud",
                rejected.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key,
                rejected.maxByOrNull { it.value }?.let { "${it.value} 'no' responses" } ?: "No data",
                rejectedPodium,
            ),
        )

        _state.value = RankingsUiState.Loaded(badges, isThisMonth)
    }
}
