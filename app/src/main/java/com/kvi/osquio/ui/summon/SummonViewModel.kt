package com.kvi.osquio.ui.summon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.data.ConfigRepository
import com.kvi.osquio.data.RsvpRepository
import com.kvi.osquio.data.SummonEvents
import com.kvi.osquio.data.SummonRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.Config
import com.kvi.osquio.data.model.Rsvp
import com.kvi.osquio.data.model.Summon
import com.kvi.osquio.data.model.User
import com.kvi.osquio.data.supabase
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime

data class LobbyState(
    val summon: Summon,
    val summoner: User?,
    val rsvps: List<Rsvp>,
    val allUsers: List<User>,
    val config: Config,
    val rebeaconCooldownSeconds: Long = 0L,
    val rebeaconUsedInWindow: Int = 0,
)

sealed interface SummonUiState {
    data object Loading : SummonUiState
    data class NoActiveSummon(val config: Config, val cooldownRemaining: Long = 0L) : SummonUiState
    data class ActiveLobby(val lobby: LobbyState) : SummonUiState
    data class Error(val message: String) : SummonUiState
}

class SummonViewModel : ViewModel() {

    private val _state = MutableStateFlow<SummonUiState>(SummonUiState.Loading)
    val state = _state.asStateFlow()

    private var realtimeJob: Job? = null
    private var cooldownJob: Job? = null
    private var rebeaconTickJob: Job? = null
    private var activeSummonId: String? = null

    private var rebeaconCount = 0
    private var rebeaconWindowStart = Instant.now()
    private var lastRebeaconAt: Instant? = null

    fun load(currentUser: User) {
        viewModelScope.launch {
            try {
                val config = ConfigRepository.getConfig()
                val summon = SummonRepository.activeSummon()
                if (summon == null) {
                    val cooldownRemaining = cooldownSecondsRemaining(currentUser)
                    _state.value = SummonUiState.NoActiveSummon(config, cooldownRemaining)
                    startCooldownTick(currentUser, config)
                } else {
                    loadLobby(summon, config)
                }
            } catch (e: Exception) {
                _state.value = SummonUiState.Error(e.message ?: "Unknown error")
            }
        }
        viewModelScope.launch {
            SummonEvents.summonClosed.collect {
                activeSummonId = null
                rebeaconTickJob?.cancel()
                val config = ((_state.value as? SummonUiState.ActiveLobby)?.lobby?.config)
                    ?: runCatching { ConfigRepository.getConfig() }.getOrNull()
                    ?: return@collect
                val cooldown = cooldownSecondsRemaining(currentUser)
                _state.value = SummonUiState.NoActiveSummon(config, cooldown)
                startCooldownTick(currentUser, config)
            }
        }
    }

