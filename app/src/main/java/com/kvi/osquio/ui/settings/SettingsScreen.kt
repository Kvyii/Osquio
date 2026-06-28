package com.kvi.osquio.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvi.osquio.data.model.User
import com.kvi.osquio.ui.theme.AppTheme
import com.kvi.osquio.ui.theme.ThemeManager

@Composable
fun SettingsScreen(currentUser: User, onSignOut: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(currentUser.id) { vm.load(currentUser) }

    when (val s = state) {
        is SettingsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is SettingsUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        is SettingsUiState.Loaded -> SettingsContent(s, vm, onSignOut)
    }
}

@Composable
private fun SettingsContent(state: SettingsUiState.Loaded, vm: SettingsViewModel, onSignOut: () -> Unit) {
    val scrollState = rememberScrollState()
    var cooldownSeconds by remember(state.config) { mutableStateOf(state.config.summonCooldownSeconds.toString()) }
    var maxAheadMinutes by remember(state.config) { mutableStateOf(state.config.maxSummonAheadMinutes.toString()) }

    state.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            vm.clearMessage()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        // Profile section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Profile", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("${state.currentUser.displayName}")
                Text("Steam ID: ${state.currentUser.steamId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.refreshSteamProfile(state.currentUser) }) {
                    Text("Refresh Steam profile")
                }
            }
        }


        // Admin section
        if (state.currentUser.isAdmin) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Admin — Config", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cooldownSeconds,
                        onValueChange = { cooldownSeconds = it },
                        label = { Text("Cooldown (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = maxAheadMinutes,
                        onValueChange = { maxAheadMinutes = it },
                        label = { Text("Max summon ahead (minutes)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val cd = cooldownSeconds.toIntOrNull() ?: return@Button
                        val ma = maxAheadMinutes.toIntOrNull() ?: return@Button
                        vm.updateConfig(cd, ma)
                    }) { Text("Save config") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Admin — Members (${state.allUsers.size})", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    state.allUsers.forEach { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(user.displayName)
                                if (user.isAdmin) Text("admin", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "To add/remove members, create accounts via the Supabase dashboard or create-member Edge Function.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Theme picker
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { theme ->
                        val label = when (theme) {
                            AppTheme.MIDNIGHT -> "Midnight"
                            AppTheme.TWILIGHT -> "Twilight"
                            AppTheme.DAWN -> "Dawn"
                        }
                        FilterChip(
                            selected = ThemeManager.current == theme,
                            onClick = { vm.setTheme(theme) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // Notifications
        NotificationsCard()

        // Sign out
        OutlinedButton(
            onClick = { vm.signOut(); onSignOut() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Sign out") }

        // About
        val uriHandler = LocalUriHandler.current
        val githubUrl = "https://github.com/Kvyii/Osquio"
        val linkText = buildAnnotatedString {
            append("v0.1.0 beta  •  ")
            pushStringAnnotation(tag = "URL", annotation = githubUrl)
            withStyle(SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )) { append("GitHub") }
            pop()
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ClickableText(
                text = linkText,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = { offset ->
                    linkText.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { uriHandler.openUri(it.item) }
                },
            )
        }
    }
}

@Composable
private fun NotificationsCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Enable alarm-style notifications to see a full-screen alert when a Beacon arrives, even when your phone is locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    } else {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enable alarm-style notifications")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Required on Android 14+ for lock screen takeover",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
