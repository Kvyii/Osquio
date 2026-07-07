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
    val maybe: Int,
    val rejected: Int,
    val ignored: Int,
    val isDeceased: Boolean = false,
    val lastSeenAt: Instant? = null,
)

enum class StatColumn { SENT, YES, MAYBE, NO, IGNORED }
enum class SortDirection { DESC, ASC, NONE }

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Loaded(
        val stats: List<UserStats>,
        val isThisMonth: Boolean,
        val sortColumn: StatColumn? = null,
        val sortDirection: SortDirection = SortDirection.NONE,
    ) : StatsUiState
    data class Error(val message: String) : StatsUiState
}

class StatsViewModel : ViewModel() {

    private val _state = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
    val state = _state.asStateFlow()

    private var allHistory: List<SummonHistory> = emptyList()
    private var allUsers: List<User> = emptyList()
    private var isThisMonth = true
    private var sortColumn: StatColumn? = null
    private var sortDirection: SortDirection = SortDirection.NONE

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

    fun toggleSort(column: StatColumn) {
        if (sortColumn != column) {
            sortColumn = column
            sortDirection = SortDirection.DESC
        } else {
            sortDirection = when (sortDirection) {
                SortDirection.DESC -> SortDirection.ASC
                SortDirection.ASC -> SortDirection.NONE
                SortDirection.NONE -> SortDirection.DESC
            }
            if (sortDirection == SortDirection.NONE) sortColumn = null
        }
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
            var accepted = 0; var maybe = 0; var rejected = 0; var ignored = 0
            filtered.forEach { history ->
                if (history.summonerId == user.id) return@forEach
                val respondents = history.snapshot["respondents"] as? JsonArray ?: return@forEach
                val nonRespondents = history.snapshot["non_respondents"] as? JsonArray ?: return@forEach
                val myResponse = respondents.firstOrNull {
                    it.jsonObject["user_id"]?.jsonPrimitive?.content == user.id
                }
                if (myResponse != null) {
                    when (myResponse.jsonObject["response"]?.jsonPrimitive?.content) {
                        "yes" -> accepted++
                        "yes_at_time" -> maybe++
                        "no" -> rejected++
                    }
                } else if (nonRespondents.any {
                        it.jsonObject["user_id"]?.jsonPrimitive?.content == user.id
                    }) {
                    ignored++
                }
            }

            var ignoreStreak = 0
            var lastSeenAt: Instant? = null
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
                    if (responded) {
                        if (lastSeenAt == null) {
                            lastSeenAt = runCatching { OffsetDateTime.parse(history.closedAt).toInstant() }.getOrNull()
                        }
                        break
                    }
                }
            }

            UserStats(user, sent, accepted, maybe, rejected, ignored, isDeceased = ignoreStreak >= 5, lastSeenAt = lastSeenAt)
        }

        val defaultSorted = stats.sortedWith(
            compareByDescending<UserStats> { it.summonsSent }
                .thenByDescending { it.accepted }
                .thenBy { it.ignored }
                .thenBy { it.rejected }
                .thenBy { it.user.displayName }
        )

        val finalSorted = when {
            sortColumn == null || sortDirection == SortDirection.NONE -> defaultSorted
            sortDirection == SortDirection.DESC -> when (sortColumn) {
                StatColumn.SENT -> defaultSorted.sortedByDescending { it.summonsSent }
                StatColumn.YES -> defaultSorted.sortedByDescending { it.accepted }
                StatColumn.MAYBE -> defaultSorted.sortedByDescending { it.maybe }
                StatColumn.NO -> defaultSorted.sortedByDescending { it.rejected }
                StatColumn.IGNORED -> defaultSorted.sortedByDescending { it.ignored }
                null -> defaultSorted
            }
            else -> when (sortColumn) {
                StatColumn.SENT -> defaultSorted.sortedBy { it.summonsSent }
                StatColumn.YES -> defaultSorted.sortedBy { it.accepted }
                StatColumn.MAYBE -> defaultSorted.sortedBy { it.maybe }
                StatColumn.NO -> defaultSorted.sortedBy { it.rejected }
                StatColumn.IGNORED -> defaultSorted.sortedBy { it.ignored }
                null -> defaultSorted
            }
        }

        _state.value = StatsUiState.Loaded(finalSorted, isThisMonth, sortColumn, sortDirection)
    }
}
