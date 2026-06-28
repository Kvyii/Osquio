package com.kvi.osquio

import android.content.Intent
import com.kvi.osquio.R
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.User
import com.kvi.osquio.data.supabase
import com.kvi.osquio.ui.MainNavGraph
import com.kvi.osquio.ui.auth.LoginScreen
import com.kvi.osquio.ui.theme.OsquioTheme
import com.google.firebase.messaging.FirebaseMessaging
import com.kvi.osquio.util.SteamApi
import com.kvi.osquio.util.UpdateChecker
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OsquioTheme {
                AppRoot()
            }
        }
        lifecycleScope.launch { checkForUpdate() }
    }

    override fun onResume() {
        super.onResume()
        requestBatteryOptimisationExemption()
    }

    private fun requestBatteryOptimisationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private suspend fun checkForUpdate() {
        val info = UpdateChecker().checkForUpdate(BuildConfig.VERSION_NAME) ?: return
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle("Update available — ${info.version}")
                .setMessage("A new version of Osquio is available.")
                .setPositiveButton("Download") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }
}

suspend fun registerFcmToken(user: User) {
    try {
        val token = FirebaseMessaging.getInstance().token.await()
        UserRepository.updateFcmToken(user.id, token)
    } catch (_: Exception) {}
}

suspend fun refreshSteamIfNeeded(user: User) {
    val allUsers = try { UserRepository.allUsers() } catch (_: Exception) { return }
    for (u in allUsers) {
        val needsRefresh = u.avatarUrl == null || u.cacheAt == null ||
            runCatching { ChronoUnit.DAYS.between(Instant.parse(u.cacheAt), Instant.now()) >= 7 }.getOrDefault(true)
        if (!needsRefresh) continue
        try {
            val profile = SteamApi.fetchProfile(u.steamId) ?: continue
            UserRepository.updateSteamCache(u.id, profile.displayName, profile.avatarUrl)
        } catch (_: Exception) {}
    }
}

@Composable
private fun AppRoot() {
    var currentUser by remember { mutableStateOf<User?>(null) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        supabase.auth.loadFromStorage()
        val session = supabase.auth.currentSessionOrNull()
        if (session != null) {
            try {
                val user = UserRepository.currentUser()
                currentUser = user
                registerFcmToken(user)
                refreshSteamIfNeeded(user)
            } catch (_: Exception) {
                supabase.auth.signOut()
            }
        }
        checked = true
    }

    if (!checked) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    if (currentUser != null) {
        MainNavGraph(currentUser!!, onSignOut = { currentUser = null })
    } else {
        LoginScreen(
            onLoginSuccess = {
                kotlinx.coroutines.MainScope().launch {
                    val user = UserRepository.currentUser()
                    currentUser = user
                    registerFcmToken(user)
                    refreshSteamIfNeeded(user)
                }
            }
        )
    }
}
