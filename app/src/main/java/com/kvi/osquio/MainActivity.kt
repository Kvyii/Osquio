package com.kvi.osquio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.kvi.osquio.R
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    @Suppress("InvalidFragmentVersionForActivityResult")
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OsquioTheme {
                AppRoot()
            }
        }
        if (savedInstanceState == null) lifecycleScope.launch { checkForUpdate() }
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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
        val info = UpdateChecker().checkForUpdate(BuildConfig.VERSION_NAME.trim()) ?: return
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
    var changelogNotes by remember { mutableStateOf<String?>(null) }
    var showNotificationWarning by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

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

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val lastSeenVersion = prefs.getString("last_seen_version", null)
        val currentVersion = BuildConfig.VERSION_NAME
        if (lastSeenVersion != currentVersion) {
            prefs.edit().putString("last_seen_version", currentVersion).apply()
            if (lastSeenVersion != null) {
                val notes = runCatching { UpdateChecker().fetchReleaseNotes(currentVersion) }.getOrNull()
                changelogNotes = notes ?: "No release notes available."
                showNotificationWarning = true
            }
        }
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

    changelogNotes?.let { notes ->
        AlertDialog(
            onDismissRequest = { changelogNotes = null },
            title = { Text("What's new in v${BuildConfig.VERSION_NAME}") },
            text = { MarkdownText(notes) },
            confirmButton = {
                TextButton(onClick = { changelogNotes = null }) { Text("Got it") }
            }
        )
    }

    if (showNotificationWarning && changelogNotes == null) {
        AlertDialog(
            onDismissRequest = { showNotificationWarning = false },
            title = { Text("Notifications are now on mute") },
            text = {
                Text("All notifications are now on mute by default. Visit the settings page to set your preferences on how you want to be annoyed.")
            },
            confirmButton = {
                TextButton(onClick = { showNotificationWarning = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun MarkdownText(markdown: String) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().forEach { line ->
            when {
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                )
                line.startsWith("# ") -> Text(
                    line.removePrefix("# "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                )
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val content = line.drop(2)
                    Text(
                        buildAnnotatedString {
                            append("• ")
                            appendMarkdownInline(content, onSurface)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> Text(
                    buildAnnotatedString { appendMarkdownInline(line, onSurface) },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendMarkdownInline(
    text: String,
    defaultColor: androidx.compose.ui.graphics.Color,
) {
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    var last = 0
    boldRegex.findAll(text).forEach { match ->
        if (match.range.first > last) append(text.substring(last, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
