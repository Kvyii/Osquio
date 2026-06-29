package com.kvi.osquio.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.data.HistoryRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.SummonHistory
import com.kvi.osquio.data.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class UserStats(
    val user: User,
    val summonsSent: Int,
    val accepted: Int,
    val rejected: Int,
    val ignored: Int,
    val isDeceased: Boolean = false,
)

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Loaded(val stats: List<UserStats>, val isThisMonth: Boolean) : StatsUiState
    data class Error(val message: String) : StatsUiState
}

class StatsViewModel : ViewModel() {

    private val _state = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
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
                _state.value = StatsUiState.Error(e.message ?: "Failed to load stats")
            }
        }
    }

    fun setFilter(thisMonth: Boolean) {
        isThisMonth = thisMonth
        recalculate()
    }

    private fun recalculate() {
        val thirtyDaysAgo = Instant.now().minusSeconds(30L * 24 * 60 * 60)
        val filtered = if (isThisMonth) {
            allHistory.filter { h ->
                runCatching {
                    OffsetDateTime.parse(h.closedAt).toInstant().isAfter(thirtyDaysAgo)
                }.getOrDefault(false)
            }
        } else allHistory

        val chronological = filtered.sortedBy { it.closedAt }

        val stats = allUsers.map { user ->
            val sent = filtered.count { it.summonerId == user.id }
            var accepted = 0; var rejected = 0; var ignored = 0
            filtered.forEach { history ->
                val respondents = history.snapshot["respondents"] as? JsonArray ?: return@forEach
                val nonRespondents = history.snapshot["non_respondents"] as? JsonArray ?: return@forEach
                val myResponse = respondents.firstOrNull {
                    it.jsonObject["user_id"]?.jsonPrimitive?.content == user.id
                }
                if (myResponse != null) {
                    when (myResponse.jsonObject["response"]?.jsonPrimitive?.content) {
                        "yes", "yes_at_time" -> accepted++
                        "no" -> rejected++
                    }
                } else if (nonRespondents.any {
                        it.jsonObject["user_id"]?.jsonPrimitive?.content == user.id
                    }) {
                    ignored++
                }
            }

            var ignoreStreak = 0
            for (history in chronological.asReversed()) {
                val respondents = history.snapshot["respondents"] as? JsonArray ?: break
                val nonRespondents = history.snapshot["non_respondents"] as? JsonArray ?: break
                val inNonRespondents = nonRespondents.any {
                    it.jsonObject["user_id"]?.jsonPrimitive?.content == user.id
                }
                if (inNonRespondents) {
                    ignoreStreak++
                } else {
                    val responded = respondents.any {
                        it.jsonObject["user_id"]?.jsonPrimitive?.content == user.id
                    }
                    if (responded) break
                }
            }

            UserStats(user, sent, accepted, rejected, ignored, isDeceased = ignoreStreak >= 10)
        }
        val sorted = stats.sortedWith(
            compareByDescending<UserStats> { it.summonsSent }
                .thenByDescending { it.accepted }
                .thenBy { it.ignored }
                .thenBy { it.rejected }
                .thenBy { it.user.displayName }
        )
        _state.value = StatsUiState.Loaded(sorted, isThisMonth)
    }
}
