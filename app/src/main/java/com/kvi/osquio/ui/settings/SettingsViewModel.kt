package com.kvi.osquio.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.BuildConfig
import com.kvi.osquio.data.ConfigRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.Config
import com.kvi.osquio.data.model.User
import com.kvi.osquio.data.supabase
import com.kvi.osquio.ui.theme.AppTheme
import com.kvi.osquio.ui.theme.ThemeManager
import com.kvi.osquio.util.SteamApi
import com.kvi.osquio.util.UpdateChecker
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val progress: Int) : UpdateState
    data object UpToDate : UpdateState
}

sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Loaded(
        val currentUser: User,
        val config: Config,
        val allUsers: List<User>,
        val message: String? = null,
        val updateState: UpdateState = UpdateState.Idle,
    ) : SettingsUiState
    data class Error(val message: String) : SettingsUiState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val state = _state.asStateFlow()

    fun load(currentUser: User) {
        viewModelScope.launch {
            try {
                val config = ConfigRepository.getConfig()
                val allUsers = UserRepository.allUsers()
                _state.value = SettingsUiState.Loaded(currentUser, config, allUsers)
            } catch (e: Exception) {
                _state.value = SettingsUiState.Error(e.message ?: "Failed to load settings")
            }
        }
    }

    fun changePassword(newPassword: String) {
        viewModelScope.launch {
            try {
                supabase.auth.updateUser { password = newPassword }
                showMessage("Password changed successfully")
            } catch (e: Exception) {
                showMessage("Failed to change password: ${e.message}")
            }
        }
    }

    fun updateConfig(cooldownSeconds: Int, maxAheadMinutes: Int) {
        viewModelScope.launch {
            try {
                ConfigRepository.updateConfig(cooldownSeconds, maxAheadMinutes)
                val current = _state.value as? SettingsUiState.Loaded ?: return@launch
                _state.value = current.copy(
                    config = current.config.copy(
                        summonCooldownSeconds = cooldownSeconds,
                        maxSummonAheadMinutes = maxAheadMinutes,
                    ),
                    message = "Config updated",
                )
            } catch (e: Exception) {
                showMessage("Failed to update config: ${e.message}")
            }
        }
    }

    fun refreshSteamProfile(user: User) {
        viewModelScope.launch {
            val allUsers = try { UserRepository.allUsers() } catch (e: Exception) {
                showMessage("Failed to load users: ${e.message}"); return@launch
            }
            var updated = 0
            var failed = 0
            for (u in allUsers) {
                val profile = try { SteamApi.fetchProfile(u.steamId) } catch (_: Exception) { null }
                if (profile == null) { failed++; continue }
                try {
                    UserRepository.updateSteamCache(u.id, profile.displayName, profile.avatarUrl)
                    updated++
                } catch (_: Exception) { failed++ }
            }
            showMessage("Updated $updated users" + if (failed > 0) ", $failed failed" else "")
        }
    }

    fun setTheme(theme: AppTheme) {
        ThemeManager.current = theme
        getApplication<android.app.Application>().getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putString("theme", theme.name).apply()
    }

    fun checkAndInstallUpdate() {
        val current = _state.value as? SettingsUiState.Loaded ?: return
        _state.value = current.copy(updateState = UpdateState.Checking)
        viewModelScope.launch {
            val checker = UpdateChecker()
            val info = checker.checkForUpdate(BuildConfig.VERSION_NAME)
            if (info == null) {
                _state.value = (_state.value as? SettingsUiState.Loaded)?.copy(updateState = UpdateState.UpToDate) ?: return@launch
                kotlinx.coroutines.delay(2000)
                _state.value = (_state.value as? SettingsUiState.Loaded)?.copy(updateState = UpdateState.Idle) ?: return@launch
                return@launch
            }
            _state.value = (_state.value as? SettingsUiState.Loaded)?.copy(updateState = UpdateState.Downloading(0)) ?: return@launch
            try {
                checker.downloadAndInstall(getApplication(), info.downloadUrl) { progress ->
                    val s = _state.value as? SettingsUiState.Loaded ?: return@downloadAndInstall
                    _state.value = s.copy(updateState = UpdateState.Downloading(progress))
                }
            } catch (e: Exception) {
                showMessage("Download failed: ${e.message}")
                _state.value = (_state.value as? SettingsUiState.Loaded)?.copy(updateState = UpdateState.Idle) ?: return@launch
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            supabase.auth.signOut(io.github.jan.supabase.auth.SignOutScope.LOCAL)
        }
    }

    fun clearMessage() {
        val current = _state.value as? SettingsUiState.Loaded ?: return
        _state.value = current.copy(message = null)
    }

    private fun showMessage(msg: String) {
        val current = _state.value as? SettingsUiState.Loaded ?: return
        _state.value = current.copy(message = msg)
    }
}