    fun resubscribeToLobby() {
        val current = _state.value
        if (current is SummonUiState.ActiveLobby) {
            viewModelScope.launch {
                try {
                    val config = ConfigRepository.getConfig()
                    val summon = SummonRepository.activeSummon()
                    if (summon != null) loadLobby(summon, config)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun loadLobby(summon: Summon, config: Config) {
        val rsvps = RsvpRepository.rsvpsForSummon(summon.id)
        val allUsers = UserRepository.allUsers()
        val summoner = allUsers.firstOrNull { it.id == summon.createdBy }
        _state.value = SummonUiState.ActiveLobby(
            LobbyState(summon, summoner, rsvps, allUsers, config)
        )
        subscribeToRealtime(summon.id, config)
    }

    private fun subscribeToRealtime(summonId: String, config: Config) {
        if (activeSummonId == summonId) return
        activeSummonId = summonId
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            val channel = supabase.channel("lobby-$summonId")

            launch {
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "rsvps"
                }.collect {
                    val updated = RsvpRepository.rsvpsForSummon(summonId)
                    val current = (_state.value as? SummonUiState.ActiveLobby)?.lobby ?: return@collect
                    _state.value = SummonUiState.ActiveLobby(current.copy(rsvps = updated))
                }
            }

            launch {
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "summons"
                }.collect {
                    val summon = SummonRepository.activeSummon()
                    if (summon == null) {
                        activeSummonId = null
                        _state.value = SummonUiState.NoActiveSummon(config)
                    }
                }
            }

            channel.subscribe()
        }
    }

    fun createSummon(currentUser: User, gameTime: Instant) {
        viewModelScope.launch {
            try {
                val config = ConfigRepository.getConfig()
                SummonRepository.createSummon(
                    createdBy = currentUser.id,
                    gameTime = gameTime.toString(),
                )
                val summon = SummonRepository.activeSummon() ?: return@launch
                loadLobby(summon, config)
            } catch (e: Exception) {
                _state.value = SummonUiState.Error(e.message ?: "Failed to create summon")
            }
        }
    }

    fun cancelSummon(summonId: String, currentUser: User) {
        viewModelScope.launch {
            try {
                SummonRepository.cancelSummon(summonId, currentUser.id)
                activeSummonId = null
                rebeaconTickJob?.cancel()
                val config = ConfigRepository.getConfig()
                val freshUser = runCatching { UserRepository.currentUser() }.getOrElse { currentUser }
                val cooldown = cooldownSecondsRemaining(freshUser)
                _state.value = SummonUiState.NoActiveSummon(config, cooldown)
                startCooldownTick(freshUser, config)
            } catch (e: Exception) {
                _state.value = SummonUiState.Error(e.message ?: "Failed to cancel")
            }
        }
    }

    fun rebeacon(summonId: String, isAdmin: Boolean = false) {
        val now = Instant.now()
        if (now.epochSecond - rebeaconWindowStart.epochSecond > 15 * 60) {
            rebeaconCount = 0
            rebeaconWindowStart = now
        }
        if (!isAdmin) {
            val secsSinceLast = lastRebeaconAt?.let { now.epochSecond - it.epochSecond } ?: Long.MAX_VALUE
            if (secsSinceLast < 60 || rebeaconCount >= 5) return
        }

        viewModelScope.launch {
            try {
                SummonRepository.triggerRebeacon(summonId)
                lastRebeaconAt = now
                rebeaconCount++
                startRebeaconCooldownTick()
            } catch (_: Exception) {}
        }
    }

    private fun startRebeaconCooldownTick() {
        rebeaconTickJob?.cancel()
        rebeaconTickJob = viewModelScope.launch {
            while (isActive) {
                val last = lastRebeaconAt ?: break
                val remaining = maxOf(0L, 60L - (Instant.now().epochSecond - last.epochSecond))
                val windowAge = Instant.now().epochSecond - rebeaconWindowStart.epochSecond
                val usedInWindow = if (windowAge > 15 * 60) 0 else rebeaconCount
                val current = (_state.value as? SummonUiState.ActiveLobby)?.lobby ?: break
                _state.value = SummonUiState.ActiveLobby(
                    current.copy(rebeaconCooldownSeconds = remaining, rebeaconUsedInWindow = usedInWindow)
                )
                if (remaining <= 0L) break
                delay(1000)
            }
        }
    }

    fun submitRsvp(summonId: String, userId: String, response: String, responseTime: Instant?) {
        viewModelScope.launch {
            try {
                RsvpRepository.upsertRsvp(
                    summonId = summonId,
                    userId = userId,
                    response = response,
                    responseTime = responseTime?.toString(),
                )
            } catch (e: Exception) {
                _state.value = SummonUiState.Error(e.message ?: "Failed to RSVP")
            }
        }
    }

    private fun cooldownSecondsRemaining(user: User): Long {
        if (user.isAdmin) return 0L
        val cooldownUntil = user.cooldownUntil ?: return 0L
        return try {
            val until = OffsetDateTime.parse(cooldownUntil).toInstant()
            maxOf(0L, until.epochSecond - Instant.now().epochSecond)
        } catch (_: Exception) { 0L }
    }

    private fun startCooldownTick(currentUser: User, config: Config) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            while (isActive) {
                val remaining = cooldownSecondsRemaining(currentUser)
                val current = _state.value
                if (current is SummonUiState.NoActiveSummon) {
                    _state.value = current.copy(cooldownRemaining = remaining)
                }
                if (remaining <= 0L) break
                delay(1000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        realtimeJob?.cancel()
        cooldownJob?.cancel()
        rebeaconTickJob?.cancel()
    }
}
