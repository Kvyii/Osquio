package com.kvi.osquio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
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
import com.kvi.osquio.notifications.DiscreteSound
import com.kvi.osquio.notifications.DndWindow
import com.kvi.osquio.notifications.LoudSound
import com.kvi.osquio.notifications.NotificationPreferences
import com.kvi.osquio.ui.theme.AppTheme
import com.kvi.osquio.ui.theme.ThemeManager
import com.kvi.osquio.ui.settings.UpdateState
import java.time.DayOfWeek

@Composable
fun SettingsScreen(currentUser: User, onSignOut: () -> Unit, onBack: () -> Unit = {}, vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(currentUser.id) { vm.load(currentUser) }

    when (val s = state) {
        is SettingsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is SettingsUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        is SettingsUiState.Loaded -> SettingsContent(s, vm, onSignOut, onBack)
    }
}

@Composable
private fun SettingsContent(state: SettingsUiState.Loaded, vm: SettingsViewModel, onSignOut: () -> Unit, onBack: () -> Unit) {
    val scrollState = rememberScrollState()
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

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

        // Notifications
        NotificationsCard(prefs = state.notifPrefs, vm = vm)

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
                            AppTheme.SPONKE -> "Sponke"
                        }
                        FilterChip(
                            selected = ThemeManager.current == theme,
                            onClick = { vm.setTheme(theme) },
                            label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f),
                        )
                    }
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
                        value = maxAheadMinutes,
                        onValueChange = { maxAheadMinutes = it },
                        label = { Text("Max summon ahead (minutes)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val ma = maxAheadMinutes.toIntOrNull() ?: return@Button
                        vm.updateConfig(ma)
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

        // Sign out
        OutlinedButton(
            onClick = { vm.signOut(); onSignOut() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Sign out") }

        // Update button
        val updateState = state.updateState
        OutlinedButton(
            onClick = { vm.checkAndInstallUpdate() },
            modifier = Modifier.fillMaxWidth(),
            enabled = updateState is UpdateState.Idle || updateState is UpdateState.UpToDate,
        ) {
            Text(when (updateState) {
                is UpdateState.Idle -> "Check for update"
                is UpdateState.Checking -> "Checking..."
                is UpdateState.Downloading -> "Downloading... ${updateState.progress}%"
                is UpdateState.UpToDate -> "Up to date"
            })
        }

        // About
        val uriHandler = LocalUriHandler.current
        val githubUrl = "https://github.com/Kvyii/Osquio"
        val linkText = buildAnnotatedString {
            append("v${com.kvi.osquio.BuildConfig.VERSION_NAME}  •  ")
            pushStringAnnotation(tag = "URL", annotation = githubUrl)
            withStyle(SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )) { append("GitHub") }
            pop()
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClickableText(
                text = linkText,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = { offset ->
                    linkText.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { uriHandler.openUri(it.item) }
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "See an error or bug? Screenshot and send to Kv",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun NotificationsCard(prefs: NotificationPreferences, vm: SettingsViewModel) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enable app notifications")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Enable alarm-style notifications to see a full-screen alert when a Beacon arrives, even when your phone is locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Alert sound", style = MaterialTheme.typography.titleMedium)
                Switch(checked = prefs.soundEnabled, onCheckedChange = { vm.setSoundEnabled(it) })
            }
            Text(
                "The sound to play when a beacon is received",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (prefs.soundEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoudSound.entries.forEach { sound ->
                        FilterChip(
                            selected = prefs.loudSound == sound,
                            onClick = {
                                vm.setLoudSound(sound)
                                playPreview(context, sound.resId)
                            },
                            label = { Text(sound.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Discrete mode", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Quieter sound Mon–Fri, 8:30am–5:30pm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = prefs.discreteModeEnabled, onCheckedChange = { vm.setDiscreteModeEnabled(it) })
                }
                if (prefs.discreteModeEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscreteSound.entries.forEach { sound ->
                            FilterChip(
                                selected = prefs.discreteSound == sound,
                                onClick = {
                                    vm.setDiscreteSound(sound)
                                    playPreview(context, sound.resId)
                                },
                                label = { Text(sound.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Do Not Disturb", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "During these windows the app makes no sound, but Beacons still appear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            prefs.dndWindows.forEachIndexed { index, window ->
                DndWindowRow(
                    window = window,
                    onChange = { vm.updateDndWindow(index, it) },
                    onDelete = { vm.removeDndWindow(index) },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (prefs.dndWindows.size < 5) {
                OutlinedButton(onClick = { vm.addDndWindow() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add DND window (${prefs.dndWindows.size}/5)")
                }
            }
        }
    }
}

private fun playPreview(context: android.content.Context, soundResId: Int, volume: Float = 1.0f) {
    val player = MediaPlayer()
    player.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    )
    val afd = context.resources.openRawResourceFd(soundResId) ?: return
    afd.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
    player.setOnCompletionListener { it.release() }
    player.prepare()
    player.setVolume(volume, volume)
    player.start()
}

private val DAY_LABELS = listOf("Su", "M", "Tu", "W", "Th", "F", "Sa")
private val DAY_VALUES = listOf(7, 1, 2, 3, 4, 5, 6) // DayOfWeek.value: Su=7, M=1..Sa=6

@Composable
private fun DndWindowRow(window: DndWindow, onChange: (DndWindow) -> Unit, onDelete: () -> Unit) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = window.enabled, onCheckedChange = { onChange(window.copy(enabled = it)) })
            TextButton(onClick = { showStartPicker = true }) {
                Text("%02d:%02d".format(window.startHour, window.startMinute))
            }
            Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showEndPicker = true }) {
                Text("%02d:%02d".format(window.endHour, window.endMinute))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "Remove DND window")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DAY_LABELS.forEachIndexed { i, label ->
                val dayValue = DAY_VALUES[i]
                FilterChip(
                    selected = dayValue in window.days,
                    onClick = {
                        val newDays = if (dayValue in window.days) window.days - dayValue else window.days + dayValue
                        onChange(window.copy(days = newDays))
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = FilterChipDefaults.filterChipColors(),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = dayValue in window.days,
                    ),
                )
            }
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            initialHour = window.startHour,
            initialMinute = window.startMinute,
            onDismiss = { showStartPicker = false },
            onConfirm = { h, m ->
                onChange(window.copy(startHour = h, startMinute = m))
                showStartPicker = false
            },
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            initialHour = window.endHour,
            initialMinute = window.endMinute,
            onDismiss = { showEndPicker = false },
            onConfirm = { h, m ->
                onChange(window.copy(endHour = h, endMinute = m))
                showEndPicker = false
            },
        )
    }
}
