package com.kvi.osquio.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.data.HistoryRepository
import com.kvi.osquio.data.model.SummonHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Calendar(
        val summonsPerDay: Map<LocalDate, Int>,
        val selectedDay: LocalDate? = null,
        val dayDetail: List<SummonHistory> = emptyList(),
        val selectedSummon: SummonHistory? = null,
    ) : HistoryUiState
    data class Error(val message: String) : HistoryUiState
}

class HistoryViewModel : ViewModel() {

    private val _state = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val state = _state.asStateFlow()

    private var allHistory: List<SummonHistory> = emptyList()

    fun load() {
        viewModelScope.launch {
            try {
                allHistory = HistoryRepository.allHistory()
                buildCalendar()
            } catch (e: Exception) {
                _state.value = HistoryUiState.Error(e.message ?: "Failed to load history")
            }
        }
    }

    fun selectDay(date: LocalDate) {
        val dayHistory = allHistory.filter { h ->
            runCatching {
                val d = OffsetDateTime.parse(h.gameTime).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
                d == date
            }.getOrDefault(false)
        }
        val current = _state.value as? HistoryUiState.Calendar ?: return
        _state.value = current.copy(selectedDay = date, dayDetail = dayHistory, selectedSummon = null)
    }

    fun selectSummon(summon: SummonHistory) {
        val current = _state.value as? HistoryUiState.Calendar ?: return
        _state.value = current.copy(selectedSummon = summon)
    }

    fun back() {
        val current = _state.value as? HistoryUiState.Calendar ?: return
        if (current.selectedSummon != null) {
            _state.value = current.copy(selectedSummon = null)
        } else {
            _state.value = current.copy(selectedDay = null, dayDetail = emptyList())
        }
    }

    private fun buildCalendar() {
        val perDay = mutableMapOf<LocalDate, Int>()
        allHistory.forEach { h ->
            runCatching {
                val d = OffsetDateTime.parse(h.gameTime).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
                perDay[d] = (perDay[d] ?: 0) + 1
            }
        }
        _state.value = HistoryUiState.Calendar(perDay)
    }
}
