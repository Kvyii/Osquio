package com.kvi.osquio.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.User
import com.kvi.osquio.data.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data class Success(val user: User) : LoginUiState
    data class Error(val message: String) : LoginUiState
}

class LoginViewModel : ViewModel() {

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state = _state.asStateFlow()

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = LoginUiState.Error("Please fill in all fields")
            return
        }
        val email = if (username.contains("@")) username else "$username@osquio.kvi"
        viewModelScope.launch {
            _state.value = LoginUiState.Loading
            try {
                supabase.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                val user = UserRepository.currentUser()
                _state.value = LoginUiState.Success(user)
            } catch (e: Exception) {
                _state.value = LoginUiState.Error("Invalid credentials")
            }
        }
    }

    fun clearError() {
        if (_state.value is LoginUiState.Error) _state.value = LoginUiState.Idle
    }
}
