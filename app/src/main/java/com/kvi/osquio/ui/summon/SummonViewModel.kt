package com.kvi.osquio.ui.summon

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.data.ConfigRepository
import com.kvi.osquio.data.RsvpRepository
import com.kvi.osquio.data.SummonRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.Config
import com.kvi.osquio.data.model.Rsvp
import com.kvi.osquio.data.model.Summon
import com.kvi.osquio.data.model.User
import com.kvi.osquio.data.supabase
import com.kvi.osquio.notifications.RsvpSoundPlayer
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
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

private const val CLIENT_LOCKOUT_SECONDS = 15 * 60L

class SummonViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("summon_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<SummonUiState>(SummonUiState.Loading)
    val state = _state.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    private val _notifyError = MutableStateFlow<String?>(null)
    val notifyError = _notifyError.asStateFlow()

    fun clearNotifyError() { _notifyError.value = null }

    private var realtimeJob: Job? = null
    private var cooldownJob: Job? = null
    private var rebeaconTickJob: Job? = null
    private var expiryJob: Job? = null
    private var activeSummonId: String? = null

    private var rebeaconCount = 0
    private var rebeaconWindowStart = Instant.now()
    private var lastRebeaconAt: Instant? = run {
        val epoch = prefs.getLong("last_rebeacon_at", 0L)
        if (epoch > 0L) Instant.ofEpochSecond(epoch) else null
    }

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
        startExpiryTimer(summon, config)
    }

    private fun startExpiryTimer(summon: Summon, config: Config) {
        expiryJob?.cancel()
        val gameTime = runCatching { Instant.parse(summon.gameTime) }.getOrNull() ?: return
        val delayMs = gameTime.toEpochMilli() - System.currentTimeMillis()
        if (delayMs <= 0L) {
            activeSummonId = null
            _state.value = SummonUiState.NoActiveSummon(config)
            return
        }
        expiryJob = viewModelScope.launch {
            delay(delayMs)
            val current = _state.value
            if (current is SummonUiState.ActiveLobby && current.lobby.summon.id == summon.id) {
                activeSummonId = null
                _state.value = SummonUiState.NoActiveSummon(config)
            }
        }
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
                }.collect { action ->
                    val current = (_state.value as? SummonUiState.ActiveLobby)?.lobby ?: return@collect
                    val updatedRsvp = runCatching {
                        when (action) {
                            is PostgresAction.Insert -> action.decodeRecord<Rsvp>()
                            is PostgresAction.Update -> action.decodeRecord<Rsvp>()
                            else -> null
                        }
                    }.getOrNull()
                    if (updatedRsvp != null) {
                        val updatedRsvps = current.rsvps
                            .filter { it.userId != updatedRsvp.userId }
                            .plus(updatedRsvp)
                        _state.value = SummonUiState.ActiveLobby(current.copy(rsvps = updatedRsvps))
                    } else {
                        val updated = RsvpRepository.rsvpsForSummon(summonId)
                        _state.value = SummonUiState.ActiveLobby(current.copy(rsvps = updated))
                    }
                }
            }

            launch {
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "summons"
                }.collect { action ->
                    val updatedSummon = runCatching {
                        when (action) {
                            is PostgresAction.Update -> action.decodeRecord<Summon>()
                            is PostgresAction.Insert -> action.decodeRecord<Summon>()
                            else -> null
                        }
                    }.getOrNull()
                    if (updatedSummon != null) {
                        if (updatedSummon.status != "open") {
                            activeSummonId = null
                            _state.value = SummonUiState.NoActiveSummon(config)
                        }
                    } else {
                        val summon = SummonRepository.activeSummon()
                        if (summon == null) {
                            activeSummonId = null
                            _state.value = SummonUiState.NoActiveSummon(config)
                        }
                    }
                }
            }

            channel.subscribe()
        }
    }

    fun createSummon(currentUser: User, gameTime: Instant) {
        if (_isCreating.value) return
        if (!currentUser.isAdmin) {
            val lastSummonAt = prefs.getLong("last_summon_at", 0L)
            if (Instant.now().epochSecond - lastSummonAt < CLIENT_LOCKOUT_SECONDS) return
        }
        prefs.edit().putLong("last_summon_at", Instant.now().epochSecond).apply()
        _isCreating.value = true
        viewModelScope.launch {
            try {
                val config = ConfigRepository.getConfig()
                val summon = SummonRepository.createSummon(
                    createdBy = currentUser.id,
                    gameTime = gameTime.toString(),
                )
                loadLobby(summon, config)
            } catch (e: Exception) {
                _notifyError.value = "Notification failed: ${e.message ?: "Unable to connect to server"}"
                _state.value = SummonUiState.Error(e.message ?: "Failed to create summon")
            } finally {
                _isCreating.value = false
            }
        }
    }

    fun cancelSummon(summonId: String, currentUser: User) {
        viewModelScope.launch {
            try {
                SummonRepository.cancelSummon(summonId, currentUser.id)
                activeSummonId = null
                rebeaconTickJob?.cancel()
                expiryJob?.cancel()
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
                prefs.edit().putLong("last_rebeacon_at", now.epochSecond).apply()
                rebeaconCount++
                startRebeaconCooldownTick()
            } catch (e: Exception) {
                _notifyError.value = "Notification failed: ${e.message ?: "Unable to connect to server"}"
            }
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
        val current = _state.value as? SummonUiState.ActiveLobby ?: return
        RsvpSoundPlayer.playForResponse(getApplication(), response)
        val optimisticRsvp = Rsvp(
            summonId = summonId,
            userId = userId,
            response = response,
            responseTime = responseTime?.toString(),
        )
        val updatedRsvps = current.lobby.rsvps
            .filter { it.userId != userId }
            .plus(optimisticRsvp)
        _state.value = SummonUiState.ActiveLobby(current.lobby.copy(rsvps = updatedRsvps))

        viewModelScope.launch {
            try {
                RsvpRepository.upsertRsvp(
                    summonId = summonId,
                    userId = userId,
                    response = response,
                    responseTime = responseTime?.toString(),
                )
            } catch (e: Exception) {
                _state.value = SummonUiState.ActiveLobby(current.lobby)
                _notifyError.value = "Failed to submit RSVP: ${e.message ?: "Unknown error"}"
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
        expiryJob?.cancel()
    }
}
